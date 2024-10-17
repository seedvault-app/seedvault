/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package de.grobox.storagebackuptester

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener
import androidx.core.view.WindowCompat.setDecorFitsSystemWindows
import androidx.core.view.WindowInsetsCompat.CONSUMED
import androidx.core.view.WindowInsetsCompat.Type.displayCutout
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import de.grobox.storagebackuptester.crypto.KeyManager

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setDecorFitsSystemWindows(window, false)
        setOnApplyWindowInsetsListener(window.decorView.rootView) { v, windowInsets ->
            val insets = windowInsets.getInsets(systemBars() or displayCutout())
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            CONSUMED
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, LogFragment.newInstance())
                .commitNow()
        }

        KeyManager.storeMasterKey()

        if (!KeyManager.hasMainKey()) {
            Log.e("TEST", "storing new key")
            KeyManager.storeMasterKey()
        } else {
            Log.e("TEST", "already have key")
        }

    }

}
