package com.stevesoltys.backup.activity;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.stevesoltys.backup.activity.backup.CreateBackupActivity;
import com.stevesoltys.backup.activity.restore.RestoreBackupActivity;
import com.stevesoltys.backup.service.backup.BackupJobService;
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService;

import static android.app.job.JobInfo.NETWORK_TYPE_UNMETERED;
import static android.content.Intent.ACTION_OPEN_DOCUMENT;
import static android.content.Intent.ACTION_OPEN_DOCUMENT_TREE;
import static android.content.Intent.CATEGORY_OPENABLE;
import static android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
import static android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
import static com.stevesoltys.backup.BackupKt.JOB_ID_BACKGROUND_BACKUP;
import static com.stevesoltys.backup.activity.MainActivity.OPEN_DOCUMENT_TREE_BACKUP_REQUEST_CODE;
import static com.stevesoltys.backup.activity.MainActivity.OPEN_DOCUMENT_TREE_REQUEST_CODE;
import static com.stevesoltys.backup.settings.SettingsManagerKt.getBackupFolderUri;
import static com.stevesoltys.backup.settings.SettingsManagerKt.getBackupPassword;
import static com.stevesoltys.backup.settings.SettingsManagerKt.setBackupFolderUri;
import static com.stevesoltys.backup.settings.SettingsManagerKt.setBackupsScheduled;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.DAYS;

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
public class MainActivityController {

    public static final String DOCUMENT_MIME_TYPE = "application/octet-stream";

    void onBackupButtonClicked(Activity parent) {
        Uri folderUri = getBackupFolderUri(parent);
        if (folderUri == null) {
            showChooseFolderActivity(parent, true);
        } else {
            // ensure that backup service is started
            parent.startService(new Intent(parent, ConfigurableBackupTransportService.class));
            showCreateBackupActivity(parent);
        }
    }

    boolean isChangeBackupLocationButtonVisible(Activity parent) {
        return getBackupFolderUri(parent) != null;
    }

    private void showChooseFolderActivity(Activity parent, boolean continueToBackup) {
        Intent openTreeIntent = new Intent(ACTION_OPEN_DOCUMENT_TREE);
        openTreeIntent.addFlags(FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION);

        try {
            Intent documentChooser = Intent.createChooser(openTreeIntent, "Select the backup location");
            int requestCode = continueToBackup ? OPEN_DOCUMENT_TREE_BACKUP_REQUEST_CODE : OPEN_DOCUMENT_TREE_REQUEST_CODE;
            parent.startActivityForResult(documentChooser, requestCode);

        } catch (ActivityNotFoundException ex) {
            Toast.makeText(parent, "Please install a file manager.", Toast.LENGTH_SHORT).show();
        }
    }

    void showLoadDocumentActivity(Activity parent) {
        Intent loadDocumentIntent = new Intent(ACTION_OPEN_DOCUMENT);
        loadDocumentIntent.addCategory(CATEGORY_OPENABLE);
        loadDocumentIntent.setType(DOCUMENT_MIME_TYPE);

        try {
            Intent documentChooser = Intent.createChooser(loadDocumentIntent, "Select the backup location");
            parent.startActivityForResult(documentChooser, MainActivity.LOAD_DOCUMENT_REQUEST_CODE);

        } catch (ActivityNotFoundException ex) {
            Toast.makeText(parent, "Please install a file manager.", Toast.LENGTH_SHORT).show();
        }
    }

    boolean onAutomaticBackupsButtonClicked(Activity parent) {
        if (getBackupFolderUri(parent) == null || getBackupPassword(parent) == null) {
            Toast.makeText(parent, "Please make at least one manual backup first.", Toast.LENGTH_SHORT).show();
            return false;
        }

        // schedule backups
        final ComponentName serviceName = new ComponentName(parent, BackupJobService.class);
        JobInfo job = new JobInfo.Builder(JOB_ID_BACKGROUND_BACKUP, serviceName)
                .setRequiredNetworkType(NETWORK_TYPE_UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)  // TODO warn the user instead
                .setPeriodic(DAYS.toMillis(1))
                .setRequiresCharging(true)
                .setPersisted(true)
                .build();
        JobScheduler scheduler = requireNonNull(parent.getSystemService(JobScheduler.class));
        scheduler.schedule(job);

        // remember that backups were scheduled
        setBackupsScheduled(parent);

        // show Toast informing the user
        Toast.makeText(parent, "Backups will run automatically now", Toast.LENGTH_SHORT).show();

        return true;
    }

    void onChangeBackupLocationButtonClicked(Activity parent) {
        showChooseFolderActivity(parent, false);
    }

    void handleChooseFolderResult(Intent result, Activity parent, boolean continueToBackup) {

        if (result == null || result.getData() == null) {
            return;
        }

        Uri folderUri = result.getData();

        // persist permission to access backup folder across reboots
        int takeFlags = result.getFlags() &
                (FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION);
        parent.getContentResolver().takePersistableUriPermission(folderUri, takeFlags);

        // store backup folder location in settings
        setBackupFolderUri(parent, folderUri);

        if (!continueToBackup) return;

        showCreateBackupActivity(parent);
    }

    private void showCreateBackupActivity(Activity parent) {
        Intent intent = new Intent(parent, CreateBackupActivity.class);
        parent.startActivity(intent);
    }

    void handleLoadDocumentResult(Intent result, Activity parent) {

        if (result == null) {
            return;
        }

        Intent intent = new Intent(parent, RestoreBackupActivity.class);
        intent.setData(result.getData());
        parent.startActivity(intent);
    }

}
