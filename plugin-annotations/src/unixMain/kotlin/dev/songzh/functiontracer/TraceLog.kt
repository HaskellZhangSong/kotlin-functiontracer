package dev.songzh.functiontracer

import kotlinx.cinterop.*
import platform.posix.*

// ---------------------------------------------------------------------------
// Async writer thread
//
// Producer threads call traceLog() which simply appends to a shared ArrayList
// under a POSIX mutex and signals the condition variable.
//
// A single background thread wakes up, grabs the whole batch in one lock, then
// writes everything to the appropriate files outside the lock.  File handles are
// kept open across batches so every write is just fputs + fflush-per-batch.
// ---------------------------------------------------------------------------

/**
 * A log entry: the destination file path (empty = stdout) and the message text.
 *
 * Stored as two interleaved elements in [AsyncWriter.buf] — even index = path,
 * odd index = message — to avoid allocating a wrapper object per log call.
 */

@OptIn(ExperimentalForeignApi::class)
private object AsyncWriter {
    // Synchronisation primitives on the native heap — they live for the whole
    // process so we never free them.
    val mutex: pthread_mutex_t = nativeHeap.alloc()
    val cond:  pthread_cond_t  = nativeHeap.alloc()
    val tid:   pthread_tVar    = nativeHeap.alloc()

    /**
     * Pending (path, message) pairs, interleaved: [path0, msg0, path1, msg1, …].
     * Must only be accessed while holding [mutex].
     */
    val buf: ArrayList<String> = ArrayList(256)

    /**
     * Set to true inside [stop] while holding [mutex], so [enqueue]'s
     * double-checked locking sees the update correctly.
     * Also read outside the lock (fast path) so must be @Volatile.
     */
    @kotlin.concurrent.Volatile var stopped = false

    /** Drives the writer-thread loop exit condition. Always set together with [stopped]. */
    @kotlin.concurrent.Volatile var shutdown = false

    /**
     * Whether the writer thread was successfully started. If false, [pthread_join]
     * must never be called because [tid] was never initialised by the OS.
     */
    @kotlin.concurrent.Volatile var threadStarted = false

    /**
     * Set to true by the winning [stop] caller after [pthread_join] returns (or
     * immediately when [pthread_create] failed and there is no thread to join).
     * Concurrent [stop] callers spin-wait on this before returning, so no caller
     * can return from [stop] before the writer thread has fully exited.
     */
    @kotlin.concurrent.Volatile var joined = false

    init {
        pthread_mutex_init(mutex.ptr, null)
        pthread_cond_init(cond.ptr, null)

        // Start the background writer thread.
        // If thread creation fails (e.g. system thread limit reached), mark as
        // already stopped so every subsequent traceLog() call is a silent no-op
        // rather than enqueuing into a buffer that will never be drained.
        val rc = pthread_create(tid.ptr, null, staticCFunction { _: COpaquePointer? ->
            AsyncWriter.loop()
            null
        }, null)
        if (rc != 0) {
            stopped  = true
            shutdown = true
            joined   = true  // no thread to join — spin-waiters in stop() can proceed immediately
            // skip atexit — there is nothing to drain
        } else {
            threadStarted = true
            // Best-effort drain on normal process exit — mirrors closeTraceLog().
            atexit(staticCFunction<Unit> { AsyncWriter.stop() })
        }
    }

    /**
     * Enqueue one (path, message) pair — called from any producer thread.
     *
     * Uses double-checked locking so threads that call this after [stop] pay
     * only a single volatile read with no lock contention.
     */
    fun enqueue(path: String, message: String) {
        // Fast-path: if the writer is already gone, drop the message immediately.
        if (stopped) return
        pthread_mutex_lock(mutex.ptr)
        // Double-check: stop() atomically sets stopped under this same mutex, so
        // any enqueue() that reaches here after stop() has released the lock will
        // see stopped == true and bail out without touching buf.
        if (stopped) {
            pthread_mutex_unlock(mutex.ptr)
            return
        }
        buf.add(path)
        buf.add(message)
        pthread_cond_signal(cond.ptr)
        pthread_mutex_unlock(mutex.ptr)
    }

    /**
     * Signal shutdown, wait for the writer to drain and exit, then return.
     *
     * [stopped] is set **inside** the mutex so it is guaranteed to be visible
     * to any [enqueue] call that acquires the mutex afterwards — eliminating the
     * check-then-act race that would occur if [stopped] were set before locking.
     *
     * Safe to call from multiple threads simultaneously or more than once.
     */
    fun stop() {
        pthread_mutex_lock(mutex.ptr)
        if (stopped) {
            pthread_mutex_unlock(mutex.ptr)
            // Another thread already called stop().  Wait until that thread's
            // pthread_join completes so we don't return before the writer exits.
            while (!joined) sched_yield()
            return
        }
        stopped  = true
        shutdown = true
        pthread_cond_signal(cond.ptr)
        pthread_mutex_unlock(mutex.ptr)

        // Block until the writer thread has flushed and closed everything.
        // Guard: if the thread was never started, tid is uninitialised — never join it.
        if (threadStarted) pthread_join(tid.value, null)
        joined = true   // visible to any concurrent stop() caller spin-waiting above
    }

    /** Body of the background writer thread. */
    fun loop() {
        val files = HashMap<String, CPointer<FILE>>()
        val batch = ArrayList<String>(256)

        while (true) {
            // ---- wait for work ----
            pthread_mutex_lock(mutex.ptr)
            // The while-loop guards against spurious wakeups (required by POSIX).
            while (buf.isEmpty() && !shutdown) {
                pthread_cond_wait(cond.ptr, mutex.ptr)
            }
            // Drain the shared buffer in O(n) under the lock (copy, then clear).
            // Producers are unblocked as soon as we release below.
            batch.addAll(buf)
            buf.clear()
            val done = shutdown
            pthread_mutex_unlock(mutex.ptr)

            // ---- write batch outside the lock ----
            var i = 0
            while (i < batch.size) {
                val path = batch[i]
                val msg  = batch[i + 1]
                i += 2

                if (path.isEmpty()) {
                    println(msg)
                } else {
                    var f = files[path]
                    if (f == null) {
                        f = fopen(path, "a")
                        if (f != null) files[path] = f
                    }
                    f?.let { fputs(msg + "\n", it) }
                }
            }
            batch.clear()

            // One fflush per file per batch — far cheaper than fflush-per-line.
            files.values.forEach { fflush(it) }

            if (done) break
        }

        // Drain complete — close every file handle cleanly before the thread exits.
        files.values.forEach { fclose(it) }
    }
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

public actual fun traceLog(message: String, logFile: String) {
    AsyncWriter.enqueue(logFile, message)
}

/**
 * Blocks until all enqueued trace messages have been written to disk and all
 * log file handles have been closed.  Subsequent [traceLog] calls after this
 * point are silently dropped.
 *
 * Calling this is **optional** — a best-effort drain is registered via
 * `atexit` automatically on the first [traceLog] call.
 */
public actual fun closeTraceLog() {
    AsyncWriter.stop()
}
