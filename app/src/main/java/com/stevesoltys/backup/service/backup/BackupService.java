package com.stevesoltys.backup.service.backup;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.stevesoltys.backup.R;
import com.stevesoltys.backup.activity.PopupWindowUtil;
import com.stevesoltys.backup.activity.backup.BackupPopupWindowListener;
import com.stevesoltys.backup.service.TransportService;
import com.stevesoltys.backup.session.backup.BackupSession;

import java.util.Set;

/**
 * @author Steve Soltys
 */
public class BackupService {

    private static final String TAG = BackupService.class.getName();

    private final TransportService transportService = new TransportService();

    public void backupPackageData(Set<String> selectedPackages, Activity parent) {
        try {
            selectedPackages.add("@pm@");

            Log.i(TAG, "Backing up " + selectedPackages.size() + " packages...");

            PopupWindow popupWindow = PopupWindowUtil.showLoadingPopupWindow(parent);
            BackupObserver backupObserver = new BackupObserver(parent, popupWindow);
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
