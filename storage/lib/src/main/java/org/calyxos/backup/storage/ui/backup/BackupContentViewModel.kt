/*
 * SPDX-FileCopyrightText: 2021 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.backup.storage.ui.backup

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.calyxos.backup.storage.api.BackupContentType
import org.calyxos.backup.storage.api.EXTERNAL_STORAGE_PROVIDER_AUTHORITY
import org.calyxos.backup.storage.api.SnapshotRetention
import org.calyxos.backup.storage.api.StorageBackup
import org.calyxos.backup.storage.api.mediaItems

public abstract class BackupContentViewModel(app: Application) : AndroidViewModel(app) {

    protected abstract val storageBackup: StorageBackup

    private val _content = MutableLiveData<List<BackupContentItem>>()
    internal val content: LiveData<List<BackupContentItem>> = _content

    internal fun addUri(uri: Uri) {
        viewModelScope.launch {
            storageBackup.addUri(uri)
            loadContent()
        }
    }

    internal fun removeUri(uri: Uri) {
        viewModelScope.launch {
            storageBackup.removeUri(uri)
            loadContent()
        }
    }

    protected suspend fun loadContent(): Unit = withContext(Dispatchers.Default) {
        val uris = storageBackup.uris
        val items = ArrayList<BackupContentItem>(uris.size)
        mediaItems.forEach { mediaType ->
            items.add(
                BackupContentItem(
                    mediaType.contentUri,
                    mediaType,
                    uris.contains(mediaType.contentUri)
                )
            )
        }
        uris.forEach { uri ->
            if (uri.authority == EXTERNAL_STORAGE_PROVIDER_AUTHORITY) {
                items.add(BackupContentItem(uri, BackupContentType.Custom, true))
            }
        }
        _content.postValue(items)
        storageBackup.setSnapshotRetention(SnapshotRetention(15, 10, 5, 2))
    }

}
