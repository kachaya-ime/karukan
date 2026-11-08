package io.github.kachaya.karukan

import android.util.Log

/**
 * Utility functions for logging that only output logs when [BuildConfig.DEBUG] is true.
 */
@Suppress("NOTHING_TO_INLINE")
inline fun logV(tag: String, msg: String, tr: Throwable? = null) {
    if (BuildConfig.DEBUG) {
        if (tr != null) {
            Log.v(tag, msg, tr)
        } else {
            Log.v(tag, msg)
        }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun logD(tag: String, msg: String, tr: Throwable? = null) {
    if (BuildConfig.DEBUG) {
        if (tr != null) {
            Log.d(tag, msg, tr)
        } else {
            Log.d(tag, msg)
        }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun logI(tag: String, msg: String, tr: Throwable? = null) {
    if (BuildConfig.DEBUG) {
        if (tr != null) {
            Log.i(tag, msg, tr)
        } else {
            Log.i(tag, msg)
        }
    }
}
