/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import android.content.Context
import com.stevesoltys.seedvault.Clock
import com.stevesoltys.seedvault.metadata.MetadataManager
import com.stevesoltys.seedvault.transport.backup.PackageService

/**
 * Creates a new [SnapshotCreator], because one is only valid for a single backup run.
 */
internal class SnapshotCreatorFactory(
    private val context: Context,
    private val clock: Clock,
    private val packageService: PackageService,
    private val metadataManager: MetadataManager,
) {
    fun createSnapshotCreator() =
        SnapshotCreator(context, clock, packageService, metadataManager)
}
