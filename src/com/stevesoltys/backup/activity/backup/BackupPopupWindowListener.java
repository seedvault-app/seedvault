package com.stevesoltys.backup.activity.backup;

import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.stevesoltys.backup.R;
import com.stevesoltys.backup.session.backup.BackupResult;
import com.stevesoltys.backup.session.backup.BackupSession;

/**
 * @author Steve Soltys
 */
class BackupPopupWindowListener implements Button.OnClickListener {

    private static final String TAG = BackupPopupWindowListener.class.getName();

    private final BackupSession backupSession;

    public BackupPopupWindowListener(BackupSession backupSession) {
        this.backupSession = backupSession;
    }

    @Override
    public void onClick(View view) {
        int viewId = view.getId();

        switch (viewId) {

            case R.id.popup_cancel_button:
                try {
                    backupSession.stop(BackupResult.CANCELLED);

                } catch (RemoteException e) {
                    Log.e(TAG, "Error cancelling backup session: ", e);
                }
                break;
        }
    }
}
