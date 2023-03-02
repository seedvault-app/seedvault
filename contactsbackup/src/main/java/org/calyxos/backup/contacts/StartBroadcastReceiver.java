/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.contacts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import static android.content.Intent.ACTION_BOOT_COMPLETED;
import static android.content.Intent.ACTION_MY_PACKAGE_REPLACED;

/**
 * This receiver doesn't seem to do anything,
 * but we need it to prevent us from getting {@link ApplicationInfo#FLAG_STOPPED}
 * which would make us ineligible for backups.
 * <p>
 * It might be required that the app is already installed as a system app for this to work.
 */
public class StartBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (ACTION_BOOT_COMPLETED.equals(action) || ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.d("StartBroadcastReceiver", "Broadcast received: " + intent);
        } else {
            Log.w("StartBroadcastReceiver", "Unexpected broadcast received: " + intent);
        }
    }

}
