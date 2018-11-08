package com.stevesoltys.backup.activity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.widget.Toast;

import com.stevesoltys.backup.activity.backup.CreateBackupActivity;
import com.stevesoltys.backup.activity.restore.RestoreBackupActivity;

import static android.content.Intent.ACTION_CREATE_DOCUMENT;
import static android.content.Intent.ACTION_OPEN_DOCUMENT;
import static android.content.Intent.CATEGORY_OPENABLE;

/**
 * @author Steve Soltys
 */
class MainActivityController {

    private static final String DOCUMENT_MIME_TYPE = "application/octet-stream";

    void showCreateDocumentActivity(Activity parent) {
        Intent createDocumentIntent = new Intent(ACTION_CREATE_DOCUMENT);
        createDocumentIntent.addCategory(CATEGORY_OPENABLE);
        createDocumentIntent.setType(DOCUMENT_MIME_TYPE);

        try {
            Intent documentChooser = Intent.createChooser(createDocumentIntent, "Select the backup location");
            parent.startActivityForResult(documentChooser, MainActivity.CREATE_DOCUMENT_REQUEST_CODE);

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

    void handleCreateDocumentResult(Intent result, Activity parent) {

        if (result == null) {
            return;
        }

        Intent intent = new Intent(parent, CreateBackupActivity.class);
        intent.setData(result.getData());
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
