/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.storage

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.DocumentsContract.buildTreeDocumentUri
import androidx.appcompat.content.res.AppCompatResources.getDrawable
import com.stevesoltys.seedvault.R

internal sealed class StorageOption {
    abstract val id: String
    abstract val icon: Drawable?
    abstract val title: String
    abstract val summary: String?
    abstract val availableBytes: Long?
    abstract val requiresNetwork: Boolean
    abstract val enabled: Boolean
    abstract val nonDefaultAction: (() -> Unit)?

    data class SafOption(
        override val icon: Drawable?,
        override val title: String,
        override val summary: String?,
        override val availableBytes: Long?,
        val authority: String,
        val rootId: String,
        val documentId: String,
        val isUsb: Boolean,
        override val requiresNetwork: Boolean,
        override val enabled: Boolean = true,
        override val nonDefaultAction: (() -> Unit)? = null,
    ) : StorageOption() {
        override val id: String = "saf-$authority"

        val uri: Uri by lazy {
            buildTreeDocumentUri(authority, documentId)
        }

        fun isInternal(): Boolean {
            return authority == AUTHORITY_STORAGE && !isUsb
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is StorageOption && other.id == id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

internal class WebDavOption(context: Context) : StorageOption() {
    override val id: String = "webdav"
    override val icon: Drawable? = getDrawable(context, R.drawable.ic_cloud_circle)
    override val title: String = context.getString(R.string.storage_webdav_option_title)
    override val summary: String = context.getString(R.string.storage_webdav_option_summary)
    override val availableBytes: Long? = null
    override val requiresNetwork: Boolean = true
    override val enabled: Boolean = true
    override val nonDefaultAction: (() -> Unit)? = null
}
