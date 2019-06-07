package com.stevesoltys.backup.activity;

import android.app.Activity;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.stevesoltys.backup.activity.backup.CreateBackupActivity;
import com.stevesoltys.backup.activity.restore.RestoreBackupActivity;
import com.stevesoltys.backup.service.backup.BackupJobService;
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static android.app.job.JobInfo.NETWORK_TYPE_UNMETERED;
import static android.content.Intent.ACTION_OPEN_DOCUMENT;
import static android.content.Intent.ACTION_OPEN_DOCUMENT_TREE;
import static android.content.Intent.CATEGORY_OPENABLE;
import static android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
import static android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
import static android.provider.DocumentsContract.buildDocumentUriUsingTree;
import static android.provider.DocumentsContract.createDocument;
import static android.provider.DocumentsContract.getTreeDocumentId;
import static com.stevesoltys.backup.Backup.JOB_ID_BACKGROUND_BACKUP;
import static com.stevesoltys.backup.activity.MainActivity.OPEN_DOCUMENT_TREE_BACKUP_REQUEST_CODE;
import static com.stevesoltys.backup.activity.MainActivity.OPEN_DOCUMENT_TREE_REQUEST_CODE;
import static com.stevesoltys.backup.settings.SettingsManager.getBackupFolderUri;
import static com.stevesoltys.backup.settings.SettingsManager.getBackupPassword;
import static com.stevesoltys.backup.settings.SettingsManager.setBackupFolderUri;
import static com.stevesoltys.backup.settings.SettingsManager.setBackupsScheduled;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.DAYS;

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
public class MainActivityController {

    private static final String TAG = MainActivityController.class.getName();

    private static final String DOCUMENT_MIME_TYPE = "application/octet-stream";
    private static final String DOCUMENT_SUFFIX = "yyyy-MM-dd_HH_mm_ss";

    void onBackupButtonClicked(Activity parent) {
        Uri folderUri = getBackupFolderUri(parent);
        if (folderUri == null) {
            showChooseFolderActivity(parent, true);
        } else {
            try {
                // ensure that backup service is started
                parent.startService(new Intent(parent, ConfigurableBackupTransportService.class));

                Uri fileUri = createBackupFile(parent.getContentResolver(), folderUri);
                showCreateBackupActivity(parent, fileUri);

            } catch (IOException e) {
                Log.w(TAG, "Error creating backup file: ", e);
                showChooseFolderActivity(parent, true);
            }
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
        setBackupsScheduled(parent);
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
        ContentResolver contentResolver = parent.getContentResolver();

        // persist permission to access backup folder across reboots
        int takeFlags = result.getFlags() &
                (FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION);
        contentResolver.takePersistableUriPermission(folderUri, takeFlags);

        // store backup folder location in settings
        setBackupFolderUri(parent, folderUri);

        if (!continueToBackup) return;

        try {
            // create a new backup file in folder
            Uri fileUri = createBackupFile(contentResolver, folderUri);

            showCreateBackupActivity(parent, fileUri);

        } catch (IOException e) {
            Log.e(TAG, "Error creating backup file: ", e);
            // TODO show better error message once more infrastructure is in place
            Toast.makeText(parent, "Error creating backup file", Toast.LENGTH_SHORT).show();
        }
    }

    private void showCreateBackupActivity(Activity parent, Uri fileUri) {
        Intent intent = new Intent(parent, CreateBackupActivity.class);
        intent.setData(fileUri);
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

    public static Uri createBackupFile(ContentResolver contentResolver, Uri folderUri) throws IOException {
        Uri documentUri = buildDocumentUriUsingTree(folderUri, getTreeDocumentId(folderUri));
        try {
            Uri fileUri = createDocument(contentResolver, documentUri, DOCUMENT_MIME_TYPE, getBackupFileName());
            if (fileUri == null) throw new IOException();
            return fileUri;

        } catch (SecurityException e) {
            // happens when folder was deleted and thus Uri permission don't exist anymore
            throw new IOException(e);
        }
    }

    private static String getBackupFileName() {
        SimpleDateFormat dateFormat = new SimpleDateFormat(DOCUMENT_SUFFIX, Locale.US);
        String date = dateFormat.format(new Date());
        return "backup-" + date;
    }

}
