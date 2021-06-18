package org.calyxos.backup.storage.prune

import android.content.Context
import org.calyxos.backup.storage.api.SnapshotRetention
import org.calyxos.backup.storage.api.StoredSnapshot
import java.io.IOException
import java.time.LocalDate
import java.time.temporal.ChronoField
import java.time.temporal.Temporal
import java.time.temporal.TemporalAdjuster
import java.time.temporal.TemporalAdjusters.firstDayOfMonth
import java.time.temporal.TemporalAdjusters.firstDayOfYear

internal const val PREFS = "org.calyxos.backup.storage.prune.retention"
internal const val PREF_DAILY = "daily"
internal const val PREF_WEEKLY = "weekly"
internal const val PREF_MONTHLY = "monthly"
internal const val PREF_YEARLY = "yearly"
internal const val PREF_DEFAULT_DAILY = 3
internal const val PREF_DEFAULT_WEEKLY = 2
internal const val PREF_DEFAULT_MONTHLY = 2
internal const val PREF_DEFAULT_YEARLY = 1

internal class RetentionManager(private val context: Context) {

    fun setSnapshotRetention(retention: SnapshotRetention) {
        if (retention.daily == 0 &&
            retention.weekly == 0 &&
            retention.monthly == 0 &&
            retention.yearly == 0
        ) throw IllegalArgumentException("Not all values can be 0")
        context.getSharedPreferences(PREFS, 0)
            .edit()
            .putInt(PREF_DAILY, retention.daily)
            .putInt(PREF_WEEKLY, retention.weekly)
            .putInt(PREF_MONTHLY, retention.monthly)
            .putInt(PREF_YEARLY, retention.yearly)
            .apply()
    }

    fun getSnapshotRetention(): SnapshotRetention {
        val prefs = context.getSharedPreferences(PREFS, 0)
        return SnapshotRetention(
            daily = prefs.getInt(PREF_DAILY, 3),
            weekly = prefs.getInt(PREF_WEEKLY, 1),
            monthly = prefs.getInt(PREF_MONTHLY, 1),
            yearly = prefs.getInt(PREF_YEARLY, 1),
        )
    }

    /**
     * Takes a list of [StoredSnapshot]s and returns a list of those
     * that can be deleted according to the current snapshot retention policy.
     */
    @Throws(IOException::class)
    fun getSnapshotsToDelete(storedSnapshots: List<StoredSnapshot>): List<StoredSnapshot> {
        val retention = getSnapshotRetention()
        val datePairs = storedSnapshots.sortedByDescending { it.timestamp }.map { s ->
            Pair(s, LocalDate.ofEpochDay(s.timestamp / 1000 / 60 / 60 / 24))
        }
        val toKeep = HashSet<StoredSnapshot>()
        toKeep += getToKeep(datePairs, retention.daily)
        toKeep += getToKeep(datePairs, retention.weekly) { temporal: Temporal ->
            temporal.with(ChronoField.DAY_OF_WEEK, 1)
        }
        toKeep += getToKeep(datePairs, retention.monthly, firstDayOfMonth())
        toKeep += getToKeep(datePairs, retention.yearly, firstDayOfYear())
        return storedSnapshots - toKeep
    }

    private fun getToKeep(
        pairs: List<Pair<StoredSnapshot, LocalDate>>,
        keep: Int,
        temporalAdjuster: TemporalAdjuster? = null,
    ): List<StoredSnapshot> {
        val toKeep = ArrayList<StoredSnapshot>()
        if (keep == 0) return toKeep
        var last: LocalDate? = null
        for ((snapshot, date) in pairs) {
            val period = if (temporalAdjuster == null) date else date.with(temporalAdjuster)
            if (period != last) {
                toKeep.add(snapshot)
                if (toKeep.size >= keep) break
                last = period
            }
        }
        return toKeep
    }

}
