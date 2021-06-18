package org.calyxos.backup.storage.prune

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.calyxos.backup.storage.api.SnapshotRetention
import org.calyxos.backup.storage.api.StoredSnapshot
import org.calyxos.backup.storage.getRandomString
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset.UTC

internal class RetentionManagerTest {

    private val context: Context = mockk()
    private val prefs: SharedPreferences = mockk()

    private val retention = RetentionManager(context)

    private val userId = getRandomString()

    @Test
    fun testDailyRetention() {
        expectGetRetention(SnapshotRetention(2, 0, 0, 0))
        val storedSnapshots = listOf(
            // 1577919600000
            LocalDateTime.of(2020, 1, 1, 23, 0).toMillis(),
            // 1577872800000
            LocalDateTime.of(2020, 1, 1, 10, 0).toMillis(),
            // 1583276400000
            LocalDateTime.of(2020, 3, 3, 23, 0).toMillis(),
        ).map { StoredSnapshot(userId, it) }
        val toDelete = retention.getSnapshotsToDelete(storedSnapshots)
        assertEquals(listOf(1577872800000), toDelete.map { it.timestamp })
    }

    @Test
    fun testWeeklyRetention() {
        expectGetRetention(SnapshotRetention(0, 2, 0, 0))

        val timestamps = listOf(
            // 1583362800000
            LocalDateTime.of(2020, 3, 4, 23, 0).toMillis(),
            // 1608544800000
            LocalDateTime.of(2020, 12, 21, 10, 0).toMillis(),
            // 1608638400000
            LocalDateTime.of(2020, 12, 22, 12, 0).toMillis(),
            // 1608678000000
            LocalDateTime.of(2020, 12, 22, 23, 0).toMillis(),
        ).map { StoredSnapshot(userId, it) }
        val toDelete = retention.getSnapshotsToDelete(timestamps)
        assertEquals(listOf(1608544800000, 1608638400000), toDelete.map { it.timestamp })
    }

    @Test
    fun testMonthlyRetention() {
        expectGetRetention(SnapshotRetention(2, 0, 2, 0))

        val timestamps = listOf(
            // 1580857200000
            LocalDateTime.of(2020, 2, 4, 23, 0).toMillis(),
            // 1583362800000
            LocalDateTime.of(2020, 3, 4, 23, 0).toMillis(),
            // 1608544800000
            LocalDateTime.of(2020, 12, 21, 10, 0).toMillis(),
            // 1608678000000
            LocalDateTime.of(2020, 12, 22, 23, 0).toMillis(),
        ).map { StoredSnapshot(userId, it) }
        val toDelete = retention.getSnapshotsToDelete(timestamps)
        assertEquals(listOf(1580857200000), toDelete.map { it.timestamp })
    }

    @Test
    fun testYearlyRetention() {
        expectGetRetention(SnapshotRetention(0, 0, 0, 4))

        val timestamps = listOf(
            // 1515106800000
            LocalDateTime.of(2018, 1, 4, 23, 0).toMillis(),
            // 1549321200000
            LocalDateTime.of(2019, 2, 4, 23, 0).toMillis(),
            // 1551740400000
            LocalDateTime.of(2019, 3, 4, 23, 0).toMillis(),
            // 1608544800000
            LocalDateTime.of(2020, 12, 21, 10, 0).toMillis(),
            // 1608678000000
            LocalDateTime.of(2020, 12, 22, 23, 0).toMillis(),
        ).map { StoredSnapshot(userId, it) }
        // keeps only the latest one for each year, so three in total, even though keep is four
        val toDelete = retention.getSnapshotsToDelete(timestamps)
        assertEquals(listOf(1549321200000, 1608544800000), toDelete.map { it.timestamp })
    }

    @Test
    fun testDefaultRetention() {
        expectGetRetention(
            SnapshotRetention(
                PREF_DEFAULT_DAILY,
                PREF_DEFAULT_WEEKLY,
                PREF_DEFAULT_MONTHLY,
                PREF_DEFAULT_YEARLY
            )
        )
        val timestamps = listOf(
            // 1515106800000
            LocalDateTime.of(2018, 1, 4, 23, 0).toMillis(),
            // 1549321200000
            LocalDateTime.of(2019, 2, 4, 23, 0).toMillis(),
            // 1551740400000
            LocalDateTime.of(2019, 3, 4, 23, 0).toMillis(),
            // 1551441600000
            LocalDateTime.of(2019, 3, 1, 12, 0).toMillis(),
            // 1608044400000
            LocalDateTime.of(2020, 12, 15, 15, 0).toMillis(),
            // 1608544800000
            LocalDateTime.of(2020, 12, 21, 10, 0).toMillis(),
            // 1608678000000
            LocalDateTime.of(2020, 12, 22, 23, 0).toMillis(),
            // 1608638400000
            LocalDateTime.of(2020, 12, 22, 12, 0).toMillis(),
        ).map { StoredSnapshot(userId, it) }
        val toDelete = retention.getSnapshotsToDelete(timestamps)
        assertEquals(
            listOf(1515106800000, 1549321200000, 1551441600000, 1608638400000),
            toDelete.map { it.timestamp })
    }

    private fun expectGetRetention(snapshotRetention: SnapshotRetention) {
        every { context.getSharedPreferences(PREFS, 0) } returns prefs
        every { prefs.getInt(PREF_DAILY, any()) } returns snapshotRetention.daily
        every { prefs.getInt(PREF_WEEKLY, any()) } returns snapshotRetention.weekly
        every { prefs.getInt(PREF_MONTHLY, any()) } returns snapshotRetention.monthly
        every { prefs.getInt(PREF_YEARLY, any()) } returns snapshotRetention.yearly
    }

    private fun LocalDateTime.toMillis(): Long {
        return toInstant(UTC).toEpochMilli()
    }

}
