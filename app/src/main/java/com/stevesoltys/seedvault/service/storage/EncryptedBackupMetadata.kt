package com.stevesoltys.seedvault.service.storage

import java.io.InputStream

class EncryptedBackupMetadata(
    val token: Long,
    val inputStreamRetriever: () -> InputStream,
)
