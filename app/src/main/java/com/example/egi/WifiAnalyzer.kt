package com.example.egi

import android.net.wifi.ScanResult

object WifiAnalyzer {
    /**
     * Convert frequency to channel number for 2.4GHz.
     * Formula: (freq - 2407) / 5
     */
    fun getChannelUsage(scanResults: List<ScanResult>): Map<Int, Int> {
        return scanResults
            .map { (it.frequency - 2407) / 5 }
            .filter { it in 1..14 }
            .groupingBy { it }
            .eachCount()
    }

    /**
     * Determine the best channel among the non-overlapping ones (1, 6, 11).
     * Returns the one with the lowest count of networks.
     */
    fun getBestChannel(usage: Map<Int, Int>): Int {
        val nonOverlapping = listOf(1, 6, 11)
        return nonOverlapping.minByOrNull { usage.getOrDefault(it, 0) } ?: 1
    }
}
