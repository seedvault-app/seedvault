package com.stevesoltys.backup.activity.restore;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
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
import com.stevesoltys.backup.session.restore.RestoreSession;
import com.stevesoltys.backup.transport.ConfigurableBackupTransport;
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService;
import com.stevesoltys.backup.transport.component.BackupComponent;
import com.stevesoltys.backup.transport.component.RestoreComponent;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfigurationBuilder;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupComponent;
import com.stevesoltys.backup.transport.component.provider.ContentProviderRestoreComponent;
import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author Steve Soltys
 */
class RestoreBackupActivityController {

    private static final String TAG = RestoreBackupActivityController.class.getName();

    private final BackupManagerController backupManager;

    RestoreBackupActivityController() {
        backupManager = new BackupManagerController();
    }

    void populatePackageList(ListView packageListView, Uri contentUri, RestoreBackupActivity parent) {
        List<String> eligiblePackageList = new LinkedList<>();
        try {
            eligiblePackageList.addAll(getEligiblePackages(contentUri, parent));

        } catch (IOException e) {
            Log.e(TAG, "Error while obtaining package list: ", e);
        }

        packageListView.setOnItemClickListener(parent);
        packageListView.setAdapter(new ArrayAdapter<>(parent, R.layout.checked_list_item, eligiblePackageList));
        packageListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
    }

    private List<String> getEligiblePackages(Uri contentUri, Activity context) throws IOException {
        List<String> results = new LinkedList<>();

        ParcelFileDescriptor fileDescriptor = context.getContentResolver().openFileDescriptor(contentUri, "r");
        FileInputStream fileInputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        ZipInputStream inputStream = new ZipInputStream(fileInputStream);

        ZipEntry zipEntry;
        while ((zipEntry = inputStream.getNextEntry()) != null) {
            String zipEntryPath = zipEntry.getName();

            if (zipEntryPath.startsWith(ContentProviderBackupConfigurationBuilder.DEFAULT_FULL_BACKUP_DIRECTORY)) {
                String fileName = new File(zipEntryPath).getName();
                results.add(fileName);
            }

            inputStream.closeEntry();
        }

        IoUtils.closeQuietly(inputStream);
        IoUtils.closeQuietly(fileDescriptor.getFileDescriptor());
        return results;
    }

    void restorePackages(Set<String> selectedPackages, Uri contentUri, Activity parent) {
        try {
            ContentProviderBackupConfiguration backupConfiguration = new ContentProviderBackupConfigurationBuilder().
                    setContext(parent).setOutputUri(contentUri).setPackages(selectedPackages).build();
            boolean success = initializeBackupTransport(backupConfiguration);

            if(!success) {
                Toast.makeText(parent, R.string.restore_in_progress, Toast.LENGTH_LONG).show();
                return;
            }

            PopupWindow popupWindow = buildPopupWindow(parent);
            RestoreObserver restoreObserver = new RestoreObserver(parent, popupWindow, selectedPackages.size());
            RestoreSession restoreSession = backupManager.restore(restoreObserver, selectedPackages);

            View popupWindowButton = popupWindow.getContentView().findViewById(R.id.popup_cancel_button);
            if (popupWindowButton != null) {
                popupWindowButton.setOnClickListener(new RestorePopupWindowListener(restoreSession));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error while running restore: ", e);
        }
    }

    private boolean initializeBackupTransport(ContentProviderBackupConfiguration configuration) {
        ConfigurableBackupTransport backupTransport = ConfigurableBackupTransportService.getBackupTransport();

        if(backupTransport.isActive()) {
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
