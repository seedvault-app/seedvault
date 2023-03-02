/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.plugins.saf

import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract.EXTRA_LOADING
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.stevesoltys.seedvault.assertReadEquals
import com.stevesoltys.seedvault.coAssertThrows
import com.stevesoltys.seedvault.getRandomBase64
import com.stevesoltys.seedvault.getRandomByteArray
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.writeAndClose
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@MediumTest
class DocumentsStorageTest : KoinComponent {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val settingsManager by inject<SettingsManager>()
    private val storage = DocumentsStorage(
        appContext = context,
        settingsManager = settingsManager,
        safStorage = settingsManager.getSafStorage() ?: error("No SAF storage"),
    )

    private val filename = getRandomBase64()
    private lateinit var file: DocumentFile

    @Before
    fun setup() = runBlocking {
        assertNotNull("Select a storage location in the app first!", storage.rootBackupDir)
        file = storage.rootBackupDir?.createOrGetFile(context, filename)
            ?: error("Could not create test file")
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun testWritingAndReadingFile() {
        // write to output stream
        val outputStream = storage.getOutputStream(file)
        val content = ByteArray(1337).apply { Random.nextBytes(this) }
        outputStream.write(content)
        outputStream.flush()
        outputStream.close()

        // read written data from input stream
        val inputStream = storage.getInputStream(file)
        val readContent = inputStream.readBytes()
        inputStream.close()
        assertArrayEquals(content, readContent)

        // write smaller content to same file
        val outputStream2 = storage.getOutputStream(file)
        val content2 = ByteArray(42).apply { Random.nextBytes(this) }
        outputStream2.write(content2)
        outputStream2.flush()
        outputStream2.close()

        // read written data from input stream
        val inputStream2 = storage.getInputStream(file)
        val readContent2 = inputStream2.readBytes()
        inputStream2.close()
        assertArrayEquals(content2, readContent2)
    }

    @Test
    fun testFindFile() = runBlocking(Dispatchers.IO) {
        val foundFile = storage.rootBackupDir!!.findFileBlocking(context, file.name!!)
        assertNotNull(foundFile)
        assertEquals(filename, foundFile!!.name)
        assertEquals(storage.rootBackupDir!!.uri, foundFile.parentFile?.uri)
    }

    @Test
    fun testCreateFile() {
        // create test file
        val dir = storage.rootBackupDir!!
        val createdFile = dir.createFile("text", getRandomBase64())
        assertNotNull(createdFile)
        assertNotNull(createdFile!!.name)

        // write some data into it
        val data = getRandomByteArray()
        context.contentResolver.openOutputStream(createdFile.uri)!!.writeAndClose(data)

        // data should still be there
        assertReadEquals(data, context.contentResolver.openInputStream(createdFile.uri))

        // delete again
        createdFile.delete()
        assertFalse(createdFile.exists())
    }

    @Test
    fun testCreateTwoFiles() = runBlocking {
        val mimeType = "application/octet-stream"
        val dir = storage.rootBackupDir!!

        // create test file
        val name1 = getRandomBase64(Random.nextInt(1, 10))
        val file1 = requireNotNull(dir.createFile(mimeType, name1))
        assertTrue(file1.exists())
        assertEquals(name1, file1.name)
        assertEquals(0L, file1.length())

        assertReadEquals(getRandomByteArray(0), context.contentResolver.openInputStream(file1.uri))

        // write some data into it
        val data1 = getRandomByteArray(5 * 1024 * 1024)
        context.contentResolver.openOutputStream(file1.uri)!!.writeAndClose(data1)
        assertEquals(data1.size.toLong(), file1.length())

        // data should still be there
        assertReadEquals(data1, context.contentResolver.openInputStream(file1.uri))

        // create test file
        val name2 = getRandomBase64(Random.nextInt(1, 10))
        val file2 = requireNotNull(dir.createFile(mimeType, name2))
        assertTrue(file2.exists())
        assertEquals(name2, file2.name)

        // write some data into it
        val data2 = getRandomByteArray(12 * 1024 * 1024)
        context.contentResolver.openOutputStream(file2.uri)!!.writeAndClose(data2)
        assertEquals(data2.size.toLong(), file2.length())

        // data should still be there
        assertReadEquals(data2, context.contentResolver.openInputStream(file2.uri))

        // delete files again
        file1.delete()
        file2.delete()
        assertFalse(file1.exists())
        assertFalse(file2.exists())
    }

    @Test
    fun testGetLoadedCursor() = runBlocking {
        // empty cursor extras are like not loading, returns same cursor right away
        val cursor1: Cursor = mockk()
        every { cursor1.extras } returns Bundle()
        assertEquals(cursor1, getLoadedCursor { cursor1 })

        // explicitly not loading, returns same cursor right away
        val cursor2: Cursor = mockk()
        every { cursor2.extras } returns Bundle().apply { putBoolean(EXTRA_LOADING, false) }
        assertEquals(cursor2, getLoadedCursor { cursor2 })

        // loading cursor registers content observer, times out and closes cursor
        val cursor3: Cursor = mockk()
        every { cursor3.extras } returns Bundle().apply { putBoolean(EXTRA_LOADING, true) }
        every { cursor3.registerContentObserver(any()) } just Runs
        every { cursor3.close() } just Runs
        coAssertThrows(TimeoutCancellationException::class.java) {
            getLoadedCursor(1000) { cursor3 }
        }
        verify { cursor3.registerContentObserver(any()) }
        verify { cursor3.close() } // ensure that cursor gets closed

        // loading cursor registers content observer, but re-query fails
        val cursor4: Cursor = mockk()
        val observer4 = slot<ContentObserver>()
        val query: () -> Cursor? = { if (observer4.isCaptured) null else cursor4 }
        every { cursor4.extras } returns Bundle().apply { putBoolean(EXTRA_LOADING, true) }
        every { cursor4.registerContentObserver(capture(observer4)) } answers {
            observer4.captured.onChange(false, Uri.parse("foo://bar"))
        }
        every { cursor4.close() } just Runs
        coAssertThrows(IOException::class.java) {
            getLoadedCursor(10_000, query)
        }
        assertTrue(observer4.isCaptured)
        verify { cursor4.close() } // ensure that cursor gets closed

        // loading cursor registers content observer, re-queries and returns new result
        val cursor5: Cursor = mockk()
        val result5: Cursor = mockk()
        val observer5 = slot<ContentObserver>()
        val query5: () -> Cursor? = { if (observer5.isCaptured) result5 else cursor5 }
        every { cursor5.extras } returns Bundle().apply { putBoolean(EXTRA_LOADING, true) }
        every { cursor5.registerContentObserver(capture(observer5)) } answers {
            observer5.captured.onChange(false, null)
        }
        every { cursor5.close() } just Runs
        assertEquals(result5, getLoadedCursor(10_000, query5))
        assertTrue(observer5.isCaptured)
        verify { cursor5.close() } // ensure that initial cursor got closed
    }

}
