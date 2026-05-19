package dev.songzh.functiontracer

// Windows (mingwX64) stub — no pthreads available.
// Falls back to synchronous stdout logging.
public actual fun traceLog(message: String, logFile: String): Unit = println(message)
public actual fun closeTraceLog(): Unit = Unit

