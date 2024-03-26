package com.stevesoltys.seedvault.ui

import android.view.MenuItem
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.stevesoltys.seedvault.R

abstract class BackupActivity : AppCompatActivity() {

    @CallSuper
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    protected fun showFragment(f: Fragment, addToBackStack: Boolean = false, tag: String? = null) {
        supportFragmentManager.beginTransaction().apply {
            if (tag == null) replace(R.id.fragment, f)
            else replace(R.id.fragment, f, tag)
            if (addToBackStack) addToBackStack(null)
            commit()
        }
    }

}
