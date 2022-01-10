package com.stevesoltys.seedvault.ui.storage

import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.DocumentsContract.buildTreeDocumentUri

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
