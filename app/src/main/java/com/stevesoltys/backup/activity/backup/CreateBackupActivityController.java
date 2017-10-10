package com.stevesoltys.backup.activity.backup;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.Toast;
import com.stevesoltys.backup.R;
import com.stevesoltys.backup.session.BackupManagerController;
import com.stevesoltys.backup.session.backup.BackupSession;
import com.stevesoltys.backup.transport.ConfigurableBackupTransport;
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService;
import com.stevesoltys.backup.transport.component.BackupComponent;
import com.stevesoltys.backup.transport.component.RestoreComponent;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfigurationBuilder;
import com.stevesoltys.backup.transport.component.provider.backup.ContentProviderBackupComponent;
import com.stevesoltys.backup.transport.component.provider.restore.ContentProviderRestoreComponent;

import java.util.LinkedList;
import java.util.List;

/**
 * @author Steve Soltys
 */
class CreateBackupActivityController {

    private static final String TAG = CreateBackupActivityController.class.getName();

    private final BackupManagerController backupManager;

    CreateBackupActivityController() {
        backupManager = new BackupManagerController();
    }

    void populatePackageList(ListView packageListView, CreateBackupActivity parent) {
        List<String> eligiblePackageList = new LinkedList<>();
        try {
            eligiblePackageList.addAll(backupManager.getEligiblePackages());

        } catch (RemoteException e) {
            Log.e(TAG, "Error while obtaining package list: ", e);
        }

        packageListView.setOnItemClickListener(parent);
        packageListView.setAdapter(new ArrayAdapter<>(parent, R.layout.checked_list_item, eligiblePackageList));
        packageListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
    }

    void backupPackages(List<String> selectedPackages, Uri contentUri, Activity parent) {
        try {
            String[] selectedPackageArray = selectedPackages.toArray(new String[selectedPackages.size() + 1]);
            selectedPackageArray[selectedPackageArray.length - 1] = "@pm@";

            ContentProviderBackupConfiguration backupConfiguration = new ContentProviderBackupConfigurationBuilder()
                    .setContext(parent).setOutputUri(contentUri).setPackages(selectedPackageArray).build();
            boolean success = initializeBackupTransport(backupConfiguration);

            if (!success) {
                Toast.makeText(parent, R.string.backup_in_progress, Toast.LENGTH_LONG).show();
                return;
            }

            PopupWindow popupWindow = buildPopupWindow(parent);
            BackupObserver backupObserver = new BackupObserver(parent, popupWindow);
            BackupSession backupSession = backupManager.backup(backupObserver, selectedPackageArray);

            View popupWindowButton = popupWindow.getContentView().findViewById(R.id.popup_cancel_button);

            if (popupWindowButton != null) {
                popupWindowButton.setOnClickListener(new BackupPopupWindowListener(backupSession));
            }

        } catch (Exception e) {
            Log.e(TAG, "Error while running backup: ", e);
        }
    }

    private boolean initializeBackupTransport(ContentProviderBackupConfiguration configuration) {
        ConfigurableBackupTransport backupTransport = ConfigurableBackupTransportService.getBackupTransport();

        if (backupTransport.isActive()) {
            return false;
        }

        BackupComponent backupComponent = new ContentProviderBackupComponent(configuration);
        RestoreComponent restoreComponent = new ContentProviderRestoreComponent(configuration);
        backupTransport.initialize(backupComponent, restoreComponent);
        return true;
    }

    private PopupWindow buildPopupWindow(Activity parent) {
        LayoutInflater inflater = (LayoutInflater) parent.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        ViewGroup popupViewGroup = parent.findViewById(R.id.popup_layout);
        View popupView = inflater.inflate(R.layout.progress_popup_window, popupViewGroup);

        PopupWindow popupWindow = new PopupWindow(popupView, 750, 350, true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        popupWindow.setElevation(10);
        popupWindow.setFocusable(false);
        popupWindow.showAtLocation(popupView, Gravity.CENTER, 0, 0);
        return popupWindow;
    }
}
