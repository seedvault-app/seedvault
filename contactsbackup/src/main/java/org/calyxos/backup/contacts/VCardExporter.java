/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.contacts;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static android.provider.ContactsContract.Contacts.CONTENT_MULTI_VCARD_URI;
import static android.provider.ContactsContract.Data.CONTENT_URI;
import static android.provider.ContactsContract.Data.LOOKUP_KEY;
import static android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE;
import static org.calyxos.backup.contacts.ContactsBackupAgent.DEBUG;

class VCardExporter {

    private final static String TAG = "VCardExporter";

    private final ContentResolver contentResolver;

    VCardExporter(ContentResolver contentResolver) {
        this.contentResolver = contentResolver;
    }

    Optional<InputStream> getVCardInputStream() throws FileNotFoundException {
        String lookupKeysStr = String.join(":", getLookupKeys());
        if (DEBUG) {
            Log.e(TAG, "lookupKeysStr: " + lookupKeysStr);
        }
        if (lookupKeysStr.isEmpty()) {
            return Optional.empty();
        } else {
            Uri uri = Uri.withAppendedPath(CONTENT_MULTI_VCARD_URI, Uri.encode(lookupKeysStr));
            return Optional.ofNullable(contentResolver.openInputStream(uri));
        }
    }

    private Collection<String> getLookupKeys() {
        String[] projection = new String[]{LOOKUP_KEY};
        // We can not add IS_PRIMARY here as this gets lost on restored contacts
        String selection = ACCOUNT_TYPE + " is null OR " + ACCOUNT_TYPE + "='com.android.contacts'";
        Cursor cursor = contentResolver.query(CONTENT_URI, projection, selection, null, null);
        if (cursor == null) {
            Log.e(TAG, "Cursor for LOOKUP_KEY is null");
            return Collections.emptyList();
        }
        Set<String> lookupKeys = new HashSet<>();
        try {
            while (cursor.moveToNext()) {
                lookupKeys.add(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
        return lookupKeys;
    }

}
