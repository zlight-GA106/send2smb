package com.zlight.sendtosmb

/** Monotonic timing; batch averages include setup and the gaps between small files. */
internal class TransferSpeedMeter(private val clock: () -> Long = System::nanoTime) {
    private var started = clock()
    private var sampled = started
    private var sampledBytes = 0L
    private var bytes = 0L
    private var progressHighWater: Long? = null
    var current: Double = 0.0
        private set

    @Synchronized fun add(delta: Long) {
        bytes += delta.coerceAtLeast(0)
    }

    /** The first callback is the verified/restored offset, not bytes sent in this attempt. */
    @Synchronized fun recordProgress(done: Long): Long {
        val previous = progressHighWater
        val delta = if (previous == null) 0 else (done - previous).coerceAtLeast(0)
        progressHighWater = maxOf(previous ?: done, done)
        bytes += delta
        return delta
    }

    @Synchronized fun sample(): Double {
        val now = clock()
        val elapsed = now - sampled
        if (elapsed >= 500_000_000L) {
            current = (bytes - sampledBytes) * 1_000_000_000.0 / elapsed
            sampled = now
            sampledBytes = bytes
        }
        return current
    }

    @Synchronized fun average(): Double =
        bytes * 1_000_000_000.0 / (clock() - started).coerceAtLeast(1)
}
