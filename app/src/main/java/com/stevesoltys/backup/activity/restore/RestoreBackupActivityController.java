package com.stevesoltys.backup.activity.restore;

import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.stevesoltys.backup.R;
import com.stevesoltys.backup.activity.PopupWindowUtil;
import com.stevesoltys.backup.service.restore.RestoreService;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import libcore.io.IoUtils;

import static com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConstants.DEFAULT_FULL_BACKUP_DIRECTORY;

/**
 * @author Steve Soltys
 */
class RestoreBackupActivityController {

    private static final String TAG = RestoreBackupActivityController.class.getName();

    private final RestoreService restoreService = new RestoreService();

    void populatePackageList(ListView packageListView, Uri contentUri, RestoreBackupActivity parent) {
        AtomicReference<PopupWindow> popupWindow = new AtomicReference<>();

        parent.runOnUiThread(() -> {
            popupWindow.set(PopupWindowUtil.showLoadingPopupWindow(parent));
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
            parent.preSelectAllPackages();
        });
    }

    private List<String> getEligiblePackages(Uri contentUri, Activity context) throws IOException {
        List<String> results = new LinkedList<>();

        ParcelFileDescriptor fileDescriptor = context.getContentResolver().openFileDescriptor(contentUri, "r");
        FileInputStream fileInputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        ZipInputStream inputStream = new ZipInputStream(fileInputStream);

        ZipEntry zipEntry;
        while ((zipEntry = inputStream.getNextEntry()) != null) {
            String zipEntryPath = zipEntry.getName();

            if (zipEntryPath.startsWith(DEFAULT_FULL_BACKUP_DIRECTORY)) {
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
                        restoreService.restorePackages(selectedPackages, contentUri, parent,
                                passwordTextView.getText().toString()))

                .setNegativeButton("Cancel", (dialog, button) -> dialog.cancel())
                .show();
    }
}
