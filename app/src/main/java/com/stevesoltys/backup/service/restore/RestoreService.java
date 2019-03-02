package com.stevesoltys.backup.service.restore;

import android.app.Activity;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.PopupWindow;
import android.widget.Toast;
import com.stevesoltys.backup.R;
import com.stevesoltys.backup.activity.PopupWindowUtil;
import com.stevesoltys.backup.activity.restore.RestorePopupWindowListener;
import com.stevesoltys.backup.service.TransportService;
import com.stevesoltys.backup.session.restore.RestoreSession;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfiguration;
import com.stevesoltys.backup.transport.component.provider.ContentProviderBackupConfigurationBuilder;

import java.util.Set;

/**
 * @author Steve Soltys
 */
public class RestoreService {

    private static final String TAG = RestoreService.class.getName();

    private final TransportService transportService = new TransportService();

    public void restorePackages(Set<String> selectedPackages, Uri contentUri, Activity parent, String password) {
        try {
            ContentProviderBackupConfiguration backupConfiguration = new ContentProviderBackupConfigurationBuilder().
                    setContext(parent)
                    .setOutputUri(contentUri)
                    .setPackages(selectedPackages)
                    .setPassword(password)
                    .build();

            boolean success = transportService.initializeBackupTransport(backupConfiguration);

            if (!success) {
                Toast.makeText(parent, R.string.restore_in_progress, Toast.LENGTH_LONG).show();
                return;
            }

            PopupWindow popupWindow = PopupWindowUtil.showLoadingPopupWindow(parent);
            RestoreObserver restoreObserver = new RestoreObserver(parent, popupWindow, selectedPackages.size());
            RestoreSession restoreSession = transportService.restore(restoreObserver, selectedPackages);

            View popupWindowButton = popupWindow.getContentView().findViewById(R.id.popup_cancel_button);

            if (popupWindowButton != null) {
                popupWindowButton.setOnClickListener(new RestorePopupWindowListener(restoreSession));
            }

        } catch (RemoteException e) {
            Log.e(TAG, "Error while running restore: ", e);
        }
    }
}
