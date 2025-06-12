/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.api

public interface CheckObserver {

    public fun onStartChecking()
    public fun onCheckUpdate(speed: Long, thousandth: Int)
    public fun onCheckSuccess(size: Long, speed: Long)
    public fun onCheckFoundErrors(size: Long, speed: Long)
}
