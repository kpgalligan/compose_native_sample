package androidx.compose.runtime.dispatch

val DefaultMonotonicFrameClock: MonotonicFrameClock get() {
  throw UnsupportedOperationException(
    "No default MonotonicFrameClock! You must include one in your CoroutineContext.")
}
