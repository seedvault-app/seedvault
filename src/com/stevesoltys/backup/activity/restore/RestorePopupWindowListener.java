package com.stevesoltys.backup.activity.restore;

import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import com.stevesoltys.backup.R;
import com.stevesoltys.backup.session.restore.RestoreResult;
import com.stevesoltys.backup.session.restore.RestoreSession;

/**
 * @author Steve Soltys
 */
class RestorePopupWindowListener implements Button.OnClickListener {

    private static final String TAG = RestorePopupWindowListener.class.getName();

    private final RestoreSession restoreSession;

    RestorePopupWindowListener(RestoreSession restoreSession) {
        this.restoreSession = restoreSession;
    }

    @Override
    public void onClick(View view) {
        int viewId = view.getId();

        switch (viewId) {

            case R.id.popup_cancel_button:
                try {
                    restoreSession.stop(RestoreResult.CANCELLED);

                } catch (RemoteException e) {
                    Log.e(TAG, "Error cancelling restore session: ", e);
                }
                break;
        }
    }
}
