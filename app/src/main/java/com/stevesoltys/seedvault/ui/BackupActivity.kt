/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.stevesoltys.seedvault.R

abstract class BackupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setupEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    protected fun showFragment(f: Fragment, addToBackStack: Boolean = false, tag: String? = null) {
        supportFragmentManager.beginTransaction().apply {
            replace(R.id.fragment, f, tag)
            if (addToBackStack) addToBackStack(null)
            commit()
        }
    }

}
