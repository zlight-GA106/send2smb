package com.zlight.sendtosmb

/** Monotonic timing; batch averages include setup and the gaps between small files. */
internal class TransferSpeedMeter(private val clock: () -> Long = System::nanoTime) {
    private var started = clock()
    private var sampled = started
    private var sampledBytes = 0L
    private var bytes = 0L
    var current: Double = 0.0
        private set

    @Synchronized fun add(delta: Long) {
        bytes += delta.coerceAtLeast(0)
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
