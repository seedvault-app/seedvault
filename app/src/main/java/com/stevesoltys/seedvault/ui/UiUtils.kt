package com.stevesoltys.seedvault.ui

import android.content.Context
import android.content.res.ColorStateList
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

// Ported from
// https://cs.android.com/android/platform/superproject/+/android-12.0.0_r1:frameworks/base/packages/SettingsLib/src/com/android/settingslib/Utils.java
fun getColorAccent(context: Context): ColorStateList? {
    return getColorAttr(context, android.R.attr.colorAccent)
}

fun getColorAttr(context: Context, attr: Int): ColorStateList? {
    val typedArray = context.obtainStyledAttributes(IntArray(1){ attr })
    var stateList: ColorStateList? = null
    try {
        stateList = typedArray.getColorStateList(0)
    } finally {
        typedArray.recycle()
    }
    return stateList
}
