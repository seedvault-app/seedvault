/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.worker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
import android.graphics.BitmapFactory
import android.util.Log
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import androidx.core.graphics.drawable.toBitmap
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.transport.backup.PackageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater.BEST_SPEED
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal const val FILE_BACKUP_ICONS = ".backup.icons"
private const val ICON_SIZE = 128
private const val ICON_QUALITY = 75
private const val CACHE_FOLDER = "restore-icons"
private val TAG = IconManager::class.simpleName

internal class IconManager(
    private val context: Context,
    private val packageService: PackageService,
) {

    @Throws(IOException::class)
    fun uploadIcons(outputStream: OutputStream) {
        Log.d(TAG, "Start uploading icons")
        val packageManager = context.packageManager
        ZipOutputStream(outputStream).use { zip ->
            zip.setLevel(BEST_SPEED)
            val entries = mutableSetOf<String>()
            packageService.allUserPackages.forEach {
                val drawable = packageManager.getApplicationIcon(it.applicationInfo)
                if (packageManager.isDefaultApplicationIcon(drawable)) return@forEach
                val entry = ZipEntry(it.packageName)
                zip.putNextEntry(entry)
                drawable.toBitmap(ICON_SIZE, ICON_SIZE).compress(WEBP_LOSSY, ICON_QUALITY, zip)
                entries.add(it.packageName)
                zip.closeEntry()
            }
            packageService.launchableSystemApps.forEach {
                val drawable = it.loadIcon(packageManager)
                if (packageManager.isDefaultApplicationIcon(drawable)) return@forEach
                // check for duplicates (e.g. updated launchable system app)
                if (it.activityInfo.packageName in entries) return@forEach
                val entry = ZipEntry(it.activityInfo.packageName)
                zip.putNextEntry(entry)
                drawable.toBitmap(ICON_SIZE, ICON_SIZE).compress(WEBP_LOSSY, ICON_QUALITY, zip)
                zip.closeEntry()
            }
        }
        Log.d(TAG, "Finished uploading icons")
    }

    /**
     * Downloads icons file from given [inputStream].
     * @return a set of package names for which icons were found
     */
    @Throws(IOException::class, SecurityException::class)
    fun downloadIcons(inputStream: InputStream): Set<String> {
        Log.d(TAG, "Start downloading icons")
        val folder = File(context.cacheDir, CACHE_FOLDER)
        if (!folder.isDirectory && !folder.mkdirs())
            throw IOException("Can't create cache folder for icons")
        val set = mutableSetOf<String>()
        ZipInputStream(inputStream).use { zip ->
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

    private val defaultIcon by lazy {
        getDrawable(context, R.drawable.ic_launcher_default)!!.toBitmap()
    }

    /**
     * Tries to load the icons for the given [packageName]
     * that was downloaded before with [downloadIcons].
     * Calls [callback] on the UiThread with the loaded [Bitmap] or the default icon.
     */
    suspend fun loadIcon(packageName: String, callback: (Bitmap) -> Unit) {
        try {
            withContext(Dispatchers.IO) {
                val folder = File(context.cacheDir, CACHE_FOLDER)
                val file = File(folder, packageName)
                file.inputStream().use { inputStream ->
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    withContext(Dispatchers.Main) {
                        callback(bitmap)
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

}
