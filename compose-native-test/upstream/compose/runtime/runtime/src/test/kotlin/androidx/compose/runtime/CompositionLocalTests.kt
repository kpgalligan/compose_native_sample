/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.compose.runtime

import androidx.compose.runtime.mock.Text
import androidx.compose.runtime.mock.compositionTest
import androidx.compose.runtime.mock.expectChanges
import androidx.compose.runtime.mock.expectNoChanges
import androidx.compose.runtime.mock.validate
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Create a normal (dynamic) CompositionLocal with a string value
val LocalSomeTextComposition = compositionLocalOf { "Default" }

// Create a normal (dynamic) CompositionLocal with an int value
val LocalSomeIntComposition = compositionLocalOf { 1 }

// Create a non-overridable CompositionLocal provider key
private val LocalSomeOtherIntProvider = compositionLocalOf { 1 }

// Make public the consumer key.
val LocalSomeOtherIntComposition: CompositionLocal<Int> = LocalSomeOtherIntProvider

// Create a static CompositionLocal with an int value
val LocalSomeStaticInt = staticCompositionLocalOf { 40 }

class CompositionLocalTests {

    @Composable
    fun ReadStringCompositionLocal(compositionLocal: CompositionLocal<String>) {
        Text(value = compositionLocal.current)
    }

    @Test
    fun testCompositionLocalApi() = compositionTest {
        compose {
            assertEquals("Default", LocalSomeTextComposition.current)
            assertEquals(1, LocalSomeIntComposition.current)
            CompositionLocalProvider(
                LocalSomeTextComposition provides "Test1",
                LocalSomeIntComposition provides 12,
                LocalSomeOtherIntProvider provides 42,
                LocalSomeStaticInt provides 50
            ) {
                assertEquals(
                    "Test1",
                    LocalSomeTextComposition.current
                )
                assertEquals(12, LocalSomeIntComposition.current)
                assertEquals(
                    42,
                    LocalSomeOtherIntComposition.current
                )
                assertEquals(50, LocalSomeStaticInt.current)
                CompositionLocalProvider(
                    LocalSomeTextComposition provides "Test2",
                    LocalSomeStaticInt provides 60
                ) {
                    assertEquals(
                        "Test2",
                        LocalSomeTextComposition.current
                    )
                    assertEquals(
                        12,
                        LocalSomeIntComposition.current
                    )
                    assertEquals(
                        42,
                        LocalSomeOtherIntComposition.current
                    )
                    assertEquals(60, LocalSomeStaticInt.current)
                }
                assertEquals(
                    "Test1",
                    LocalSomeTextComposition.current
                )
                assertEquals(12, LocalSomeIntComposition.current)
                assertEquals(
                    42,
                    LocalSomeOtherIntComposition.current
                )
                assertEquals(50, LocalSomeStaticInt.current)
            }
            assertEquals("Default", LocalSomeTextComposition.current)
            assertEquals(1, LocalSomeIntComposition.current)
        }
    }

    @Test
    fun recompose_Dynamic() = compositionTest {
        val invalidates = mutableListOf<RecomposeScope>()
        fun doInvalidate() = invalidates.forEach { it.invalidate() }.also { invalidates.clear() }
        var someText = "Unmodified"
        compose {
            invalidates.add(currentRecomposeScope)
            CompositionLocalProvider(
                LocalSomeTextComposition provides someText
            ) {
                ReadStringCompositionLocal(
                    compositionLocal = LocalSomeTextComposition
                )
            }
        }

        fun validate() {
            validate {
                Text(someText)
            }
        }

        someText = "Modified"
        doInvalidate()
        expectChanges()
        validate()
    }

    @Test
    fun recompose_Static() = compositionTest {
        val invalidates = mutableListOf<RecomposeScope>()
        fun doInvalidate() = invalidates.forEach { it.invalidate() }.also { invalidates.clear() }
        val staticStringCompositionLocal = staticCompositionLocalOf { "Default" }
        var someText = "Unmodified"
        compose {
            invalidates.add(currentRecomposeScope)
            CompositionLocalProvider(
                staticStringCompositionLocal provides someText
            ) {
                ReadStringCompositionLocal(
                    compositionLocal = staticStringCompositionLocal
                )
            }
        }

        fun validate() {
            validate {
                Text(someText)
            }
        }
        validate()

        someText = "Modified"
        doInvalidate()
        expectChanges()
        validate()
    }

    @Test
    fun subCompose_Dynamic() = compositionTest {
        val invalidates = mutableListOf<RecomposeScope>()
        fun doInvalidate() = invalidates.forEach { it.invalidate() }.also { invalidates.clear() }
        var someText = "Unmodified"
        compose {
            invalidates.add(currentRecomposeScope)

            CompositionLocalProvider(
                LocalSomeTextComposition provides someText,
                LocalSomeIntComposition provides 0
            ) {
                ReadStringCompositionLocal(compositionLocal = LocalSomeTextComposition)

                TestSubcomposition {
                    assertEquals(
                        someText,
                        LocalSomeTextComposition.current
                    )
                    assertEquals(0, LocalSomeIntComposition.current)

                    CompositionLocalProvider(
                        LocalSomeIntComposition provides 42
                    ) {
                        assertEquals(
                            someText,
                            LocalSomeTextComposition.current
                        )
                        assertEquals(
                            42,
                            LocalSomeIntComposition.current
                        )
                    }
                }
            }
        }

        fun validate() {
            validate {
                Text(someText)
            }
        }

        someText = "Modified"
        doInvalidate()
        expectChanges()
        validate()
    }

    @Test
    fun subCompose_Static() = compositionTest {
        val invalidates = mutableListOf<RecomposeScope>()
        fun doInvalidate() = invalidates.forEach { it.invalidate() }.also { invalidates.clear() }
        val staticSomeTextCompositionLocal = staticCompositionLocalOf { "Default" }
        val staticSomeIntCompositionLocal = staticCompositionLocalOf { -1 }
        var someText = "Unmodified"
        compose {
            invalidates.add(currentRecomposeScope)

            CompositionLocalProvider(
                staticSomeTextCompositionLocal provides someText,
                staticSomeIntCompositionLocal provides 0
            ) {
                assertEquals(someText, staticSomeTextCompositionLocal.current)
                assertEquals(0, staticSomeIntCompositionLocal.current)

                ReadStringCompositionLocal(
                    compositionLocal = staticSomeTextCompositionLocal,
                )

                TestSubcomposition {
                    assertEquals(someText, staticSomeTextCompositionLocal.current)
                    assertEquals(0, staticSomeIntCompositionLocal.current)

                    CompositionLocalProvider(
                        staticSomeIntCompositionLocal provides 42
                    ) {
                        assertEquals(someText, staticSomeTextCompositionLocal.current)
                        assertEquals(42, staticSomeIntCompositionLocal.current)
                    }
                }
            }
        }

        fun validate() {
            validate {
                Text(someText)
            }
        }

        someText = "Modified"
        doInvalidate()
        expectChanges()
        validate()
    }

    @Test
    fun deferredSubCompose_Dynamic() = compositionTest {
        val invalidates = mutableListOf<RecomposeScope>()
        fun doInvalidate() = invalidates.forEach { it.invalidate() }.also { invalidates.clear() }
        var someText = "Unmodified"
        var doSubCompose: () -> Unit = { error("Sub-compose callback not set") }
        compose {
            invalidates.add(currentRecomposeScope)

            CompositionLocalProvider(
                LocalSomeTextComposition provides someText,
                LocalSomeIntComposition provides 0
            ) {
                ReadStringCompositionLocal(
                    compositionLocal = LocalSomeTextComposition,
                )

                doSubCompose = testDeferredSubcomposition {
                    assertEquals(
                        someText,
                        LocalSomeTextComposition.current
                    )
                    assertEquals(0, LocalSomeIntComposition.current)

                    CompositionLocalProvider(
                        LocalSomeIntComposition provides 42
                    ) {
                        assertEquals(
                            someText,
                            LocalSomeTextComposition.current
                        )
                        assertEquals(
                            42,
                            LocalSomeIntComposition.current
                        )
                    }
                }
            }
        }

        fun validate() {
            validate {
                Text(someText)
            }
        }
        validate()
        doSubCompose()

        someText = "Modified"
        doInvalidate()
        expectChanges()
        validate()

        doSubCompose()
    }

    @Test
    fun deferredSubCompose_Static() = compositionTest {
        val invalidates = mutableListOf<RecomposeScope>()
        fun doInvalidate() = invalidates.forEach { it.invalidate() }.also { invalidates.clear() }
        var someText = "Unmodified"
        var doSubCompose: () -> Unit = { error("Sub-compose callback not set") }
        val staticSomeTextCompositionLocal = staticCompositionLocalOf { "Default" }
        val staticSomeIntCompositionLocal = staticCompositionLocalOf { -1 }
        compose {
            invalidates.add(currentRecomposeScope)

            CompositionLocalProvider(
                staticSomeTextCompositionLocal provides someText,
                staticSomeIntCompositionLocal provides 0
            ) {
                assertEquals(someText, staticSomeTextCompositionLocal.current)
                assertEquals(0, staticSomeIntCompositionLocal.current)

                ReadStringCompositionLocal(
                    compositionLocal = staticSomeTextCompositionLocal
                )

                doSubCompose = testDeferredSubcomposition {

                    assertEquals(someText, staticSomeTextCompositionLocal.current)
                    assertEquals(0, staticSomeIntCompositionLocal.current)

                    CompositionLocalProvider(
                        staticSomeIntCompositionLocal provides 42
                    ) {
                        assertEquals(someText, staticSomeTextCompositionLocal.current)
                        assertEquals(42, staticSomeIntCompositionLocal.current)
                    }
                }
            }
        }

        fun validate() {
            validate {
                Text(someText)
            }
        }
        validate()
        doSubCompose()

        someText = "Modified"
        doInvalidate()
        expectChanges()
        validate()

        doSubCompose()
    }

    @Test
    fun deferredSubCompose_Nested_Static() = compositionTest {
        val invalidates = mutableListOf<RecomposeScope>()
        fun doInvalidate() = invalidates.forEach { it.invalidate() }.also { invalidates.clear() }
        var someText = "Unmodified"
        var doSubCompose1: () -> Unit = { error("Sub-compose-1 callback not set") }
        var doSubCompose2: () -> Unit = { error("Sub-compose-2 callback not set") }
        val staticSomeTextCompositionLocal = staticCompositionLocalOf { "Default" }
        val staticSomeIntCompositionLocal = staticCompositionLocalOf { -1 }
        compose {
            invalidates.add(currentRecomposeScope)

            CompositionLocalProvider(
                staticSomeTextCompositionLocal provides someText,
                staticSomeIntCompositionLocal provides 0
            ) {
                assertEquals(someText, staticSomeTextCompositionLocal.current)
                assertEquals(0, staticSomeIntCompositionLocal.current)

                ReadStringCompositionLocal(
                    compositionLocal = staticSomeTextCompositionLocal
                )

                doSubCompose1 = testDeferredSubcomposition {

                    assertEquals(someText, staticSomeTextCompositionLocal.current)
                    assertEquals(0, staticSomeIntCompositionLocal.current)

                    doSubCompose2 = testDeferredSubcomposition {
                        assertEquals(someText, staticSomeTextCompositionLocal.current)
                        assertEquals(0, staticSomeIntCompositionLocal.current)
                    }
                }
            }
        }

        fun validate() {
            validate {
                Text(someText)
            }
        }
        validate()
        doSubCompose1()
        doSubCompose2()

        someText = "Modified"
        doInvalidate()
        expectChanges()
        validate()

        doSubCompose1()
        doSubCompose2()
    }

    @Test
    fun insertShouldSeePreviouslyProvidedValues() = compositionTest {
        val invalidates = mutableListOf<RecomposeScope>()
        fun doInvalidate() = invalidates.forEach { it.invalidate() }.also { invalidates.clear() }
        val someStaticString = staticCompositionLocalOf { "Default" }
        var shouldRead = false
        compose {
            CompositionLocalProvider(
                someStaticString provides "Provided A"
            ) {
                invalidates.add(currentRecomposeScope)
                if (shouldRead)
                    ReadStringCompositionLocal(someStaticString)
            }
        }

        fun validate() {
            validate {
                if (shouldRead)
                    Text("Provided A")
            }
        }
        validate()

        shouldRead = true
        doInvalidate()
        expectChanges()
        validate()
    }

    @Test
    fun providingANewDataClassValueShouldNotRecompose() = compositionTest {
        val invalidates = mutableListOf<RecomposeScope>()
        fun doInvalidate() = invalidates.forEach { it.invalidate() }.also { invalidates.clear() }
        val someDataCompositionLocal = compositionLocalOf(structuralEqualityPolicy()) { SomeData() }
        var composed = false

        @Composable
        fun ReadSomeDataCompositionLocal(
            compositionLocal: CompositionLocal<SomeData>
        ) {
            composed = true
            Text(value = compositionLocal.current.value)
        }

        compose {
            invalidates.add(currentRecomposeScope)
            CompositionLocalProvider(
                someDataCompositionLocal provides SomeData("provided")
            ) {
                ReadSomeDataCompositionLocal(someDataCompositionLocal)
            }
        }

        assertTrue(composed)
        composed = false
        doInvalidate()
        expectNoChanges()
        assertFalse(composed)
    }
}

private data class SomeData(val value: String = "default")