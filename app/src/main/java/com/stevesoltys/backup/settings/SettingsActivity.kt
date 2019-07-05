package com.stevesoltys.backup.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Intent.*
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import android.widget.Toast.LENGTH_SHORT
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProviders
import com.stevesoltys.backup.R

private val TAG = SettingsActivity::class.java.name

const val REQUEST_CODE_OPEN_DOCUMENT_TREE = 1
const val REQUEST_CODE_RECOVERY_CODE = 2


class SettingsActivity : AppCompatActivity() {

    private lateinit var viewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings)

        viewModel = ViewModelProviders.of(this).get(SettingsViewModel::class.java)

        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        if (resultCode != RESULT_OK) {
            Log.w(TAG, "Error in activity result: $requestCode")
            finishAfterTransition()
        }

        if (requestCode == REQUEST_CODE_OPEN_DOCUMENT_TREE) {
            viewModel.handleChooseFolderResult(result)
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return

        // check that backup is provisioned
        if (!viewModel.recoveryCodeIsSet()) {
            showRecoveryCodeActivity()
        } else if (!viewModel.locationIsSet()) {
            showChooseFolderActivity()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.settings_menu, menu)
        if (resources.getBoolean(R.bool.show_restore_in_settings)) {
            menu.findItem(R.id.action_restore).isVisible = true
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when {
            item.itemId == android.R.id.home -> {
                onBackPressed()
                true
            }
            item.itemId == R.id.action_backup -> {
                Toast.makeText(this, "Not yet implemented", LENGTH_SHORT).show()
                true
            }
            item.itemId == R.id.action_restore -> {
                Toast.makeText(this, "Not yet implemented", LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showRecoveryCodeActivity() {
        val intent = Intent(this, RecoveryCodeActivity::class.java)
        startActivityForResult(intent, REQUEST_CODE_RECOVERY_CODE)
    }

    private fun showChooseFolderActivity() {
        val openTreeIntent = Intent(ACTION_OPEN_DOCUMENT_TREE)
        openTreeIntent.addFlags(FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        // TODO StringRes
        try {
            val documentChooser = createChooser(openTreeIntent, "Select the backup location")
            startActivityForResult(documentChooser, REQUEST_CODE_OPEN_DOCUMENT_TREE)
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(this, "Please install a file manager.", LENGTH_LONG).show()
        }
    }

}
