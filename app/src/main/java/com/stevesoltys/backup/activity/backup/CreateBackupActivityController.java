package com.stevesoltys.backup.activity.backup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.RemoteException;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.google.android.collect.Sets;
import com.stevesoltys.backup.R;
import com.stevesoltys.backup.session.BackupManagerController;
import com.stevesoltys.backup.session.backup.BackupSession;
import com.stevesoltys.backup.transport.ConfigurableBackupTransport;
import com.stevesoltys.backup.transport.ConfigurableBackupTransportService;
import com.stevesoltys.backup.transport.component.BackupComponent;
import com.stevesoltys.backup.transport.component.RestoreComponent;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupComponent;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfigurationBuilder;
import com.stevesoltys.backup.transport.component.provider.ContentProviderRestoreComponent;

import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Steve Soltys
 */
class CreateBackupActivityController {

    private static final String TAG = CreateBackupActivityController.class.getName();

    private static final Set<String> IGNORED_PACKAGES = Sets.newArraySet(
            "com.android.providers.downloads.ui", "com.android.providers.downloads", "com.android.providers.media",
            "com.stevesoltys.backup"
    );

    private final BackupManagerController backupManager;

    CreateBackupActivityController() {
        backupManager = new BackupManagerController();
    }

    void populatePackageList(ListView packageListView, CreateBackupActivity parent) {
        AtomicReference<PopupWindow> popupWindow = new AtomicReference<>();

        parent.runOnUiThread(() -> {
            popupWindow.set(showLoadingPopupWindow(parent));
            TextView textView = popupWindow.get().getContentView().findViewById(R.id.popup_text_view);
            textView.setText(R.string.loading_packages);

            View popupWindowButton = popupWindow.get().getContentView().findViewById(R.id.popup_cancel_button);
            popupWindowButton.setOnClickListener(view -> parent.finish());
        });

        List<String> eligiblePackageList = new LinkedList<>();

        try {
            eligiblePackageList.addAll(backupManager.getEligiblePackages());
            eligiblePackageList.removeAll(IGNORED_PACKAGES);

        } catch (RemoteException e) {
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

    void showEnterPasswordAlert(Set<String> selectedPackages, Uri contentUri, Activity parent) {
        final EditText passwordTextView = new EditText(parent);
        passwordTextView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new AlertDialog.Builder(parent)
                .setTitle("Enter a password")
                .setMessage("You'll need this to restore your backup, so write it down!")
                .setView(passwordTextView)

                .setPositiveButton("Set password", (dialog, button) ->
                        showConfirmPasswordAlert(selectedPackages, contentUri, parent,
                                passwordTextView.getText().toString()))

                .setNegativeButton("Cancel", (dialog, button) -> dialog.cancel())
                .show();
    }

    private void showConfirmPasswordAlert(Set<String> selectedPackages, Uri contentUri, Activity parent,
                                          String originalPassword) {
        final EditText passwordTextView = new EditText(parent);
        passwordTextView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        new AlertDialog.Builder(parent)
                .setTitle("Confirm password")
                .setView(passwordTextView)

                .setPositiveButton("Confirm", (dialog, button) -> {
                    String password = passwordTextView.getText().toString();

                    if (originalPassword.equals(password)) {
                        backupPackages(selectedPackages, contentUri, parent, password);

                    } else {
                        new AlertDialog.Builder(parent)
                                .setMessage("Passwords do not match, please try again.")
                                .setPositiveButton("Ok", (dialog2, button2) -> dialog2.dismiss())
                                .show();

                        dialog.cancel();
                    }
                })

                .setNegativeButton("Cancel", (dialog, button) -> dialog.cancel())
                .show();
    }

    private void backupPackages(Set<String> selectedPackages, Uri contentUri, Activity parent,
                                String selectedPassword) {
        try {
            selectedPackages.add("@pm@");

            ContentProviderBackupConfiguration backupConfiguration = new ContentProviderBackupConfigurationBuilder()
                    .setContext(parent)
                    .setOutputUri(contentUri)
                    .setPackages(selectedPackages)
                    .setPassword(selectedPassword)
                    .build();

            boolean success = initializeBackupTransport(backupConfiguration);

            if (!success) {
                Toast.makeText(parent, R.string.backup_in_progress, Toast.LENGTH_LONG).show();
                return;
            }

            PopupWindow popupWindow = showLoadingPopupWindow(parent);
            BackupObserver backupObserver = new BackupObserver(parent, popupWindow);
            BackupSession backupSession = backupManager.backup(backupObserver, selectedPackages);

            View popupWindowButton = popupWindow.getContentView().findViewById(R.id.popup_cancel_button);
            popupWindowButton.setOnClickListener(new BackupPopupWindowListener(backupSession));

            TextView textView = popupWindow.getContentView().findViewById(R.id.popup_text_view);
            textView.setText(R.string.initializing);

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
}
