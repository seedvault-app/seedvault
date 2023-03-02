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
import com.stevesoltys.seedvault.crypto.CipherFactoryImpl
import com.stevesoltys.seedvault.crypto.CryptoImpl
import com.stevesoltys.seedvault.crypto.KEY_SIZE_BYTES
import com.stevesoltys.seedvault.crypto.KeyManagerTestImpl
import com.stevesoltys.seedvault.encodeBase64
import com.stevesoltys.seedvault.header.HeaderReaderImpl
import com.stevesoltys.seedvault.metadata.MetadataReaderImpl
import com.stevesoltys.seedvault.plugins.LegacyStoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import com.stevesoltys.seedvault.toByteArrayFromHex
import com.stevesoltys.seedvault.transport.TransportTest
import com.stevesoltys.seedvault.transport.backup.KvDbManager
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

/**
 * Tests that we can still restore Version 0 backups with current code.
 */
internal class RestoreV0IntegrationTest : TransportTest() {

    private val outputFactory = mockk<OutputFactory>()
    private val secretKey = SecretKeySpec(
        "This is a legacy backup key 1234".toByteArray(), 0, KEY_SIZE_BYTES, "AES"
    )
    private val keyManager = KeyManagerTestImpl(secretKey)
    private val cipherFactory = CipherFactoryImpl(keyManager)
    private val headerReader = HeaderReaderImpl()
    private val cryptoImpl = CryptoImpl(keyManager, cipherFactory, headerReader)
    private val dbManager = mockk<KvDbManager>()
    private val metadataReader = MetadataReaderImpl(cryptoImpl)
    private val notificationManager = mockk<BackupNotificationManager>()
    private val storagePluginManager: StoragePluginManager = mockk()

    @Suppress("Deprecation")
    private val legacyPlugin = mockk<LegacyStoragePlugin>()
    private val backupPlugin = mockk<StoragePlugin<*>>()
    private val kvRestore = KVRestore(
        pluginManager = storagePluginManager,
        legacyPlugin = legacyPlugin,
        outputFactory = outputFactory,
        headerReader = headerReader,
        crypto = cryptoImpl,
        dbManager = dbManager,
    )
    private val fullRestore =
        FullRestore(storagePluginManager, legacyPlugin, outputFactory, headerReader, cryptoImpl)
    private val restore = RestoreCoordinator(
        context = context,
        crypto = crypto,
        settingsManager = settingsManager,
        metadataManager = metadataManager,
        notificationManager = notificationManager,
        pluginManager = storagePluginManager,
        kv = kvRestore,
        full = fullRestore,
        metadataReader = metadataReader,
    ).apply { beforeStartRestore(metadata.copy(version = 0x00)) }

    private val fileDescriptor = mockk<ParcelFileDescriptor>(relaxed = true)
    private val appData = ("562AB665C3543120FC794D7CDA3AC18E5959235A4D" +
        "3FD9A75BE521E99EF8D79EA4A98652A0F32AFFEC07").toByteArrayFromHex()
    private val appData2 = ("C530E675053D3D253B7F5BE864B44DCB4484C9660C" +
        "FBDAEA3ECA56A91E5F6D4DF5B79F9B33AF0F8CA73AF3F208916BA96BB49F1B535B05BCCAC2398251E00B" +
        "0EB22214BB6E863E8E6228383E8172526C1E0B5DE353EEB90A31D2CB9DAEFE806A88F0CD7551237B5D1C" +
        "14FB6ED5546CB3B52EA74C1BEA068C99D23FFB3345EA77CEBBA28DB5FBD6AD104CAC4F30E4B7F299E2E1" +
        "AEB93B7CC158322443B47527289123974BD94BD7FED5E0EB26A59CAFB3544B28431255EBAB074EF7E7BA" +
        "37E8B847DFE30B3D3F4334BE248CB384B13C010F439E004E973990CC05062B0B2E090435DA8362B5EF89" +
        "E9E8640E03BD5C179F1FCC23D55737E246F8E36F461D816378F898812750109CD9BA2F5F3A11BCAF53D9" +
        "81CF3A40194FFD13E4343D7A46BB60C2605469F0BCFD4A7DD9273B4B7CDE97591443FDBBFBFF713B4AB9" +
        "C5DC303FDC768AC5C39CBFEA65DB24EE7BF9DE7566D46C3008997EAAE0288CE3BE217AA9081608A0DD06" +
        "222E505F241BCBFC0D2FB2BA2490A315566B1848319C13BF07411623DE201DC70FFF7FF2E834F4910508" +
        "DC1626C1F5C9661F1890C5FB57414DB95E5D3A6651695546D7E0BB8C67C446914D9CD110DF1323FA41AB" +
        "870DEE288E9A8EB79A5902EF19FD89A9E5B2670900227078FF5A18B860CDE01B1F57CA1D910B20836AC1" +
        "8DF92CAB93AFEA235AE44B5141C3E49BE964FF5ADBC5C504CA270B2764B2420192D5B0C8356FDB48C01A" +
        "E00BC0B77098AAD6CDAFC2259E114A6C1C673572EF1A295CC8D00FF0F62F5797A486553604F9EA243DE5" +
        "3CD3CB125A27BFB6C1E07485B0200E792200980C32AE2B1AE10880744396BC8DB153965046EA800F1C57" +
        "8E6978CF482E49112D97CD0498772EDD0A22032530BB2EE12CE2D6640C612CEB0512525AA1AAFBADF17F" +
        "3328D03AC49279DFB4D88F9AD21CE36DB58A4A210267E0705DB243CA419A05DF3DF2C4C06E12E42B0B98" +
        "20CADBB9D469BC266996CD8BB51E30BDDB23980EC1463FD9C5DB9CE31D7E5E4F2C0B8E9B70B02A773297" +
        "0C37CE04083488FDCC0663B89F2F1AFBEB73279DEC7DF783A276FBE80019B56B8A1B9DE9B9730E2D3FAA" +
        "DB3A2B2D14C930F36FA89945D8D0D07C7C5C049011612085835656C768556453EE2BBB6736FB106F81A2" +
        "52EB174688827D03C521C7BE9C31106F3F739142CB0F82F5B0AC8287EE4D4F459BDCD9267CE08375335D" +
        "62997EDCD4AAC7C01CA24328FE753E4D05D152D30C6486E9B77F38AAF8C78855ED90643C42F92AC286DD" +
        "95477E4DD3288923D380E1823325D9D120D49F932B768FCA8CF5C68200918DE9569C5EA6017A213A886E" +
        "01408922943060398FA1599DD22E37D4DCD63E14ECDB821564EC1FB9C30460C917BF9295A42BFF65D6AE" +
        "F3420DA4CD1DA371190D171D6DBD59D58F02290537708E7FEBEFBA5A5E4E8544ECCC8302FA28E66E506E" +
        "78ACEDA79246824591A513D5361E0506FF5C44435FC6BDA03B0D5010A75833B286C3D2C0FE652325AB51" +
        "C7CDF6EA3B5D607BBAD7402936CE453037DECF6EB3AECCF825CC06BB79C7A924CA65C1CCB429DE619914" +
        "90CD1DD35F992F88FC5E632DCE02CB669F813F70CA84E03DA66CE72D5C572D3C8C5974DCB43BFCA24D4A" +
        "EA985D0765819BBE643CA424DEF68232E53EAF58AE31BA38FCB87E1A20B61E01DE29A89BA76098863FFC" +
        "8B85EDCD934C0946E35199CBA77F621CDC46A3DAFE571ECF898E1157B96C2566F355AFD323A585A344FC" +
        "0D660A155F2CEEAF7DAAB0EEDBD08ADBC78486E51D05A2C98672CE02A746A9CD79B3F8062B3143355D7A" +
        "30B8790FBDD26955009867A24FF16FA310887E71CC9817B2495052CBB5CC19C6ADA9592EBF0477DE696D" +
        "858A13A3D23955330BFAFB0915CC").toByteArrayFromHex()
    private val key = "RestoreKey"
    private val key64 = key.encodeBase64()
    private val key2 = "RestoreKey2"
    private val key264 = key2.encodeBase64()

    init {
        every { storagePluginManager.appPlugin } returns backupPlugin
    }

    @Test
    fun `test key-value backup and restore with 2 records`() = runBlocking {
        val encryptedAppData = ("00002A2C701AA7C91D1286E265D29169B25C41E6D0" +
            "E09B70C43E00DE3B00E52C977DBBCA08FD9295002ED5F25CAFA69FA8BE8EB9AE590466B8003A6DC06E84" +
            "A524CBD6A220BE4C00FCA3B13EF3169B30AFA3AAFB8E9AAA4D39C60C68174D52A45CFF82E5DBAA0876EA" +
            "6725949C8BAE04AB447790686F01C5C18925B1D3B60F931C").toByteArrayFromHex()
        val encryptedAppData2 = ("00002BED60ADBCD70A6DDEEB0B0FB70F0D0E1F18EF" +
            "45A5F30C64E5FCF6381F009B0DF62AE761913A1373150A77960061FBE1ABD18E2F4A291F3205499135C6" +
            "388B921EA9CD17DB6E9DA445CB91262362ECB9FAFED0EA0AF0AE601B7AC9D567011F7DD2B852FB36273A" +
            "944E1E23FDAE0FA381698B1440583256C7A8D58248558C8B4D75A2DB5C0E697FFCDA0C905204F6CB0A2B" +
            "DBA46CF86F7A55F8D4A1C04A6EED4B761541BCF18F3FDC953AEFB6B012A0E4B90C90D6214BB2A0E23110" +
            "5521A9E373B88D8995ED9CA0FB4C05394A9254CDC74D2F832C1D760AD7316142F7C77F11073A5FB84E37" +
            "00CCFABE223896BFCFF891B48770FFFAD39810B2F58EF0CE7FC90251791BA33A1FB58EF3000F794218F5" +
            "8734FF29F3849CC0A1D2203824E0D0066AF9712E9A9A05CAADACB55072C6A1C8D1E74A1BEF394067B60A" +
            "F032FDCA2FFB1A5A270F459A565AEFDACCAAE103AB93CCC0AB7E0862A3875522F7D7AFC23479BF73D4A6" +
            "BE104BE3093C0B80B1967085158089F228328FBEA84A087E93F063AAB0F8978859516FA73FAD38C04461" +
            "738F2D822B4FA72DABACF585184330FB680BC1B7EDAD6D375CE6AC6109778C20CE3B32C6E76CEAC728F9" +
            "FE8D0E1403190D2A30DAA3B5BCC06A11E241AD060D7B0DF9F71DC416EF855208A693E8A318157593F869" +
            "FDBEBCCD0B21CD821D3BDD0103D1B959CE623E7920CB642600782C661A6180A299AAE787B0D982AF6881" +
            "E9A905233C92A8107BF3648E1EDDF8FD1F7EA629EE2793CDADDECE7B05BB1A8953C63895F3CF2665A00B" +
            "6D311970714EC1712F1EC47FE76E04FC338E9139306EBA85E97AA09368D76859B9E827F14DE0CC04A8B5" +
            "4F57BE0B22EE3DB621CDBCC2F3E2E524A5D8F9BC1C93C372E30122A7B18EB049F769F0D3F490AC8D2766" +
            "50C41170A55FDA672525C93041D7AA90B8D71B5FD9798DA0E4AB2A85FE58A93F3A505382C28313966C1C" +
            "59D771CC64B166E1F7E7C1CA5ACEA67B1D27F0CABA24FC23BFF358D42579E5CB3C614CC319E645A1EED3" +
            "4CFD767AB29EBEFA26824CBD1285BAC0B45F488B62687E63067A8886D75852AAC61CEF2320AB01854827" +
            "0F042180FA8B6FA6199DBACF9BEBC8F60AE52AF8432DB5CB8E2BA85405BB190DAD2F762830807340916F" +
            "C07841E6122091FFBCE7AB2779635E5650C0D8797682B3C98158CF81B50FA164F8F18FFF7FFC7A3A1ED1" +
            "357C28EBA4BF1936DE10AD04527648E44A81B948BF620E3317056448E24F7A0E6A852F1AE3ADA6522596" +
            "61EF9D687E860F8EEF1D306B5FA4062FEAE2E6BD9E1269C4A85E879C9BB4A6B655C7DAE3BA8684943A14" +
            "F4D03E074812A2137602BFD75AF8A9A5A1415BB4FD55A313649AF6B7DBAE5EE99AF50F45460BB0CDEC9D" +
            "C1508D70CDE76EE054ACCFB1EC2A12FF2E451AAB1FFB7A6E11B745B54A9610ADAEEFCF0B835C04A714A8" +
            "972FC660911712B8A9FDA6514159FA67D54359B7A41EDB0F5809313EC1689ACA1B3335864E8174A33875" +
            "93B4665352D0A926BC6A9901108BB568EA475A3677A38022FC02CCEAFFD6B03EF840F30A981337798DF5" +
            "EDC84FE0D06BEE5C38781B018EF85632546ADA02823217A8029070D5A2B7D91CD3548A8E1E439C5C7472" +
            "4F059C892C2877B6AC49F190EC7EF0F84855024411C9F7153181AD6A59EBE2C6717A7C178F59199C5FAF" +
            "540A3C0425DF9F058C28FC0FEAC1B06136D6A5D43CA951CB4D8731321DB5BC19C2E058F4166E4407331C" +
            "10E0E414B8FED0A06E99FC4E1BFBD78E9AACEE1B6D7480B5BC8C9F7A07302A6F73B989E1D447E7DB8088" +
            "3420D9AB943DF06341BB044970B2573C3912C182EAB89681BF3523EAE62DA8FE9E8BF194E57247485760" +
            "BFB6AC6F69AB5D6E1E59836E6623C8706FF3A15ADA275551ECE42A77EE4E31F9B0DF448D53A164EFCDA9" +
            "0659C6892C63FA6E96FE07AF80EAB910EDE962C3FB60EF43C262C898F5A21917716D643827164CEE550F" +
            "44BE99D6A5CE1A5877E0648BDB5972E54CC7").toByteArrayFromHex()

        // start restore
        assertEquals(TRANSPORT_OK, restore.startRestore(token, arrayOf(packageInfo)))

        // find data for K/V backup
        coEvery { legacyPlugin.hasDataForPackage(token, packageInfo) } returns true

        val restoreDescription = restore.nextRestorePackage() ?: fail()
        assertEquals(packageInfo.packageName, restoreDescription.packageName)
        assertEquals(RestoreDescription.TYPE_KEY_VALUE, restoreDescription.dataType)

        // restore finds the backed up key and writes the decrypted value
        val backupDataOutput = mockk<BackupDataOutput>()
        val rInputStream = ByteArrayInputStream(encryptedAppData)
        val rInputStream2 = ByteArrayInputStream(encryptedAppData2)
        coEvery { legacyPlugin.listRecords(token, packageInfo) } returns listOf(key64, key264)
        every { outputFactory.getBackupDataOutput(fileDescriptor) } returns backupDataOutput
        coEvery {
            legacyPlugin.getInputStreamForRecord(
                token,
                packageInfo,
                key64
            )
        } returns rInputStream
        every { backupDataOutput.writeEntityHeader(key, appData.size) } returns 1137
        every { backupDataOutput.writeEntityData(appData, appData.size) } returns appData.size
        coEvery {
            legacyPlugin.getInputStreamForRecord(
                token,
                packageInfo,
                key264
            )
        } returns rInputStream2
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
        val encryptedData = ("00002076EBEBDDB434FC78D6410C14AEDA1B7257EF" +
            "E44B0CE5A6289BCF46470909577083B8C0B57D85E27EE27785E40025397A8C7F7357F67467C10867222E" +
            "1328BD9584D9035F45D0EA08C9B907E5606E1B2973EADD48BE75284DA4B575127CBB4A00254450CEFE52" +
            "7BA1937B0573FD730D69855E15D6F9887533D6BDE6CD081771C7BBD242903EAC22C4221FFB246D4E169B" +
            "B3CE").toByteArrayFromHex()

        // start restore
        assertEquals(TRANSPORT_OK, restore.startRestore(token, arrayOf(packageInfo)))

        // find data only for full backup
        coEvery { legacyPlugin.hasDataForPackage(token, packageInfo) } returns false
        coEvery { legacyPlugin.hasDataForFullPackage(token, packageInfo) } returns true

        val restoreDescription = restore.nextRestorePackage() ?: fail()
        assertEquals(packageInfo.packageName, restoreDescription.packageName)
        assertEquals(TYPE_FULL_STREAM, restoreDescription.dataType)

        // reverse the backup streams into restore input
        val inputStream = ByteArrayInputStream(encryptedData)
        val outputStream = ByteArrayOutputStream()
        coEvery {
            legacyPlugin.getInputStreamForPackage(
                token,
                packageInfo
            )
        } returns inputStream
        every { outputFactory.getOutputStream(fileDescriptor) } returns outputStream

        // restore data
        assertEquals(appData.size / 2, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertEquals(appData.size / 2, restore.getNextFullRestoreDataChunk(fileDescriptor))
        assertEquals(NO_MORE_DATA, restore.getNextFullRestoreDataChunk(fileDescriptor))
        restore.finishRestore()

        // assert that restored data matches original app data
        assertArrayEquals(appData, outputStream.toByteArray())
    }

}
