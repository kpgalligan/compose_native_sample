package androidx.compose.runtime.dispatch

// The old Kotlin stdlib function is used. By declaring an expect here we can intercept its
// resolution and retarget it to our own expect/actual.
// TODO Remove when https://issuetracker.google.com/issues/177245498 is fixed.
internal expect inline fun <R> synchronized(lock: Any, block: () -> R): R
