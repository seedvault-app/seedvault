package org.calyxos.backup.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts

class ContactUtils(private val resolver: ContentResolver) {

    data class Contact(
        val name: String?,
        val phone: String?,
        val email: String?
    )

    fun addContact(contact: Contact) {
        val ops = ArrayList<ContentProviderOperation>().apply {
            add(
                ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_TYPE, null)
                    .withValue(RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            add(
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, contact.name)
                    .build()
            )
            add(
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                    .withValue(Phone.NUMBER, contact.phone)
                    .withValue(Phone.TYPE, Phone.TYPE_HOME)
                    .build()
            )
            add(
                ContentProviderOperation.newInsert(Data.CONTENT_URI)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE)
                    .withValue(Email.ADDRESS, contact.email)
                    .withValue(Email.TYPE, Email.TYPE_WORK)
                    .build()
            )
        }
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    fun getContacts(): List<Contact> {
        val lookupKeys = ArrayList<String>()
        resolver.query(Contacts.CONTENT_URI, arrayOf(Contacts.LOOKUP_KEY), null, null, null)
            .use { cursor ->
                while (cursor!!.moveToNext()) {
                    lookupKeys.add(cursor.getString(0))
                }
            }
        val contacts = ArrayList<Contact>()
        for (key in lookupKeys) {
            val name = getDetail(key, StructuredName.DISPLAY_NAME, StructuredName.CONTENT_ITEM_TYPE)
            val phone = getDetail(key, Phone.NUMBER, Phone.CONTENT_ITEM_TYPE)
            val email = getDetail(key, Email.ADDRESS, Email.CONTENT_ITEM_TYPE)
            contacts.add(Contact(name, phone, email))
        }
        return contacts
    }

    private fun getDetail(lookupKey: String, detail: String, mimeType: String): String? {
        val projection = arrayOf(detail)
        val selection = "${Contacts.LOOKUP_KEY}=? AND ${Data.MIMETYPE}=?"
        val args = arrayOf(lookupKey, mimeType)
        resolver.query(Data.CONTENT_URI, projection, selection, args, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    return cursor.getString(0)
                }
            }
        return null
    }

    fun deleteAllContacts() {
        val ops = ArrayList<ContentProviderOperation>()
        resolver.query(
            Contacts.CONTENT_URI,
            arrayOf(Contacts.LOOKUP_KEY),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor!!.moveToNext()) {
                val uri: Uri =
                    Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, cursor.getString(0))
                ops.add(ContentProviderOperation.newDelete(uri).build())
            }
        }
        resolver.applyBatch(ContactsContract.AUTHORITY, ops)
    }

    fun getNumberOfContacts(): Int {
        return resolver.query(
            Contacts.CONTENT_URI,
            arrayOf(Contacts.LOOKUP_KEY),
            null,
            null,
            null
        )!!.count
    }

}
