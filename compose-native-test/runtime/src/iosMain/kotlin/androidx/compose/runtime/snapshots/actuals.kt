package androidx.compose.runtime.snapshots

@PublishedApi
internal actual inline fun <R> synchronized(lock: Any, block: () -> R): R {
  // TODO assuming single-threaded usage for now. DO NOT SHIP!
  return block()
}
