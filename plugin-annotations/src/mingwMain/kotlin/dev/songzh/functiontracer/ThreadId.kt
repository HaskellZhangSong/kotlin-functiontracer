package dev.songzh.functiontracer

import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.GetCurrentThreadId

@OptIn(ExperimentalForeignApi::class)
public actual fun traceCurrentThreadId(): String = GetCurrentThreadId().toString()

