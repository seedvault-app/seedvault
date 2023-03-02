/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.db

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
internal class FilesCacheTest {

    private lateinit var filesCache: FilesCache
    private lateinit var db: Db

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, Db::class.java).build()
        filesCache = db.getFilesCache()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testWriteAndRead() {
        val uri1 = Uri.parse("content://foo/bar")
        val file1 = CachedFile(
            uri = uri1,
            size = Random.nextLong(),
            lastModified = Random.nextLong(),
            chunks = listOf("foo", "bar"),
            lastSeen = Random.nextLong()
        )
        val uri2 = Uri.parse("content://media/external")
        val file2 = CachedFile(
            uri = uri2,
            size = Random.nextLong(),
            lastModified = Random.nextLong(),
            chunks = listOf("23", "42"),
            lastSeen = Random.nextLong()
        )
        filesCache.insert(file1)
        filesCache.insert(file2)
        assertThat(filesCache.getByUri(uri1), equalTo(file1))
        assertThat(filesCache.getByUri(uri2), equalTo(file2))

        assertThat(filesCache.getByUri(Uri.parse("doesntExist")), equalTo(null))
    }

}
