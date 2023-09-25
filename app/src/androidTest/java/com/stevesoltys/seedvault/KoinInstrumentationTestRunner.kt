package com.stevesoltys.seedvault

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

class KoinInstrumentationTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        return super.newApplication(
            classLoader,
            KoinInstrumentationTestApp::class.java.name,
            context
        )
    }
}
