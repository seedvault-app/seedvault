package com.stevesoltys.backup.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import com.stevesoltys.backup.R;

public class MainActivity extends Activity implements View.OnClickListener {

    public static final int OPEN_DOCUMENT_TREE_REQUEST_CODE = 1;

    public static final int LOAD_DOCUMENT_REQUEST_CODE = 2;

    private MainActivityController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.create_backup_button).setOnClickListener(this);
        findViewById(R.id.restore_backup_button).setOnClickListener(this);

        controller = new MainActivityController();
    }

    @Override
    public void onClick(View view) {
        int viewId = view.getId();

        switch (viewId) {

            case R.id.create_backup_button:
                controller.onBackupButtonClicked(this);
                break;

            case R.id.restore_backup_button:
                controller.showLoadDocumentActivity(this);
                break;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent result) {

        if (resultCode != Activity.RESULT_OK) {
            Log.e(MainActivity.class.getName(), "Error in activity result: " + requestCode);
            return;
        }

        switch (requestCode) {

            case OPEN_DOCUMENT_TREE_REQUEST_CODE:
                controller.handleChooseFolderResult(result, this);
                break;

            case LOAD_DOCUMENT_REQUEST_CODE:
                controller.handleLoadDocumentResult(result, this);
                break;
        }
    }
}
