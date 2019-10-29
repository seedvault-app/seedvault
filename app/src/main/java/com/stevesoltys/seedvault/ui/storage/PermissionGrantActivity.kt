package com.stevesoltys.seedvault.ui.storage

import android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class PermissionGrantActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.data != null) {
            intent.addFlags(FLAG_GRANT_PREFIX_URI_PERMISSION)
            setResult(RESULT_OK, intent)
        }
        finish()
    }

}
