package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stevesoltys.seedvault.TestApp
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(
    sdk = [29], // robolectric does not support 30, yet
    application = TestApp::class
)
internal class DocumentFileTest {

    private val context: Context = mockk()
    private val parentUri: Uri = Uri.parse(
        "content://com.android.externalstorage.documents/tree/" +
            "primary%3A/document/primary%3A.SeedVaultAndroidBackup"
    )
    private val parentFile: DocumentFile = DocumentFile.fromTreeUri(context, parentUri)!!
    private val uri: Uri = Uri.parse(
        "content://com.android.externalstorage.documents/tree/" +
            "primary%3A/document/primary%3A.SeedVaultAndroidBackup%2Ftest"
    )

    @After
    fun afterEachTest() {
        stopKoin()
    }

    @Test
    fun `test ugly getTreeDocumentFile reflection hack`() {
        assertTrue(DocumentsContract.isTreeUri(uri))
        val file = getTreeDocumentFile(parentFile, context, uri)
        assertEquals(uri, file.uri)
        assertEquals(parentFile, file.parentFile)
    }

}
