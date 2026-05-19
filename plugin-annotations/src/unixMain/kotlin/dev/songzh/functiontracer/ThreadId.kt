package dev.songzh.functiontracer

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.pthread_self

@OptIn(ExperimentalForeignApi::class)
public actual fun traceCurrentThreadId(): String = pthread_self().toString()

