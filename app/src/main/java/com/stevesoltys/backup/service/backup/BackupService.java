package com.stevesoltys.backup.service.backup;

import android.app.Activity;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import com.stevesoltys.backup.R;
import com.stevesoltys.backup.activity.PopupWindowUtil;
import com.stevesoltys.backup.activity.backup.BackupPopupWindowListener;
import com.stevesoltys.backup.service.TransportService;
import com.stevesoltys.backup.session.backup.BackupSession;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfigurationBuilder;

import java.net.URI;
import java.util.Set;

/**
 * @author Steve Soltys
 */
public class BackupService {

    private static final String TAG = BackupService.class.getName();

    private final TransportService transportService = new TransportService();

    public void backupPackageData(Set<String> selectedPackages, Uri contentUri, Activity parent,
                                  String selectedPassword) {
        try {
            selectedPackages.add("@pm@");

            ContentProviderBackupConfiguration backupConfiguration = new ContentProviderBackupConfigurationBuilder()
                    .setContext(parent)
                    .setOutputUri(contentUri)
                    .setPackages(selectedPackages)
                    .setPassword(selectedPassword)
                    .build();

            boolean success = transportService.initializeBackupTransport(backupConfiguration);

            if (!success) {
                Toast.makeText(parent, R.string.backup_in_progress, Toast.LENGTH_LONG).show();
                return;
            }

            PopupWindow popupWindow = PopupWindowUtil.showLoadingPopupWindow(parent);
            BackupObserver backupObserver = new BackupObserver(parent, popupWindow, new URI(contentUri.getPath()));
            BackupSession backupSession = transportService.backup(backupObserver, selectedPackages);

            View popupWindowButton = popupWindow.getContentView().findViewById(R.id.popup_cancel_button);
            popupWindowButton.setOnClickListener(new BackupPopupWindowListener(backupSession));

            TextView textView = popupWindow.getContentView().findViewById(R.id.popup_text_view);
            textView.setText(R.string.initializing);

        } catch (Exception e) {
            Log.e(TAG, "Error while running backup: ", e);
        }
    }
}
