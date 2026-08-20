package com.arflix.tv.util

/**
 * IPTV-PERF F6.1: tiny timing breadcrumbs for the Live TV hot paths
 * (tune, window append, focus commit, indexed guide window).
 *
 * Filter logcat with `IPTV-PERF`. Writes go to System.err only, so the cost is
 * a single println — acceptable on these already-instrumented paths.
 */
object IptvPerfTracer {

    private const val TAG = "IPTV-PERF"

    fun <T> trace(label: String, block: () -> T): T {
        val startMs = System.currentTimeMillis()
        return try {
            block()
        } finally {
            log("$label ${System.currentTimeMillis() - startMs}ms")
        }
    }

    fun log(message: String) {
        System.err.println("[$TAG] $message")
    }
}