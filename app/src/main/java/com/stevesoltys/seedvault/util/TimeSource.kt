package com.stevesoltys.seedvault.util

/**
 * This class only exists, so we can mock the time in tests.
 */
class TimeSource {
    /**
     * Returns the current time in milliseconds (Unix time).
     */
    fun time(): Long {
        return System.currentTimeMillis()
    }
}
