package org.calyxos.backup.storage.db

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.calyxos.backup.storage.toStoredUri
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class UriStoreTest {

    private lateinit var uriStore: UriStore
    private lateinit var db: Db

    private val uri1 = Uri.parse("content://foo/bar/1").toStoredUri()
    private val uri2 = Uri.parse("content://foo/bar/2").toStoredUri()
    private val uri3 = Uri.parse("content://foo/bar/3").toStoredUri()

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, Db::class.java).build()
        uriStore = db.getUriStore()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testInsertAndGet() {
        uriStore.addStoredUri(uri1)
        assertThat(uriStore.getStoredUris(), equalTo(listOf(uri1)))
        uriStore.addStoredUri(uri2)
        uriStore.addStoredUri(uri3)
        assertThat(uriStore.getStoredUris(), equalTo(listOf(uri1, uri2, uri3)))
    }

    @Test
    fun testInsertAndRemove() {
        uriStore.addStoredUri(uri1)
        assertThat(uriStore.getStoredUris(), equalTo(listOf(uri1)))
        uriStore.removeStoredUri(uri1)
        assertThat(uriStore.getStoredUris(), equalTo(emptyList()))
        uriStore.addStoredUri(uri2)
        uriStore.addStoredUri(uri3)
        assertThat(uriStore.getStoredUris(), equalTo(listOf(uri2, uri3)))
        uriStore.removeStoredUri(uri3)
        assertThat(uriStore.getStoredUris(), equalTo(listOf(uri2)))
    }

}
