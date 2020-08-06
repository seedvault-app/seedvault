package com.stevesoltys.seedvault

import androidx.documentfile.provider.DocumentFile
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.plugins.saf.DocumentsStorage
import com.stevesoltys.seedvault.plugins.saf.createOrGetFile
import com.stevesoltys.seedvault.settings.SettingsManager
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.KoinComponent
import org.koin.core.inject
import kotlin.random.Random

private const val filename = "test-file"

@RunWith(AndroidJUnit4::class)
class DocumentsStorageTest : KoinComponent {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val metadataManager by inject<MetadataManager>()
    private val settingsManager by inject<SettingsManager>()
    private val storage = DocumentsStorage(context, metadataManager, settingsManager)

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
