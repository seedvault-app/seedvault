/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

/**
 * This class only exists, so we can mock the time in tests.
 */
class Clock {
    /**
     * Returns the current time in milliseconds (Unix time).
     */
    fun time(): Long {
        return System.currentTimeMillis()
    }
}
