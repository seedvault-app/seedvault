package com.stevesoltys.backup.ui

import android.view.MenuItem
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.stevesoltys.backup.R

abstract class BackupActivity : AppCompatActivity() {

    @CallSuper
    override fun onOptionsItemSelected(item: MenuItem): Boolean = when {
        item.itemId == android.R.id.home -> {
            onBackPressed()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    protected fun showFragment(f: Fragment, addToBackStack: Boolean = false) {
        val fragmentTransaction = supportFragmentManager.beginTransaction()
                .replace(R.id.fragment, f)
        if (addToBackStack) fragmentTransaction.addToBackStack(null)
        fragmentTransaction.commit()
    }

}
