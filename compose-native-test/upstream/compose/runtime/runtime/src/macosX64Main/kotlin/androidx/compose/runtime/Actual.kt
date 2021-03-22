/*
 * Copyright 2020 The Android Open Source Project
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

internal actual open class ThreadLocal<T> actual constructor(
    initialValue: () -> T
) {
    private var value: T = initialValue()

    actual fun get(): T = value

    actual fun set(value: T) {
        this.value = value
    }
}

actual class AtomicReference<V> actual constructor(private var value: V) {
    actual fun get(): V = value

    actual fun set(value: V) {
        this.value = value
    }

    actual fun getAndSet(value: V): V {
        val oldValue = this.value
        this.value = value
        return oldValue
    }

    actual fun compareAndSet(expect: V, newValue: V): Boolean =
        if (expect == value) {
            value = newValue
            true
        } else {
            false
        }
}

internal actual fun identityHashCode(instance: Any?): Int {
    if (instance == null) {
        return 0
    }

    return instance.hashCode()
}

actual annotation class TestOnly

actual inline fun <R> synchronized(lock: Any, block: () -> R): R =
    block()

actual val DefaultMonotonicFrameClock: MonotonicFrameClock
    get() = DefaultChoreographerFrameClock

object DefaultChoreographerFrameClock : MonotonicFrameClock {
    override suspend fun <R> withFrameNanos(
        onFrame: (frameTimeNanos: Long) -> R
    ): R {
        TODO()
    }
}