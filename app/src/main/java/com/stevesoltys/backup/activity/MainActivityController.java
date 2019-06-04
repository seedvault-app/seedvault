package com.stevesoltys.backup.activity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.stevesoltys.backup.activity.backup.CreateBackupActivity;
import com.stevesoltys.backup.activity.restore.RestoreBackupActivity;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import static android.content.Intent.ACTION_OPEN_DOCUMENT;
import static android.content.Intent.ACTION_OPEN_DOCUMENT_TREE;
import static android.content.Intent.CATEGORY_OPENABLE;
import static android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
import static android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
import static android.provider.DocumentsContract.buildDocumentUriUsingTree;
import static android.provider.DocumentsContract.createDocument;
import static android.provider.DocumentsContract.getTreeDocumentId;
import static com.stevesoltys.backup.activity.MainActivity.OPEN_DOCUMENT_TREE_REQUEST_CODE;

/**
 * @author Steve Soltys
 * @author Torsten Grote
 */
class MainActivityController {

    private static final String DOCUMENT_MIME_TYPE = "application/octet-stream";
    private static final String DOCUMENT_SUFFIX = "yyyy-MM-dd_HH_mm_ss";

    void showChooseFolderActivity(Activity parent) {
        Intent createDocumentIntent = new Intent(ACTION_OPEN_DOCUMENT_TREE);
        createDocumentIntent.addFlags(FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION);

        try {
            Intent documentChooser = Intent.createChooser(createDocumentIntent, "Select the backup location");
            parent.startActivityForResult(documentChooser, OPEN_DOCUMENT_TREE_REQUEST_CODE);

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

    void handleChooseFolderResult(Intent result, Activity parent) {

        if (result == null || result.getData() == null) {
            return;
        }

        Uri folderUri = result.getData();
        ContentResolver contentResolver = parent.getContentResolver();

        // persist permission to access backup folder across reboots
        int takeFlags = result.getFlags() &
                (FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION);
        contentResolver.takePersistableUriPermission(folderUri, takeFlags);

        // create backup file in folder
        Uri fileUri;
        try {
            fileUri = createBackupFile(contentResolver, folderUri);
        } catch (IOException e) {
            // TODO show better error message once more infrastructure is in place
            Toast.makeText(parent, "Error creating backup file", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
            return;
        }

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

    private Uri createBackupFile(ContentResolver contentResolver, Uri folderUri) throws IOException {
        Uri documentUri = buildDocumentUriUsingTree(folderUri, getTreeDocumentId(folderUri));
        Uri fileUri = createDocument(contentResolver, documentUri, DOCUMENT_MIME_TYPE, getBackupFileName());
        if (fileUri == null) throw new IOException();
        return fileUri;
    }

    private String getBackupFileName() {
        SimpleDateFormat dateFormat = new SimpleDateFormat(DOCUMENT_SUFFIX, Locale.US);
        String date = dateFormat.format(new Date());
        return "backup-" + date;
    }

}
