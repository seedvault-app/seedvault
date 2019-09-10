package com.stevesoltys.backup.service.backup;

import android.app.Activity;
import android.app.backup.BackupProgress;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.stevesoltys.backup.R;
import com.stevesoltys.backup.session.backup.BackupResult;
import com.stevesoltys.backup.session.backup.BackupSession;
import com.stevesoltys.backup.session.backup.BackupSessionObserver;

/**
 * @author Steve Soltys
 */
class BackupObserver implements BackupSessionObserver {

    private final Activity context;

    private final PopupWindow popupWindow;

    BackupObserver(Activity context, PopupWindow popupWindow) {
        this.context = context;
        this.popupWindow = popupWindow;
    }

    @Override
    public void backupPackageStarted(BackupSession backupSession, String packageName, BackupProgress backupProgress) {
        context.runOnUiThread(() -> {

            TextView textView = popupWindow.getContentView().findViewById(R.id.popup_text_view);

            if (textView != null) {
                textView.setText(packageName);
            }

            ProgressBar progressBar = popupWindow.getContentView().findViewById(R.id.popup_progress_bar);

            if (progressBar != null) {
                progressBar.setMax((int) backupProgress.bytesExpected);
                progressBar.setProgress((int) backupProgress.bytesTransferred);
            }
        });
    }

    @Override
    public void backupPackageCompleted(BackupSession backupSession, String packageName, BackupResult result) {
        context.runOnUiThread(() -> {

            TextView textView = popupWindow.getContentView().findViewById(R.id.popup_text_view);

            if (textView != null) {
                textView.setText(packageName);
            }
        });
    }

    @Override
    public void backupSessionCompleted(BackupSession backupSession, BackupResult backupResult) {
        context.runOnUiThread(() -> {
            if (backupResult == BackupResult.SUCCESS) {
                Toast.makeText(context, R.string.backup_success, Toast.LENGTH_LONG).show();

            } else if (backupResult == BackupResult.CANCELLED) {
                Toast.makeText(context, R.string.backup_cancelled, Toast.LENGTH_LONG).show();

            } else {
                Toast.makeText(context, R.string.backup_failure, Toast.LENGTH_LONG).show();
            }

            popupWindow.dismiss();
            context.finish();
        });
    }
}
