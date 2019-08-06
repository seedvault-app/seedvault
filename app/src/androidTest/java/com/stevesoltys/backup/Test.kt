package com.stevesoltys.backup

import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.stevesoltys.backup.settings.getBackupFolderUri
import com.stevesoltys.backup.transport.backup.plugins.DocumentsStorage
import com.stevesoltys.backup.transport.backup.plugins.createOrGetFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

private const val filename = "test-file"

@RunWith(AndroidJUnit4::class)
class AndroidUnitTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folderUri = getBackupFolderUri(context)
    private val deviceName = "device name"
    private val storage = DocumentsStorage(context, folderUri, deviceName)

    private lateinit var file: DocumentFile

    @Before
    fun setup() {
        assertNotNull("Select a storage location in the app first!", storage.rootBackupDir)
        file = storage.rootBackupDir?.createOrGetFile(filename)
                ?: throw RuntimeException("Could not create test file")
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

}
