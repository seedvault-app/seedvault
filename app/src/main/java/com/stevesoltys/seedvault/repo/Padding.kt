/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow

object Padding {

    /**
     * Pads the given [size] using the [Padmé algorithm](https://lbarman.ch/blog/padme/).
     *
     * @param size unpadded object length
     * @return the padded object length
     */
    fun getPadTo(size: Int): Int {
        val e = floor(log2(size.toFloat()))
        val s = floor(log2(e)) + 1
        val lastBits = e - s
        val bitMask = (2.toFloat().pow(lastBits) - 1).toInt()
        return (size + bitMask) and bitMask.inv()
    }

}
