package org.example

import dev.songzh.functiontracer.closeTraceLog
import kotlinx.cinterop.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
fun main() {
    val numThreads = 20
    val tids = Array(numThreads) { nativeHeap.alloc<pthread_tVar>() }

    for (i in 0 until numThreads) {
        pthread_create(tids[i].ptr, null, staticCFunction { _: COpaquePointer? ->
            runDemo()
            null
        }, null)
    }

    for (i in 0 until numThreads) {
        pthread_join(tids[i].value, null)
    }

    tids.forEach { nativeHeap.free(it) }
    closeTraceLog()
}
