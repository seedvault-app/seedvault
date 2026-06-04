/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.check

import android.os.Bundle
import android.text.format.Formatter.formatShortFileSize
import android.view.View.GONE
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.repo.Checker
import com.stevesoltys.seedvault.repo.CheckerResult
import com.stevesoltys.seedvault.restore.RestoreSetAdapter
import com.stevesoltys.seedvault.transport.restore.RestorableBackup
import com.stevesoltys.seedvault.ui.BackupActivity
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.android.ext.android.inject

internal const val ACTION_FINISHED = "FINISHED"
internal const val ACTION_SHOW = "SHOW"

class AppCheckResultActivity : BackupActivity() {

    private val log = KotlinLogging.logger { }

    private val checker: Checker by inject()
    private val notificationManager: BackupNotificationManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) when (intent.action) {
            ACTION_FINISHED -> {
                notificationManager.onCheckCompleteNotificationSeen()
                checker.clear()
                finish()
            }
            ACTION_SHOW -> {
                notificationManager.onCheckCompleteNotificationSeen()
                onActionReceived()
            }
            else -> {
                log.error { "Unknown action: ${intent.action}" }
                finish()
            }
        }
    }

    private fun onActionReceived() {
        when (val result = checker.checkerResult) {
            is CheckerResult.Success -> onSuccess(result)
            is CheckerResult.Error -> onError(result)
            is CheckerResult.GeneralError, null -> {
                if (result == null) {
                    val str = getString(R.string.backup_app_check_error_no_result)
                    val e = NullPointerException(str)
                    val r = CheckerResult.GeneralError(e)
                    onGeneralError(r)
                } else {
                    onGeneralError(result as CheckerResult.GeneralError)
                }
            }
        }
        checker.clear()
    }

    private fun onSuccess(result: CheckerResult.Success) {
        setContentView(R.layout.activity_check_result)
        val intro = getString(
            R.string.backup_app_check_success_intro,
            result.snapshots.size,
            result.percent,
            formatShortFileSize(this, result.size),
        )
        requireViewById<TextView>(R.id.introView).text = intro

        val listView = requireViewById<RecyclerView>(R.id.listView)
        listView.adapter = RestoreSetAdapter(
            listener = null,
            items = result.snapshots.map { snapshot ->
                RestorableBackup("", snapshot)
            }.sortedByDescending { it.time },
        )
    }

    private fun onError(result: CheckerResult.Error) {
        setContentView(R.layout.activity_check_result)
        requireViewById<ImageView>(R.id.imageView).setImageResource(R.drawable.ic_cloud_error)
        requireViewById<TextView>(R.id.titleView).setText(R.string.backup_app_check_error_title)
        val disclaimerView = requireViewById<TextView>(R.id.disclaimerView)

        val intro = if (result.existingSnapshots == 0) {
            disclaimerView.visibility = GONE
            getString(R.string.backup_app_check_error_no_snapshots)
        } else if (result.snapshots.isEmpty()) {
            disclaimerView.visibility = GONE
            getString(
                R.string.backup_app_check_error_only_broken_snapshots,
                result.existingSnapshots,
            )
        } else {
            getString(R.string.backup_app_check_error_has_snapshots, result.existingSnapshots)
        }
        requireViewById<TextView>(R.id.introView).text = intro

        val items = (result.goodSnapshots.map { snapshot ->
            RestorableBackup("", snapshot)
        } + result.badSnapshots.map { snapshot ->
            RestorableBackup("", snapshot, false)
        }).sortedByDescending { it.time }
        val listView = requireViewById<RecyclerView>(R.id.listView)
        listView.adapter = RestoreSetAdapter(
            listener = null,
            items = items,
        )
    }

    private fun onGeneralError(result: CheckerResult.GeneralError) {
        setContentView(R.layout.activity_check_result)
        requireViewById<ImageView>(R.id.imageView).setImageResource(R.drawable.ic_cloud_error)
        requireViewById<TextView>(R.id.titleView).setText(R.string.backup_app_check_error_title)

        requireViewById<TextView>(R.id.introView).text =
            getString(R.string.backup_app_check_error_no_snapshots)

        requireViewById<TextView>(R.id.disclaimerView).text =
            "${result.e.localizedMessage}\n\n${result.e}"
    }
}
