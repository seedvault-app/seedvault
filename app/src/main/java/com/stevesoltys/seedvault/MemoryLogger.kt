/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import android.util.Log

object MemoryLogger {

    fun log() {
        Log.d("MemoryLogger", getMemStr())
    }

    fun getMemStr(): String {
        val r = Runtime.getRuntime()
        val total = r.totalMemory() / 1024 / 1024
        val free = r.freeMemory() / 1024 / 1024
        val max = r.maxMemory() / 1024 / 1024
        val used = total - free
        return "$free MiB free - $used of $total (max $max)"
    }
}
