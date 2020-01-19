package com.stevesoltys.seedvault.ui

import android.content.Context
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.getRelativeTimeSpanString
import com.stevesoltys.seedvault.R

fun Long.toRelativeTime(context: Context): CharSequence {
    return if (this == 0L) {
        context.getString(R.string.settings_backup_last_backup_never)
    } else {
        val now = System.currentTimeMillis()
        getRelativeTimeSpanString(this, now, MINUTE_IN_MILLIS, 0)
    }
}
