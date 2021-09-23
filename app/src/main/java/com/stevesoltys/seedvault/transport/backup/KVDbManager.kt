package com.stevesoltys.seedvault.transport.backup

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
import android.database.sqlite.SQLiteOpenHelper
import android.provider.BaseColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

interface KvDbManager {
    fun getDb(packageName: String, isRestore: Boolean = false): KVDb

    /**
     * Use only for backup.
     */
    fun getDbInputStream(packageName: String): InputStream

    /**
     * Use only for restore.
     */
    fun getDbOutputStream(packageName: String): OutputStream

    /**
     * Use only for backup.
     */
    fun existsDb(packageName: String): Boolean
    fun deleteDb(packageName: String, isRestore: Boolean = false): Boolean
}

class KvDbManagerImpl(private val context: Context) : KvDbManager {

    override fun getDb(packageName: String, isRestore: Boolean): KVDb {
        return KVDbImpl(context, getFileName(packageName, isRestore))
    }

    private fun getFileName(packageName: String, isRestore: Boolean): String {
        val prefix = if (isRestore) "restore_" else ""
        return "${prefix}kv_$packageName.db"
    }

    private fun getDbFile(packageName: String, isRestore: Boolean = false): File {
        return context.getDatabasePath(getFileName(packageName, isRestore))
    }

    override fun getDbInputStream(packageName: String): InputStream {
        return FileInputStream(getDbFile(packageName))
    }

    override fun getDbOutputStream(packageName: String): OutputStream {
        return FileOutputStream(getDbFile(packageName, true))
    }

    override fun existsDb(packageName: String): Boolean {
        return getDbFile(packageName).isFile
    }

    override fun deleteDb(packageName: String, isRestore: Boolean): Boolean {
        return getDbFile(packageName, isRestore).delete()
    }
}

interface KVDb : AutoCloseable {
    fun put(key: String, value: ByteArray)
    fun get(key: String): ByteArray?
    fun getAll(): List<Pair<String, ByteArray>>
    fun delete(key: String)
    fun vacuum()
}

class KVDbImpl(context: Context, fileName: String) :
    SQLiteOpenHelper(context, fileName, null, DATABASE_VERSION), KVDb {

    companion object {
        private const val DATABASE_VERSION = 1

        private object KVEntry : BaseColumns {
            const val TABLE_NAME = "kv_entry"
            const val COLUMN_NAME_KEY = "key"
            const val COLUMN_NAME_VALUE = "value"
        }

        private const val SQL_CREATE_ENTRIES =
            "CREATE TABLE IF NOT EXISTS ${KVEntry.TABLE_NAME} (" +
                "${KVEntry.COLUMN_NAME_KEY} TEXT PRIMARY KEY," +
                "${KVEntry.COLUMN_NAME_VALUE} BLOB)"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_ENTRIES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    override fun vacuum() = writableDatabase.execSQL("VACUUM")

    override fun put(key: String, value: ByteArray) {
        val values = ContentValues().apply {
            put(KVEntry.COLUMN_NAME_KEY, key)
            put(KVEntry.COLUMN_NAME_VALUE, value)
        }
        writableDatabase.insertWithOnConflict(KVEntry.TABLE_NAME, null, values, CONFLICT_REPLACE)
    }

    override fun get(key: String): ByteArray? = readableDatabase.query(
        KVEntry.TABLE_NAME,
        arrayOf(KVEntry.COLUMN_NAME_VALUE),
        "${KVEntry.COLUMN_NAME_KEY} = ?",
        arrayOf(key),
        null,
        null,
        null
    ).use { cursor ->
        if (!cursor.moveToNext()) null
        else cursor.getBlob(0)
    }

    override fun getAll(): List<Pair<String, ByteArray>> = readableDatabase.query(
        KVEntry.TABLE_NAME,
        arrayOf(KVEntry.COLUMN_NAME_KEY, KVEntry.COLUMN_NAME_VALUE),
        null,
        null,
        null,
        null,
        null
    ).use { cursor ->
        val list = ArrayList<Pair<String, ByteArray>>(cursor.count)
        while (cursor.moveToNext()) {
            list.add(Pair(cursor.getString(0), cursor.getBlob(1)))
        }
        list
    }

    override fun delete(key: String) {
        writableDatabase.delete(KVEntry.TABLE_NAME, "${KVEntry.COLUMN_NAME_KEY} = ?", arrayOf(key))
    }

}
