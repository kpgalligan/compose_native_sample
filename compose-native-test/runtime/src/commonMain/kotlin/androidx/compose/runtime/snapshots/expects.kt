package androidx.compose.runtime.snapshots

// The old Kotlin stdlib function is used. By declaring an expect here we can intercept its
// resolution and retarget it to our own expect/actual.
// TODO Remove when http://r.android.com/1547257 lands.
@PublishedApi
internal expect inline fun <R> synchronized(lock: Any, block: () -> R): R
