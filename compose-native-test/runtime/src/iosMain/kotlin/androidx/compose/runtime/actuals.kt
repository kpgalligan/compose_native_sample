package androidx.compose.runtime

// TODO The notion of a global EmbeddingContext actual should not exist! It breaks concurrent
//  usage on multiple threads which target multiple threads. This should always be pulled from the
//  Recomposer. https://issuetracker.google.com/issues/168110493
@kotlin.native.concurrent.ThreadLocal
var yoloGlobalEmbeddingContext: EmbeddingContext? = null

actual fun EmbeddingContext(): EmbeddingContext = yoloGlobalEmbeddingContext!!

internal actual fun recordSourceKeyInfo(key: Any) {}
actual fun keySourceInfoOf(key: Any): String? = null
actual fun resetSourceInfo() {}

internal actual object Trace {
	actual fun beginSection(name: String): Any? {
		return null
	}
	actual fun endSection(token: Any?) {
	}
}

actual annotation class TestOnly
actual annotation class MainThread
actual annotation class CheckResult(actual val suggest: String)

internal actual inline fun <R> synchronized(lock: Any, block: () -> R): R {
  // TODO Assuming single-threaded usage for now. DO NOT SHIP!
  return block()
}

// TODO Assuming single-threaded usage for now. DO NOT SHIP!
actual class AtomicReference<V> actual constructor(private var value: V) {
  actual fun get() = value

  actual fun set(value: V) {
    this.value = value
  }

  actual fun getAndSet(value: V): V {
    val old = this.value
    this.value = value
    return old
  }

  actual fun compareAndSet(expect: V, newValue: V): Boolean {
    if (value == expect) {
      value = newValue
      return true
    }
    return false
  }
}

// TODO Remove when https://issuetracker.google.com/issues/177245490 is fixed.
actual class WeakHashMap<K, V>: AbstractMutableMap<K, V>(), MutableMap<K, V>{
  override val entries get() = mutableSetOf<MutableMap.MutableEntry<K, V>>()
  override fun put(key: K, value: V): V? = null
}

// TODO Assuming single-threaded usage for now. DO NOT SHIP!
internal actual open class ThreadLocal<T>
actual constructor(
  private val initialValue: () -> T,
) {
  private var value: Any? = UNINITIALIZED

  @Suppress("UNCHECKED") // Can only be T at cast site.
  actual fun get(): T {
    var value = this.value
    if (value === UNINITIALIZED) {
      value = initialValue()
      this.value = value
    }
    return value as T
  }

  actual fun set(value: T) {
    this.value = value
  }

  private companion object {
    private val UNINITIALIZED = Any()
  }
}

// TODO This turns your hashing map into a linked list... DO NOT SHIP!
internal actual fun identityHashCode(instance: Any?) = 1
