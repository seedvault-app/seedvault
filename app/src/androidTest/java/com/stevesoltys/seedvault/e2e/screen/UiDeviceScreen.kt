package com.stevesoltys.seedvault.e2e.screen

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiSelector

abstract class UiDeviceScreen<T> {

    operator fun invoke(function: T.() -> Unit) {
        function.invoke(this as T)
    }

    protected fun findObject(
        block: UiSelector.() -> UiSelector,
    ): UiObject = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        .findObject(
            UiSelector().let { it.block() }
        )
}
