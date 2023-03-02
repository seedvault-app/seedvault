/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui

class MutableLiveEvent<T> : LiveEvent<T>() {

    fun postEvent(value: T) {
        super.postValue(ConsumableEvent(value))
    }

    fun setEvent(value: T) {
        super.setValue(ConsumableEvent(value))
    }

}
