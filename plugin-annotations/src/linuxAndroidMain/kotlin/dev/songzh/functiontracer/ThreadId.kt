package dev.songzh.functiontracer

import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.ThreadLocal

private val nextThreadId = AtomicInt(1)

@ThreadLocal
private var threadLocalId: Int = 0

public actual fun traceCurrentThreadId(): String {
    if (threadLocalId == 0) threadLocalId = nextThreadId.getAndIncrement()
    return threadLocalId.toString()
}
