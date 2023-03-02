/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import com.stevesoltys.seedvault.getRandomString
import com.stevesoltys.seedvault.toByteArrayFromHex
import com.stevesoltys.seedvault.toHexString
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random

class TestKvDbManager : KvDbManager {

    private var db: TestKVDb? = null
    private val outputStream = ByteArrayOutputStream()

    override fun getDb(packageName: String, isRestore: Boolean): KVDb {
        if (isRestore) {
            readDbFromStream(ByteArrayInputStream(outputStream.toByteArray()))
            return this.db!!
        }
        return TestKVDb().apply { db = this }
    }

    override fun getDbInputStream(packageName: String): InputStream {
        return ByteArrayInputStream(db!!.serialize().toByteArray())
    }

    override fun getDbOutputStream(packageName: String): OutputStream {
        outputStream.reset()
        return outputStream
    }

    override fun existsDb(packageName: String): Boolean {
        return db != null
    }

    override fun getDbSize(packageName: String): Long? {
        return db?.serialize()?.toByteArray()?.size?.toLong()
    }

    override fun deleteDb(packageName: String, isRestore: Boolean): Boolean {
        clearDb()
        return true
    }

    fun clearDb() {
        this.db = null
    }

    fun readDbFromStream(inputStream: InputStream) {
        this.db = TestKVDb.deserialize(String(inputStream.readBytes()))
    }
}

class TestKVDb(private val json: JSONObject = JSONObject()) : KVDb {

    override fun put(key: String, value: ByteArray) {
        json.put(key, value.toHexString(spacer = ""))
    }

    override fun get(key: String): ByteArray? {
        return json.getByteArray(key)
    }

    override fun getAll(): List<Pair<String, ByteArray>> {
        val list = ArrayList<Pair<String, ByteArray>>(json.length())
        json.keys().forEach { key ->
            val bytes = json.getByteArray(key)
            if (bytes != null) list.add(Pair(key, bytes))
        }
        return list
    }

    override fun delete(key: String) {
        json.remove(key)
    }

    override fun vacuum() {
    }

    override fun close() {
    }

    fun serialize(): String {
        return json.toString()
    }

    companion object {
        fun deserialize(str: String): TestKVDb {
            return TestKVDb(JSONObject(str))
        }
    }

    private fun JSONObject.getByteArray(key: String): ByteArray? {
        val str = optString(key, "")
        if (str.isNullOrEmpty()) return null
        return str.toByteArrayFromHex()
    }

}

class TestKvDbManagerTest {

    private val dbManager = TestKvDbManager()

    private val key1 = getRandomString(12)
    private val key2 = getRandomString(12)
    private val bytes1 = Random.nextBytes(23)
    private val bytes2 = Random.nextBytes(23)

    @Test
    fun test() {
        assertFalse(dbManager.existsDb("foo"))

        val db = dbManager.getDb("foo")
        db.put(key1, bytes1)
        db.put(key2, bytes2)
        assertTrue(dbManager.existsDb("foo"))

        assertArrayEquals(bytes1, db.get(key1))
        assertArrayEquals(bytes2, db.get(key2))

        val list = db.getAll()
        assertEquals(2, list.size)
        assertEquals(key1, list[0].first)
        assertArrayEquals(bytes1, list[0].second)
        assertEquals(key2, list[1].first)
        assertArrayEquals(bytes2, list[1].second)

        val dbBytes = dbManager.getDbInputStream("foo").readBytes()

        assertTrue(dbManager.existsDb("foo"))
        dbManager.clearDb()
        assertFalse(dbManager.existsDb("foo"))

        dbManager.readDbFromStream(ByteArrayInputStream(dbBytes))
        assertTrue(dbManager.existsDb("foo"))
        assertArrayEquals(bytes1, db.get(key1))
        assertArrayEquals(bytes2, db.get(key2))
        assertNull(db.get("bar"))

        db.delete(key2)
        assertNull(db.get(key2))
    }

}
