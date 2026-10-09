package com.zlight.sendtosmb

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferSpeedMeterTest {
    @Test fun batchAverageIncludesSmallFileSetupAndGaps() {
        var now = 0L
        val meter = TransferSpeedMeter { now }
        now += 1_000_000_000L // setup
        meter.add(1024)
        now += 1_000_000_000L // file switch
        meter.add(1024)
        assertEquals(1024.0, meter.average(), 0.001)
    }

    @Test fun currentSpeedFallsToZeroWhenNoBytesArrive() {
        var now = 0L
        val meter = TransferSpeedMeter { now }
        meter.add(2048)
        now += 500_000_000L
        assertEquals(4096.0, meter.sample(), 0.001)
        now += 500_000_000L
        assertEquals(0.0, meter.sample(), 0.001)
        assertEquals(2048.0, meter.average(), 0.001)
    }

    @Test fun subSecondAndUnknownSizeTransfersUseActualByteCounts() {
        var now = 0L
        val meter = TransferSpeedMeter { now }
        meter.add(1000)
        meter.add(-10)
        now = 100_000_000L
        assertEquals(10000.0, meter.average(), 0.001)
        assertEquals(0.0, meter.sample(), 0.001)
    }
}
