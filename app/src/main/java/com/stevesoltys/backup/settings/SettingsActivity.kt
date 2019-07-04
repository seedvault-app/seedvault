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
import com.stevesoltys.backup.activity.MainActivity.OPEN_DOCUMENT_TREE_REQUEST_CODE

private val TAG = SettingsActivity::class.java.name

class SettingsActivity : AppCompatActivity() {

    private lateinit var viewModel: SettingsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings)

        viewModel = ViewModelProviders.of(this).get(SettingsViewModel::class.java)

        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
    }

    override fun onStart() {
        super.onStart()
        if (!viewModel.locationIsSet()) {
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        if (resultCode != RESULT_OK) {
            Log.w(TAG, "Error in activity result: $requestCode")
            return
        }

        if (requestCode == OPEN_DOCUMENT_TREE_REQUEST_CODE) {
            viewModel.handleChooseFolderResult(result)
        }
    }

    private fun showChooseFolderActivity() {
        val openTreeIntent = Intent(ACTION_OPEN_DOCUMENT_TREE)
        openTreeIntent.addFlags(FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)
        // TODO StringRes
        try {
            val documentChooser = createChooser(openTreeIntent, "Select the backup location")
            startActivityForResult(documentChooser, OPEN_DOCUMENT_TREE_REQUEST_CODE)
        } catch (ex: ActivityNotFoundException) {
            Toast.makeText(this, "Please install a file manager.", LENGTH_LONG).show()
        }
    }

}
