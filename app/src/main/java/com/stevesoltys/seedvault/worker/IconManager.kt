/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import android.content.Context
import android.graphics.Bitmap.CompressFormat.PNG
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.crypto.TYPE_ICONS
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.repo.AppBackupManager
import com.stevesoltys.seedvault.repo.BackupReceiver
import com.stevesoltys.seedvault.repo.Loader
import com.stevesoltys.seedvault.transport.backup.PackageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.calyxos.backup.storage.crypto.StreamCrypto.toByteArray
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.toHexString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.attribute.FileTime
import java.security.GeneralSecurityException
import java.util.zip.Deflater.NO_COMPRESSION
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val ICON_SIZE = 64
private const val CACHE_FOLDER = "restore-icons"
private val TAG = IconManager::class.simpleName

internal class IconManager(
    private val context: Context,
    private val packageService: PackageService,
    private val crypto: Crypto,
    private val backupReceiver: BackupReceiver,
    private val loader: Loader,
    private val appBackupManager: AppBackupManager,
) {

    private val snapshotCreator
        get() = appBackupManager.snapshotCreator ?: error("No SnapshotCreator")

    @Throws(IOException::class, GeneralSecurityException::class)
    suspend fun uploadIcons() {
        Log.d(TAG, "Start uploading icons")
        val packageManager = context.packageManager
        val byteArrayOutputStream = ByteArrayOutputStream()
        ZipOutputStream(byteArrayOutputStream).use { zip ->
            zip.setLevel(NO_COMPRESSION) // we compress with zstd after chunking the zip
            val entries = mutableSetOf<String>()
            // sort packages by package name to get deterministic ZIP
            packageService.allUserPackages.sortedBy { it.packageName }.forEach {
                val applicationInfo = it.applicationInfo ?: return@forEach
                val drawable = packageManager.getApplicationIcon(applicationInfo)
                if (packageManager.isDefaultApplicationIcon(drawable)) return@forEach
                val entry = ZipEntry(it.packageName).apply {
                    // needed to be deterministic
                    setLastModifiedTime(FileTime.fromMillis(0))
                }
                zip.putNextEntry(entry)
                // WEBP_LOSSY compression wasn't deterministic in our tests,
                // and JPEG doesn't support transparency (causing black squares),
                // so use PNG
                drawable.toBitmap(ICON_SIZE, ICON_SIZE).compress(PNG, 0, zip)
                entries.add(it.packageName)
                zip.closeEntry()
            }
            // sort packages by package name to get deterministic ZIP
            packageService.launchableSystemApps.sortedBy { it.activityInfo.packageName }.forEach {
                val drawable = it.loadIcon(packageManager)
                if (packageManager.isDefaultApplicationIcon(drawable)) return@forEach
                // check for duplicates (e.g. updated launchable system app)
                if (it.activityInfo.packageName in entries) return@forEach
                val entry = ZipEntry(it.activityInfo.packageName).apply {
                    // needed to be deterministic
                    setLastModifiedTime(FileTime.fromMillis(0))
                }
                zip.putNextEntry(entry)
                // For PNG choice see comment above
                drawable.toBitmap(ICON_SIZE, ICON_SIZE).compress(PNG, 0, zip)
                zip.closeEntry()
            }
        }
        val owner = "IconManager"
        try {
            backupReceiver.addBytes(owner, byteArrayOutputStream.toByteArray())
        } catch (e: Exception) {
            // ensure to call finalize, even if an exception gets thrown while adding bytes
            backupReceiver.finalize(owner)
            throw e
        }
        // call finalize and add to snapshot only when we got here without exception
        val backupData = backupReceiver.finalize(owner)
        snapshotCreator.onIconsBackedUp(backupData)
        Log.d(TAG, "Finished uploading icons")
    }

    /**
     * Downloads icons file from given [snapshot] from the repository with [repoId].
     * @return a set of package names for which icons were found
     */
    @Throws(IOException::class, SecurityException::class, GeneralSecurityException::class)
    suspend fun downloadIcons(repoId: String, snapshot: Snapshot): Set<String> {
        Log.d(TAG, "Start downloading icons")
        val folder = File(context.cacheDir, CACHE_FOLDER)
        if (!folder.isDirectory && !folder.mkdirs())
            throw IOException("Can't create cache folder for icons")

        val outputStream = ByteArrayOutputStream()
        snapshot.iconChunkIdsList.forEach {
            val blob = snapshot.getBlobsOrThrow(it.toByteArray().toHexString())
            val handle = AppBackupFileType.Blob(repoId, blob.id.toByteArray().toHexString())
            loader.loadFile(handle).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        val set = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(outputStream.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                File(folder, entry.name).outputStream().use { outputStream ->
                    zip.copyTo(outputStream)
                }
                set.add(entry.name)
                entry = zip.nextEntry
            }
        }
        Log.d(TAG, "Finished downloading icons")
        return set
    }

    /**
     * Downloads icons file from given [inputStream].
     * @return a set of package names for which icons were found
     */
    @Suppress("DEPRECATION")
    @Throws(IOException::class, SecurityException::class, GeneralSecurityException::class)
    fun downloadIconsV1(token: Long, inputStream: InputStream): Set<String> {
        Log.d(TAG, "Start downloading icons")
        val folder = File(context.cacheDir, CACHE_FOLDER)
        if (!folder.isDirectory && !folder.mkdirs())
            throw IOException("Can't create cache folder for icons")
        val set = mutableSetOf<String>()
        crypto.newDecryptingStreamV1(inputStream, getAD(1.toByte(), token)).use { cryptoStream ->
            ZipInputStream(cryptoStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    File(folder, entry.name).outputStream().use { outputStream ->
                        zip.copyTo(outputStream)
                    }
                    set.add(entry.name)
                    entry = zip.nextEntry
                }
            }
        }
        Log.d(TAG, "Finished downloading icons")
        return set
    }

    private val defaultIcon by lazy {
        getDrawable(context, R.drawable.ic_launcher_default)!!
    }

    /**
     * Tries to load the icons for the given [packageName]
     * that was downloaded before with [downloadIcons].
     * Calls [callback] on the UiThread with the loaded [Drawable] or the default icon.
     */
    suspend fun loadIcon(packageName: String, callback: (Drawable) -> Unit) {
        try {
            withContext(Dispatchers.IO) {
                val folder = File(context.cacheDir, CACHE_FOLDER)
                val file = File(folder, packageName)
                file.inputStream().use { inputStream ->
                    val drawable =
                        BitmapFactory.decodeStream(inputStream).toDrawable(context.resources)
                    withContext(Dispatchers.Main) {
                        callback(drawable)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading icon for $packageName", e)
            withContext(Dispatchers.Main) {
                callback(defaultIcon)
            }
        }
    }

    @WorkerThread
    fun removeIcons() {
        val folder = File(context.cacheDir, CACHE_FOLDER)
        val result = folder.deleteRecursively()
        Log.e(TAG, "Could delete icons: $result")
    }

    private fun getAD(version: Byte, token: Long) = ByteBuffer.allocate(2 + 8)
        .put(version)
        .put(TYPE_ICONS)
        .put(token.toByteArray())
        .array()

}
