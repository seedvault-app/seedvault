/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport.NO_MORE_DATA
import android.app.backup.BackupTransport.TRANSPORT_OK
import android.app.backup.RestoreDescription
import android.app.backup.RestoreDescription.TYPE_FULL_STREAM
import android.os.ParcelFileDescriptor
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.CipherFactoryImpl
import com.stevesoltys.seedvault.crypto.CryptoImpl
import com.stevesoltys.seedvault.crypto.KEY_SIZE_BYTES
import com.stevesoltys.seedvault.crypto.KeyManagerTestImpl
import com.stevesoltys.seedvault.header.HeaderReaderImpl
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.MetadataReaderImpl
import com.stevesoltys.seedvault.repo.Loader
import com.stevesoltys.seedvault.repo.SnapshotManager
import com.stevesoltys.seedvault.toByteArrayFromHex
import com.stevesoltys.seedvault.transport.backup.BackupTest
import com.stevesoltys.seedvault.transport.backup.TestKvDbManager
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

/**
 * Tests that we can still restore Version 1 backups with current code.
 */
internal class RestoreV1IntegrationTest : BackupTest() {

    private val outputFactory = mockk<OutputFactory>()
    private val secretKey = SecretKeySpec(
        "This is a legacy backup key 1234".toByteArray(), 0, KEY_SIZE_BYTES, "AES"
    )
    private val keyManager = KeyManagerTestImpl(secretKey)
    private val cipherFactory = CipherFactoryImpl(keyManager)
    private val headerReader = HeaderReaderImpl()
    private val cryptoImpl =
        CryptoImpl(context, keyManager, cipherFactory, headerReader, "androidId")
    private val dbManager = TestKvDbManager()
    private val metadataReader = MetadataReaderImpl(cryptoImpl)
    private val notificationManager = mockk<BackupNotificationManager>()
    private val backendManager: BackendManager = mockk()
    private val loader = mockk<Loader>()
    private val snapshotManager = mockk<SnapshotManager>()

    private val backend = mockk<Backend>()
    private val kvRestore = KVRestore(
        backendManager = backendManager,
        loader = loader,
        legacyPlugin = mockk(),
        outputFactory = outputFactory,
        headerReader = headerReader,
        crypto = cryptoImpl,
        dbManager = dbManager,
    )
    private val fullRestore =
        FullRestore(backendManager, loader, mockk(), outputFactory, headerReader, cryptoImpl)
    private val oldSalt = "31v9rtLNFE485vxQ6VtzSGo7N36ZBc97"
    private val restore = RestoreCoordinator(
        context = context,
        crypto = cryptoImpl,
        settingsManager = settingsManager,
        metadataManager = metadataManager,
        notificationManager = notificationManager,
        backendManager = backendManager,
        snapshotManager = snapshotManager,
        kv = kvRestore,
        full = fullRestore,
        metadataReader = metadataReader,
    ).apply {
        val backup = RestorableBackup(metadata.copy(version = 0x01, salt = oldSalt))
        beforeStartRestore(backup)
    }

    private val fileDescriptor = mockk<ParcelFileDescriptor>(relaxed = true)
    private val oldHandle = LegacyAppBackupFile.Blob(
        token = token,
        name = "i0juqM8CuZzN9EHKASsEEDlVTfE-SC-uRuyrQWDTzEs",
    )

    init {
        every { backendManager.backend } returns backend
    }

    @Test
    fun `test key-value backup and restore with 2 records`() = runBlocking {
        val encryptedDb = ("0128eb2eb87c33e9377de0d986afeaf72263f8a515fc5b626b49cd40100ea8dc" +
            "c5e421027c7280e9f3e0df5982736dc8f833256971826e93f438839840832e38" +
            "f942643730099e33191d3366031cb54144d33e3ab13cd7f1d3d62e6cbe421982" +
            "384f5570bae8d87e19c7d20f91a0642be531eaea46466c0ad1c9e382fc11839e" +
            "3d24521ba47e00d78db5699eb27f912bad9801db4f52ce8ddc9984e22f2aeb5a" +
            "07baae80951d6b4448a5821c2aa18ee89b7f99b9252d7f510b5031a3c1fc1dda" +
            "6e451eb81f52a0e58bbba14c495629ad4c92b0a2e2262e64d0df7e55207fd5b6" +
            "3041d35a67bf44dc006706bb1d261fb9a20461fd7517859e08fbecab75c81755" +
            "f986356f98ccdb8d2326aae9b5576657fb0513074b4a37b0555483ebf9ee04bf" +
            "7a53a35e3a8bf9de43dd19c146785b881fb3bd3dbc467625a0c47b96fb1f7ff7" +
            "9584268057ba3d01c3af58bdcc5f9fa1a0a0cc34c241b271b5de48efd70dbd27" +
            "faa8d04363a700ec3cd37adb95a1e361f965b7a757b51e242a4eb3bb9f3166b1" +
            "50dc5e55f9eb70460f4ed2d4fb5c8975d10b5157822f05805070547bbb49754b" +
            "89162b20a27a24dbfdb7b58c08bc9ee58c98324d0eccf49c857a0cd81f753eaa" +
            "072b9a798d55a38bde580d5dfd1b7554745b58f2f13187046e5ce8e36cd3be96" +
            "6ba231b95704d4b468803382f93267fc301c899fde1768e9ec8387bee7acd847" +
            "9a7c760133f3bb67064de6c0b35c9614e4297dbc6f08efecd33e75edf3a043da" +
            "7a4d0a8992a65d2641ba3e251de497e2b7da881cb84780d7db7210d2d52e13a0" +
            "28779a3b87609c7a479db5e52e3210e256b7b584fe735e2e779c22a0885e320b" +
            "47ec6c9d86b7d564783326f603beb5e098d57658a2faca57f92118a7f701777e" +
            "6598eeddb16afae60a9ff2241c622df391483d66100a4f16ee17289a869e3453" +
            "6ea1fff7a1210da46a3ee23576485c23eb0c97d84f1924ee0526382420f348d2" +
            "5701efa78b481fb43c6c4b25eb0675e10908240f191e5afb3027e8a29a160c7e" +
            "83a3cbe0b3083829ce67bc7c110e95dc3a17a86c4bfbcb3e1b9f76d4be944b6a" +
            "15e8ad63948e7e034f38bce2d15a1e699fe008bb3d35cb9743d3a795bf7b8ebe" +
            "ea97a3b9b546e501b1a2be502fce6067e116c627054187460ed2f3f6378fbe85" +
            "985809a12ce9ffd2a5033d41a666516510a4d40da09a56bc881fd07cf839e427" +
            "9bb4ebd96bd84cdd7ddc9cfe2e2b9d00d3c8001f40c1e771e0edd37362a4b21e" +
            "60c7d5799971c7a89e61d514ab2b481bd1123d9f5b173cc5ed98726ca3af598b" +
            "23e152e7e66352bc01917306fa1f786a598fa53e49ba38b5f506808d6f6ca3f1" +
            "8a754fcf7e1b804b8c1dce60e8bbc77ecea8f78ce66faa27d5a13eabcb898d7b" +
            "bc16c7629db5953b2884a12ce66198d93b0bbed07c8fdac0633096d29eb97f19" +
            "e817e5bca5651d4324e6c2b284468e286a2516cf792a196d19a2b6a09675d6b3" +
            "c9aa994bc237341e583bdc5b1ca5266567aa8af924f5571d9ec131be56912146" +
            "8e9954c8f747d971ea179963d4beb817c9796f85c79fbabea134e4b7df5e9a1a" +
            "eafa7d296d4e834177bb685d41afbd5d5618eeb079cba2f4cc1886d79c6faef6" +
            "4189817a429d05dcc243558a699f426283f09db2906d206c10d4ea3565dd42d1" +
            "f1a914c65646bd5a039e082281ae462c196e0aff9e7702de4128c367db5a4239" +
            "353fe6214124dff50e356ffc605e8e85ab35ac63217ea48e8ebd572a11882e19" +
            "206e474131dd546dfcfea726109f86eeb5edd0f90e20749ae2019f8868b1b2f9" +
            "242921dd5d63c5621ab91ef91dcffa048d1825593dab7c9f84c3d0590e81491e" +
            "623325883e637ee82401f402aa5ab251bf12393a9a0291ec4b5ffe299337938c" +
            "39335f7ac27da4e62365b1a8baf144fbfbd2a0f77c71aade8897b96557065f0b" +
            "e7401b02cca855f75948e907c6d91adaacdc9abbb3f58a0dc46d542514d58928" +
            "ad5bdb6c6bfb5ebadb4898841d816968e77fefcd85d657dbbd7808e9423758b8" +
            "d98f2874cd9feef2fcddc6ac8879115223c76d1b9b6974bb552caf2d9b165484" +
            "6ea36f92aed98b9a82700bf55dd6cd4f4b86527966c5f86fcff621706f77f0a7" +
            "c737ae1f0a8d854815dcc8aee9f9c15c4bc95d4e5be6fa11627ad1112efa9290" +
            "70f7aeaa7327091526165082a83af27ebb717b8dfc620a4c2a31a1ebf930320b" +
            "3792d10f1420580ec4eed48fcf60ca66f6d6669f931a5d6947ffe6e1b2af4f07" +
            "4265a78640ed30ad7f939891ae03877cb8cbffb33e41d92514ec003a9839ecbb" +
            "fbdd53ac012389d5ceab86414cd60b965a1e0ab520b5350b9375d0cf7226bd2d" +
            "5b141b1d71c0456aa090e34fd4d067").toByteArrayFromHex()

        // start restore
        assertEquals(TRANSPORT_OK, restore.startRestore(token, arrayOf(packageInfo)))

        // get restore description
        val restoreDescription = restore.nextRestorePackage() ?: fail()
        assertEquals(packageInfo.packageName, restoreDescription.packageName)
        assertEquals(RestoreDescription.TYPE_KEY_VALUE, restoreDescription.dataType)

        // restore finds the backed up key and writes the decrypted value
        val backupDataOutput = mockk<BackupDataOutput>()
        coEvery { backend.load(oldHandle) } returns ByteArrayInputStream(encryptedDb)
        every { outputFactory.getBackupDataOutput(fileDescriptor) } returns backupDataOutput
        every { backupDataOutput.writeEntityHeader(key, appData.size) } returns 1137
        every { backupDataOutput.writeEntityData(appData, appData.size) } returns appData.size
        every { backupDataOutput.writeEntityHeader(key2, appData2.size) } returns 1137
        every { backupDataOutput.writeEntityData(appData2, appData2.size) } returns appData2.size
        assertEquals(TRANSPORT_OK, restore.getRestoreData(fileDescriptor))

        verifyOrder {
            backupDataOutput.writeEntityData(appData, appData.size)
            backupDataOutput.writeEntityData(appData2, appData2.size)
        }
    }

    @Test
    fun `test full backup and restore with two chunks`() = runBlocking {
        val encryptedData = ("0128b5b75f24f2d57f8ab858b5eefe1836ed185999fa26adc1e8b5f8db238dbb" +
            "b6b3aa4a2951c69cfa9bae6b58e4e379e727edf7c3128d3ad969ab75c2e4acef" +
            "23beb22f29d1e064dab3d2d16430e0edf2d3ad1279befe4e75ff50209fc44e04" +
            "b2bd1e14420bcfe9b1407724af98cf39eb4eee257147e346935f762d1f718cc9" +
            "06ad2d86d413b061e0df719d62e6e95433ce0dd75e152a04f68afc0c479df785" +
            "6591922ecd7c907d9b33db0352624a67c3d0c63e0fe2b7f57287d45013eb59ef" +
            "bbb7bccc55577e5c7477b27f282eb73cb367fd5a9f3ded050e0d5da7648bf299" +
            "31d396a6b08ad5b55cc9dea3d570726e17261501cda0f69b81fa7e4bc87de1f3" +
            "4a353fa57e3ef1c24e7a0109bc3824f409df199cbb1f5bceccac488ba5e10857" +
            "9e540d5fb98c482996b3a5db0381a19e295ad36277e37f4a69882313c22d5c4d" +
            "6cbecc63645b039e07f591745345253536022d8c79de54e90478635dcb5182bb" +
            "bf7bf17621c3aa979dcbb7231cf30a4fe942faf8816ba5701b132fb72477d5b6" +
            "55a03c07d1978e3892da09c1e28d5a06a360cecb319450a5d4170b08d45504a8" +
            "2726fc500315384d310eae2f17f5701b2a8929d66a20d0807ab67f61d76087d0" +
            "2c5f7e06b8ce413df029eb376fcc72714d5c0588a1a91c3fba8e6b2464fe97c5" +
            "de74d56b2bd884eb04d0a3897e8546be9caff567b9d7bcf6837f40240e0de3c5" +
            "9a2ecdda6a881d1116a619221e33fc7b485880cd188bbff40f3a81687aa8fbd4" +
            "657e0cd578768394533dd2ec9f010b3d74cf52734a55acb2e28d041d1aed60c7" +
            "dc3e336299c9ac1f3dfcae10d9fd9ba3bf27ac17268b2c28a15893c3618a1e62" +
            "dbbe4bb1ab2c6240ab16cd24b07e0cb0b33b99afc9278e46aa0a9f987ce9f67d" +
            "92477ca931708001e43700f8d3391985fc62d9e0e744c58c2f4831be1e2fac56" +
            "3848b67b427f8b589e390a4d53317c888de4e85226e9c36b220baf37793234a4" +
            "35643cf95b3f9f08869afa0ddbc5fc7d5b63d657fe8acddde5fdbcb7bb9a900d" +
            "486574ddaa446483f815685a83fe438ccecec67854a19194113a3ce27a7cffa5" +
            "8831191ac1ff80a9203b9c2490631aa1e98ae30833be1099b79f332a03c3a47f" +
            "b845674b987b3874ec3ff3ecdc98c545a700ac928d409f62a96991a43f3b02ae" +
            "41e9062a7893417634e613d0af027c1fdc88085c5f00e93660b05012963a481d" +
            "9ccc58df8a340789162092e16306a657a50d5ebe3037f32d9cc6af7580c35c3c" +
            "1a7d9c44de9f78fa00f0cc725a5fff120f15b910bd26fec40f7dd046b0312ea3" +
            "d174b8622f3940053993d32778804af8520c57c57d38da0a6b00aac8b860f7cc" +
            "7e1ed0a5e5fdd7683fc56ee170e493f520bee887665068a285e45dbc803fa560" +
            "00d1a58f373240582cbb96a7cb97a99663c55c2f4f040e799fef945d62f30cc8" +
            "86ac67f350c013089eec5c619e9056704138139322de7d82cf5053c803254e36" +
            "5e382f7a9ff0be9f52c554eab9f515069a6d71d6e96e9e55721a97fdfb0ed0b4" +
            "5cb9af1625cd6aa29b2e0cace36990c9973dec7c6b940e4905674ff4e70170fe" +
            "2ed56659c9b3923bac130c9b10ac95b4c6986f5bc68b7e5439d9436d3c95aa80" +
            "0397f419b215dd8c591de5e4f4237b440719cb8803c1b9f4f064a5d88e88145a" +
            "f592aec86080e60699dfa6284ca5aee9b799d14422997a499a4472f5ef1ee4f6" +
            "8d41a00262d212bacc79cf9c82dfa24c4da3a1eade3be3ca3cdcf2cc00b8a2cb" +
            "5c53aad407329275a9fc9bfbff9ceb24a1cfc2cedb330682ba8b5b6f03956959" +
            "a102411fee64cdc2b2dca3bb8093815e754b2da823d9a08d15c83da7bfcfa378" +
            "9ce4b22d54e290de3c22f3294323cadd736f79786e5752dd269cdd97827471dd" +
            "af6c468d3d835443107b1f0edf775c4ad38d2a8a49d048fddd383c5ef7371049" +
            "5496c78a72e04976eb702eac6065b2e912fd").toByteArrayFromHex()

        // set backup type to full
        val packageMetadata = metadata.packageMetadataMap[packageName] ?: fail()
        metadata.packageMetadataMap[packageName] = packageMetadata.copy(
            backupType = BackupType.FULL,
        )

        // start restore
        assertEquals(TRANSPORT_OK, restore.startRestore(token, arrayOf(packageInfo)))

        val restoreDescription = restore.nextRestorePackage() ?: fail()
        assertEquals(packageInfo.packageName, restoreDescription.packageName)
        assertEquals(TYPE_FULL_STREAM, restoreDescription.dataType)

        // reverse the backup streams into restore input
        val inputStream = ByteArrayInputStream(encryptedData)
        val outputStream = ByteArrayOutputStream()
        every { outputFactory.getOutputStream(fileDescriptor) } returns outputStream
        coEvery { backend.load(oldHandle) } returns inputStream

        // restore data
        assertEquals(appData2.size, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertEquals(NO_MORE_DATA, restore.getNextFullRestoreDataChunk(fileDescriptor))
        restore.finishRestore()

        // assert that restored data matches original app data
        assertArrayEquals(appData2, outputStream.toByteArray())
    }

}
