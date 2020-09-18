package org.calyxos.backup.contacts;

import android.content.ContentResolver;
import android.util.Log;

import com.android.vcard.VCardEntry;
import com.android.vcard.VCardEntryCommitter;
import com.android.vcard.VCardEntryConstructor;
import com.android.vcard.VCardEntryHandler;
import com.android.vcard.VCardParser;
import com.android.vcard.VCardParser_V21;
import com.android.vcard.exception.VCardException;
import com.android.vcard.exception.VCardVersionException;

import java.io.IOException;
import java.io.InputStream;

import static com.android.vcard.VCardConfig.VCARD_TYPE_V21_GENERIC;
import static org.calyxos.backup.contacts.ContactsBackupAgent.DEBUG;

class VCardImporter implements VCardEntryHandler {

    private final static String TAG = "VCardImporter";
    private final static int TYPE = VCARD_TYPE_V21_GENERIC;

    private final ContentResolver contentResolver;

    VCardImporter(ContentResolver contentResolver) {
        this.contentResolver = contentResolver;
    }

    void importFromStream(InputStream is) throws IOException {
        final VCardEntryConstructor constructor = new VCardEntryConstructor(TYPE, null);
        final VCardEntryCommitter committer = new VCardEntryCommitter(contentResolver);
        constructor.addEntryHandler(committer);
        if (DEBUG) {
            constructor.addEntryHandler(this);
        }
        try {
            constructor.clear();
            VCardParser mVCardParser = new VCardParser_V21(TYPE);
            mVCardParser.addInterpreter(constructor);
            mVCardParser.parse(is);
        } catch (VCardVersionException e) {
            Log.e(TAG, "Appropriate version for this vCard is not found.", e);
            throw new IOException(e);
        } catch (VCardException e) {
            Log.e(TAG, "Error parsing vCard.", e);
            throw new IOException(e);
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            throw e;
        }
    }

    @Override
    public void onStart() {
        Log.e(TAG, "onStart");
    }

    @Override
    public void onEntryCreated(VCardEntry vCardEntry) {
        Log.e(TAG, "onEntryCreated " + vCardEntry);
    }

    @Override
    public void onEnd() {
        Log.e(TAG, "onEnd");
    }

}
