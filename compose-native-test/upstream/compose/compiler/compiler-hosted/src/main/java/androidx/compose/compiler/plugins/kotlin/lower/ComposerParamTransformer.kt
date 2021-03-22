/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.compiler.plugins.kotlin.lower

import androidx.compose.compiler.plugins.kotlin.KtxNameConventions
import androidx.compose.compiler.plugins.kotlin.analysis.ComposeWritableSlices
import androidx.compose.compiler.plugins.kotlin.hasComposableAnnotation
import androidx.compose.compiler.plugins.kotlin.irTrace
import androidx.compose.compiler.plugins.kotlin.isComposableCallable
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.jvm.ir.isInlineParameter
import org.jetbrains.kotlin.backend.jvm.lower.inlineclasses.InlineClassAbi
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.PropertyGetterDescriptor
import org.jetbrains.kotlin.descriptors.PropertySetterDescriptor
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.copyAttributes
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedFunctionDescriptorWithContainerSource
import org.jetbrains.kotlin.ir.descriptors.WrappedPropertyGetterDescriptor
import org.jetbrains.kotlin.ir.descriptors.WrappedPropertySetterDescriptor
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.copyTypeArgumentsFrom
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrCompositeImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.createType
import org.jetbrains.kotlin.ir.types.isPrimitiveType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.DeepCopySymbolRemapper
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.explicitParameters
import org.jetbrains.kotlin.ir.util.findAnnotation
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isInlined
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.inline.InlineUtil
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DescriptorWithContainerSource
import org.jetbrains.kotlin.util.OperatorNameConventions
import kotlin.math.min

@Suppress("DEPRECATION")
class ComposerParamTransformer(
    context: IrPluginContext,
    symbolRemapper: DeepCopySymbolRemapper,
    bindingTrace: BindingTrace
) :
    AbstractComposeLowering(context, symbolRemapper, bindingTrace),
    ModuleLoweringPass {

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    override fun lower(module: IrModuleFragment) {
        module.transformChildrenVoid(this)

        module.acceptVoid(symbolRemapper)

        val typeRemapper = ComposerTypeRemapper(
            context,
            symbolRemapper,
            typeTranslator,
            composerType
        )
        // for each declaration, we create a deepCopy transformer It is important here that we
        // use the "preserving metadata" variant since we are using this copy to *replace* the
        // originals, or else the module we would produce wouldn't have any metadata in it.
        val transformer = DeepCopyIrTreeWithSymbolsPreservingMetadata(
            context,
            symbolRemapper,
            typeRemapper,
            typeTranslator
        ).also { typeRemapper.deepCopy = it }
        module.transformChildren(
            transformer,
            null
        )
        // just go through and patch all of the parents to make sure things are properly wired
        // up.
        module.patchDeclarationParents()
    }

    private val transformedFunctions: MutableMap<IrSimpleFunction, IrSimpleFunction> =
        mutableMapOf()

    private val transformedFunctionSet = mutableSetOf<IrFunction>()

    private val composerType = composerIrClass.defaultType.replaceArgumentsWithStarProjections()

    override fun visitFunction(declaration: IrFunction): IrStatement {
        val v1 = declaration.withComposerParamIfNeeded()
        val v2 = super.visitFunction(v1)
        return v2
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    fun IrCall.withComposerParamIfNeeded(composerParam: IrValueParameter): IrCall {
        val isComposableLambda = isComposableLambdaInvoke()
        if (!symbol.descriptor.isComposableCallable() && !isComposableLambda)
            return this
        val ownerFn = when {
            isComposableLambda -> {
                symbol.owner.lambdaInvokeWithComposerParam()
            }
            else -> (symbol.owner).withComposerParamIfNeeded()
        }
        if (!isComposableLambda && !transformedFunctionSet.contains(ownerFn))
            return this
        if (symbol.owner == ownerFn)
            return this
        return IrCallImpl(
            startOffset,
            endOffset,
            type,
            ownerFn.symbol as IrSimpleFunctionSymbol,
            typeArgumentsCount,
            ownerFn.valueParameters.size,
            origin,
            superQualifierSymbol
        ).also {
            it.copyAttributes(this)
            context.irTrace.record(
                ComposeWritableSlices.IS_COMPOSABLE_CALL,
                it,
                true
            )
            it.copyTypeArgumentsFrom(this)
            it.dispatchReceiver = dispatchReceiver
            it.extensionReceiver = extensionReceiver
            val argumentsMissing = mutableListOf<Boolean>()
            for (i in 0 until valueArgumentsCount) {
                val arg = getValueArgument(i)
                argumentsMissing.add(arg == null)
                if (arg != null) {
                    it.putValueArgument(i, arg)
                } else {
                    it.putValueArgument(i, defaultArgumentFor(ownerFn.valueParameters[i]))
                }
            }
            val realValueParams = valueArgumentsCount
            var argIndex = valueArgumentsCount
            it.putValueArgument(
                argIndex++,
                IrGetValueImpl(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    composerParam.symbol
                )
            )

            // $changed[n]
            for (i in 0 until changedParamCount(realValueParams, ownerFn.thisParamCount)) {
                if (argIndex < ownerFn.valueParameters.size) {
                    it.putValueArgument(
                        argIndex++,
                        irConst(0)
                    )
                } else {
                    error("1. expected value parameter count to be higher: ${this.dumpSrc()}")
                }
            }

            // $default[n]
            for (i in 0 until defaultParamCount(realValueParams)) {
                val start = i * BITS_PER_INT
                val end = min(start + BITS_PER_INT, realValueParams)
                if (argIndex < ownerFn.valueParameters.size) {
                    val bits = argumentsMissing
                        .toBooleanArray()
                        .sliceArray(start until end)
                    it.putValueArgument(
                        argIndex++,
                        irConst(bitMask(*bits))
                    )
                } else if (argumentsMissing.any { it }) {
                    error("2. expected value parameter count to be higher: ${this.dumpSrc()}")
                }
            }
        }
    }

    private fun defaultArgumentFor(param: IrValueParameter): IrExpression? {
        if (param.varargElementType != null) return null
        return param.type.defaultValue().let {
            IrCompositeImpl(
                it.startOffset,
                it.endOffset,
                it.type,
                IrStatementOrigin.DEFAULT_VALUE,
                listOf(it)
            )
        }
    }

    // TODO(lmr): There is an equivalent function in IrUtils, but we can't use it because it
    //  expects a JvmBackendContext. That implementation uses a special "unsafe coerce" builtin
    //  method, but we don't have access to that so instead we are just going to construct the
    //  inline class itself and hope that it gets lowered properly.
    private fun IrType.defaultValue(
        startOffset: Int = UNDEFINED_OFFSET,
        endOffset: Int = UNDEFINED_OFFSET
    ): IrExpression {
        val classSymbol = classOrNull
        if (this !is IrSimpleType || hasQuestionMark || classSymbol?.owner?.isInline != true)
            return IrConstImpl.defaultValueForType(startOffset, endOffset, this)

        val klass = classSymbol.owner
        val ctor = classSymbol.constructors.first()
        val underlyingType = InlineClassAbi.getUnderlyingType(klass)

        // TODO(lmr): We should not be calling the constructor here, but this seems like a
        //  reasonable interim solution. We should figure out how to get access to the unsafe
        //  coerce and use that instead if possible.
        return IrConstructorCallImpl(
            startOffset,
            endOffset,
            this,
            ctor,
            typeArgumentsCount = 0,
            constructorTypeArgumentsCount = 0,
            valueArgumentsCount = 1,
            origin = null
        ).also {
            it.putValueArgument(0, underlyingType.defaultValue(startOffset, endOffset))
        }
    }

    // Transform `@Composable fun foo(params): RetType` into `fun foo(params, $composer: Composer): RetType`
    @OptIn(ObsoleteDescriptorBasedAPI::class)
    private fun IrFunction.withComposerParamIfNeeded(): IrFunction {
        // don't transform functions that themselves were produced by this function. (ie, if we
        // call this with a function that has the synthetic composer parameter, we don't want to
        // transform it further).
        if (transformedFunctionSet.contains(this)) return this

        // if not a composable fn, nothing we need to do
        if (!descriptor.isComposableCallable(context.bindingContext)) {
            return this
        }

        // if this function is an inlined lambda passed as an argument to an inline function (and
        // is NOT a composable lambda), then we don't want to transform it. Ideally, this
        // wouldn't have gotten this far because the `isComposable()` check above should return
        // false, but right now the composable annotation checker seems to produce a
        // false-positive here. It is important that we *DO NOT* transform this, but we should
        // probably fix the annotation checker instead.
        // TODO(b/147250515)
        if (isNonComposableInlinedLambda()) return this

        // we don't bother transforming expect functions. They exist only for type resolution and
        // don't need to be transformed to have a composer parameter
        if (isExpect) return this

        // cache the transformed function with composer parameter
        return transformedFunctions[this] ?: copyWithComposerParam()
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    private fun IrFunction.lambdaInvokeWithComposerParam(): IrFunction {
        val descriptor = descriptor
        val argCount = descriptor.valueParameters.size
        val extraParams = composeSyntheticParamCount(argCount, hasDefaults = false)
        val newFnClass = context.function(argCount + extraParams).owner
        val newInvoke = newFnClass.functions.first {
            it.name == OperatorNameConventions.INVOKE
        }
        return newInvoke
    }

    private fun wrapDescriptor(descriptor: FunctionDescriptor): WrappedSimpleFunctionDescriptor {
        return when (descriptor) {
            is PropertyGetterDescriptor ->
                WrappedPropertyGetterDescriptor()
            is PropertySetterDescriptor ->
                WrappedPropertySetterDescriptor()
            is DescriptorWithContainerSource ->
                WrappedFunctionDescriptorWithContainerSource()
            else ->
                object : WrappedSimpleFunctionDescriptor() {
                    override fun getSource(): SourceElement {
                        return descriptor.source
                    }
                }
        }
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    private fun IrFunction.copy(
        isInline: Boolean = this.isInline,
        modality: Modality = descriptor.modality
    ): IrSimpleFunction {
        // TODO(lmr): use deepCopy instead?
        val descriptor = descriptor
        val newDescriptor = wrapDescriptor(descriptor)

        return IrFunctionImpl(
            startOffset,
            endOffset,
            origin,
            IrSimpleFunctionSymbolImpl(newDescriptor),
            name,
            visibility,
            modality,
            returnType,
            isInline,
            isExternal,
            descriptor.isTailrec,
            descriptor.isSuspend,
            descriptor.isOperator,
            descriptor.isInfix,
            isExpect,
            isFakeOverride,
            containerSource
        ).also { fn ->
            newDescriptor.bind(fn)
            if (this is IrSimpleFunction) {
                val propertySymbol = correspondingPropertySymbol
                if (propertySymbol != null) {
                    fn.correspondingPropertySymbol = propertySymbol
                    if (propertySymbol.owner.getter == this) {
                        propertySymbol.owner.getter = fn
                    }
                    if (propertySymbol.owner.setter == this) {
                        propertySymbol.owner.setter = this
                    }
                }
            }
            fn.parent = parent
            fn.typeParameters = this.typeParameters.map {
                it.parent = fn
                it
            }
            fn.dispatchReceiverParameter = dispatchReceiverParameter?.copyTo(fn)
            fn.extensionReceiverParameter = extensionReceiverParameter?.copyTo(fn)
            fn.valueParameters = valueParameters.map { p ->
                // Composable lambdas will always have `IrGet`s of all of their parameters
                // generated, since they are passed into the restart lambda. This causes an
                // interesting corner case with "anonymous parameters" of composable functions.
                // If a parameter is anonymous (using the name `_`) in user code, you can usually
                // make the assumption that it is never used, but this is technically not the
                // case in composable lambdas. The synthetic name that kotlin generates for
                // anonymous parameters has an issue where it is not safe to dex, so we sanitize
                // the names here to ensure that dex is always safe.
                p.copyTo(fn, name = dexSafeName(p.name))
            }
            fn.annotations = annotations.map { a -> a }
            fn.metadata = metadata
            fn.body = body
        }
    }

    private fun dexSafeName(name: Name): Name {
        return if (name.isSpecial && name.asString().contains(' ')) {
            val sanitized = name
                .asString()
                .replace(' ', '$')
                .replace('<', '$')
                .replace('>', '$')
            Name.identifier(sanitized)
        } else name
    }

    private fun jvmNameAnnotation(name: String): IrConstructorCall {
        val jvmName = getTopLevelClass(DescriptorUtils.JVM_NAME)
        val ctor = jvmName.constructors.first { it.owner.isPrimary }
        val type = jvmName.createType(false, emptyList())
        return IrConstructorCallImpl(
            UNDEFINED_OFFSET,
            UNDEFINED_OFFSET,
            type,
            ctor,
            0, 0, 1
        ).also {
            it.putValueArgument(
                0,
                IrConstImpl.string(
                    UNDEFINED_OFFSET,
                    UNDEFINED_OFFSET,
                    builtIns.stringType,
                    name
                )
            )
        }
    }

    private fun IrFunction.requiresDefaultParameter(): Boolean {
        // we only add a default mask parameter if one of the parameters has a default
        // expression. Note that if this is a "fake override" method, then only the overridden
        // symbols will have the default value expressions
        return this is IrSimpleFunction && (
            valueParameters.any {
                it.defaultValue != null
            } || overriddenSymbols.any { it.owner.requiresDefaultParameter() }
            )
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    private fun IrFunction.copyWithComposerParam(): IrSimpleFunction {
        assert(explicitParameters.lastOrNull()?.name != KtxNameConventions.COMPOSER_PARAMETER) {
            "Attempted to add composer param to $this, but it has already been added."
        }
        return copy().also { fn ->
            val oldFn = this

            // NOTE: it's important to add these here before we recurse into the body in
            // order to avoid an infinite loop on circular/recursive calls
            transformedFunctionSet.add(fn)
            transformedFunctions[oldFn as IrSimpleFunction] = fn

            // The overridden symbols might also be composable functions, so we want to make sure
            // and transform them as well
            if (this is IrOverridableDeclaration<*>) {
                fn.overriddenSymbols = overriddenSymbols.map {
                    it as IrSimpleFunctionSymbol
                    val owner = it.owner
                    val newOwner = owner.withComposerParamIfNeeded()
                    newOwner.symbol as IrSimpleFunctionSymbol
                }
            }

            // if we are transforming a composable property, the jvm signature of the
            // corresponding getters and setters have a composer parameter. Since Kotlin uses the
            // lack of a parameter to determine if it is a getter, this breaks inlining for
            // composable property getters since it ends up looking for the wrong jvmSignature.
            // In this case, we manually add the appropriate "@JvmName" annotation so that the
            // inliner doesn't get confused.
            val descriptor = descriptor
            if (descriptor is PropertyGetterDescriptor &&
                fn.annotations.findAnnotation(DescriptorUtils.JVM_NAME) == null
            ) {
                val name = JvmAbi.getterName(descriptor.correspondingProperty.name.identifier)
                fn.annotations += jvmNameAnnotation(name)
                fn.correspondingPropertySymbol?.owner?.getter = fn
            }

            // same thing for the setter
            if (descriptor is PropertySetterDescriptor &&
                fn.annotations.findAnnotation(DescriptorUtils.JVM_NAME) == null
            ) {
                val name = JvmAbi.setterName(descriptor.correspondingProperty.name.identifier)
                fn.annotations += jvmNameAnnotation(name)
                fn.correspondingPropertySymbol?.owner?.setter = fn
            }

            fn.valueParameters = fn.valueParameters.map { param ->
                val newType = defaultParameterType(param)
                IrValueParameterImpl(
                    param.startOffset,
                    param.endOffset,
                    param.origin,
                    IrValueParameterSymbolImpl(param.descriptor),
                    param.name,
                    index = param.index,
                    type = newType, varargElementType = param.varargElementType,
                    isCrossinline = param.isCrossinline,
                    isNoinline = param.isNoinline,
                    isHidden = false,
                    isAssignable = param.defaultValue != null
                ).also {
                    it.defaultValue = param.defaultValue
                    it.parent = param.parent
                }
            }

            val valueParametersMapping = explicitParameters
                .zip(fn.explicitParameters)
                .toMap()

            val realParams = fn.valueParameters.size

            // $composer
            val composerParam = fn.addValueParameter {
                name = KtxNameConventions.COMPOSER_PARAMETER
                type = composerType.makeNullable()
                origin = IrDeclarationOrigin.DEFINED
                isAssignable = true
            }

            // $changed[n]
            val changed = KtxNameConventions.CHANGED_PARAMETER.identifier
            for (i in 0 until changedParamCount(realParams, fn.thisParamCount)) {
                fn.addValueParameter(
                    if (i == 0) changed else "$changed$i",
                    context.irBuiltIns.intType
                )
            }

            // $default[n]
            if (fn.requiresDefaultParameter()) {
                val defaults = KtxNameConventions.DEFAULT_PARAMETER.identifier
                for (i in 0 until defaultParamCount(realParams)) {
                    fn.addValueParameter(
                        if (i == 0) defaults else "$defaults$i",
                        context.irBuiltIns.intType,
                        IrDeclarationOrigin.MASK_FOR_DEFAULT_FUNCTION
                    )
                }
            }

            fn.transformChildrenVoid(object : IrElementTransformerVoid() {
                var isNestedScope = false
                override fun visitGetValue(expression: IrGetValue): IrGetValue {
                    val newParam = valueParametersMapping[expression.symbol.owner]
                    return if (newParam != null) {
                        IrGetValueImpl(
                            expression.startOffset,
                            expression.endOffset,
                            expression.type,
                            newParam.symbol,
                            expression.origin
                        )
                    } else expression
                }

                override fun visitReturn(expression: IrReturn): IrExpression {
                    if (expression.returnTargetSymbol == oldFn.symbol) {
                        // update the return statement to point to the new function, or else
                        // it will be interpreted as a non-local return
                        return super.visitReturn(
                            IrReturnImpl(
                                expression.startOffset,
                                expression.endOffset,
                                expression.type,
                                fn.symbol,
                                expression.value
                            )
                        )
                    }
                    return super.visitReturn(expression)
                }

                override fun visitFunction(declaration: IrFunction): IrStatement {
                    val wasNested = isNestedScope
                    try {
                        // we don't want to pass the composer parameter in to composable calls
                        // inside of nested scopes.... *unless* the scope was inlined.
                        isNestedScope =
                            if (declaration.isNonComposableInlinedLambda()) wasNested else true
                        return super.visitFunction(declaration)
                    } finally {
                        isNestedScope = wasNested
                    }
                }

                override fun visitCall(expression: IrCall): IrExpression {
                    val expr = if (!isNestedScope) {
                        expression.withComposerParamIfNeeded(composerParam)
                    } else
                        expression
                    return super.visitCall(expr)
                }
            })
        }
    }

    fun defaultParameterType(param: IrValueParameter): IrType {
        val type = param.type
        if (param.defaultValue == null) return type
        return when {
            type.isPrimitiveType() -> type
            type.isInlined() -> type
            else -> type.makeNullable()
        }
    }

    fun IrCall.isInlineParameterLambdaInvoke(): Boolean {
        if (origin != IrStatementOrigin.INVOKE) return false
        val lambda = dispatchReceiver as? IrGetValue
        val valueParameter = lambda?.symbol?.owner as? IrValueParameter
        return valueParameter?.isInlineParameter() == true
    }

    private fun IrCall.isComposableLambdaInvoke(): Boolean {
        return isInvoke() && dispatchReceiver?.type?.hasComposableAnnotation() == true
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    private fun IrFunction.isNonComposableInlinedLambda(): Boolean {
        descriptor.findPsi()?.let { psi ->
            (psi as? KtFunctionLiteral)?.let {
                val arg = InlineUtil.getInlineArgumentDescriptor(
                    it,
                    context.bindingContext
                ) ?: return false

                return !arg.type.hasComposableAnnotation()
            }
        }
        return false
    }
}
