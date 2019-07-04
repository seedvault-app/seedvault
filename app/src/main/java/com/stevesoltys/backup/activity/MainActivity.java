package com.stevesoltys.backup.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.stevesoltys.backup.R;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.stevesoltys.backup.settings.SettingsManagerKt.areBackupsScheduled;

public class MainActivity extends Activity implements View.OnClickListener {

    public static final int OPEN_DOCUMENT_TREE_REQUEST_CODE = 1;

    public static final int OPEN_DOCUMENT_TREE_BACKUP_REQUEST_CODE = 2;

    public static final int LOAD_DOCUMENT_REQUEST_CODE = 3;

    private MainActivityController controller;
    private Button automaticBackupsButton;
    private Button changeLocationButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        controller = new MainActivityController();

        findViewById(R.id.create_backup_button).setOnClickListener(this);
        findViewById(R.id.restore_backup_button).setOnClickListener(this);

        automaticBackupsButton = findViewById(R.id.automatic_backups_button);
        automaticBackupsButton.setOnClickListener(this);
        if (areBackupsScheduled(this)) automaticBackupsButton.setVisibility(GONE);

        changeLocationButton = findViewById(R.id.change_backup_location_button);
        changeLocationButton.setOnClickListener(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (controller.isChangeBackupLocationButtonVisible(this)) {
            changeLocationButton.setVisibility(VISIBLE);
        } else {
            changeLocationButton.setVisibility(GONE);
        }
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

            case R.id.automatic_backups_button:
                if (controller.onAutomaticBackupsButtonClicked(this)) {
                    automaticBackupsButton.setVisibility(GONE);
                }
                break;

            case R.id.change_backup_location_button:
                controller.onChangeBackupLocationButtonClicked(this);
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
                controller.handleChooseFolderResult(result, this, false);
                break;

            case OPEN_DOCUMENT_TREE_BACKUP_REQUEST_CODE:
                controller.handleChooseFolderResult(result, this, true);
                break;

            case LOAD_DOCUMENT_REQUEST_CODE:
                controller.handleLoadDocumentResult(result, this);
                break;
        }
    }
}
