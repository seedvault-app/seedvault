package com.stevesoltys.backup.activity.restore;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.stevesoltys.backup.R;
import com.stevesoltys.backup.session.BackupManagerController;
import com.stevesoltys.backup.session.restore.RestoreSession;
import com.stevesoltys.backup.transport.ConfigurableBackupTransport;
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService;
import com.stevesoltys.backup.transport.component.BackupComponent;
import com.stevesoltys.backup.transport.component.RestoreComponent;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupComponent;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfigurationBuilder;
import com.stevesoltys.backup.transport.component.provider.ContentProviderRestoreComponent;
import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
        AtomicReference<PopupWindow> popupWindow = new AtomicReference<>();

        parent.runOnUiThread(() -> {
            popupWindow.set(showLoadingPopupWindow(parent));
            TextView textView = popupWindow.get().getContentView().findViewById(R.id.popup_text_view);
            textView.setText(R.string.loading_backup);

            View popupWindowButton = popupWindow.get().getContentView().findViewById(R.id.popup_cancel_button);
            popupWindowButton.setOnClickListener(view -> parent.finish());
        });

        List<String> eligiblePackageList = new LinkedList<>();

        try {
            eligiblePackageList.addAll(getEligiblePackages(contentUri, parent));

        } catch (IOException e) {
            Log.e(TAG, "Error while obtaining package list: ", e);
        }

        parent.runOnUiThread(() -> {
            if (popupWindow.get() != null) {
                popupWindow.get().dismiss();
            }

            packageListView.setOnItemClickListener(parent);
            packageListView.setAdapter(new ArrayAdapter<>(parent, R.layout.checked_list_item, eligiblePackageList));
            packageListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        });
    }

    private PopupWindow showLoadingPopupWindow(Activity parent) {
        LayoutInflater inflater = (LayoutInflater) parent.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        ViewGroup popupViewGroup = parent.findViewById(R.id.popup_layout);
        View popupView = inflater.inflate(R.layout.progress_popup_window, popupViewGroup);

        PopupWindow popupWindow = new PopupWindow(popupView, 750, 350, true);
        popupWindow.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        popupWindow.setElevation(10);
        popupWindow.setFocusable(false);
        popupWindow.showAtLocation(popupView, Gravity.CENTER, 0, 0);
        popupWindow.setOutsideTouchable(false);
        return popupWindow;
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

    void showEnterPasswordAlert(Set<String> selectedPackages, Uri contentUri, Activity parent) {
        final EditText passwordTextView = new EditText(parent);
        passwordTextView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new AlertDialog.Builder(parent)
                .setTitle("Enter a password")
                .setMessage("If you didn't enter one while creating the backup, you can leave this blank.")
                .setView(passwordTextView)

                .setPositiveButton("Confirm", (dialog, button) ->
                        restorePackages(selectedPackages, contentUri, parent, passwordTextView.getText().toString()))
                .setNegativeButton("Cancel", (dialog, button) -> dialog.cancel())
                .show();
    }

    private void restorePackages(Set<String> selectedPackages, Uri contentUri, Activity parent, String password) {
        try {
            ContentProviderBackupConfiguration backupConfiguration = new ContentProviderBackupConfigurationBuilder().
                    setContext(parent)
                    .setOutputUri(contentUri)
                    .setPackages(selectedPackages)
                    .setPassword(password)
                    .build();

            boolean success = initializeBackupTransport(backupConfiguration);

            if (!success) {
                Toast.makeText(parent, R.string.restore_in_progress, Toast.LENGTH_LONG).show();
                return;
            }

            PopupWindow popupWindow = showLoadingPopupWindow(parent);
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

        if (backupTransport.isActive()) {
            return false;
        }

        BackupComponent backupComponent = new ContentProviderBackupComponent(configuration);
        RestoreComponent restoreComponent = new ContentProviderRestoreComponent(configuration);
        backupTransport.initialize(backupComponent, restoreComponent);
        return true;
    }
}
