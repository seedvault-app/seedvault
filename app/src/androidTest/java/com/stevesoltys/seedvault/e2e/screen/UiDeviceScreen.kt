/*
 * SPDX-FileCopyrightText: 2023 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.e2e.screen

import android.widget.ScrollView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import java.lang.Thread.sleep

abstract class UiDeviceScreen<T> {

    companion object {
        private const val SELECTOR_TIMEOUT = 180000L
    }

    operator fun invoke(function: T.() -> Unit) {
        function.invoke(this as T)
    }

    fun UiObject.scrollTo(
        scrollSelector: UiSelector = UiSelector().className(ScrollView::class.java),
    ): UiObject {
        val uiScrollable = UiScrollable(scrollSelector)
        uiScrollable.waitForExists(SELECTOR_TIMEOUT)
        uiScrollable.scrollIntoView(this)
        waitForExists(SELECTOR_TIMEOUT)

        sleep(2000)
        return this
    }

    fun findObject(
        block: UiSelector.() -> UiSelector,
    ): UiObject = device().findObject(
        UiSelector().let { it.block() }
    )

    private fun device() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        .also {
            Configurator.getInstance().waitForSelectorTimeout = SELECTOR_TIMEOUT
        }
}
