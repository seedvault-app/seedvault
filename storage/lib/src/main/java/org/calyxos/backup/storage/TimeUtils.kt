package org.calyxos.backup.storage

import android.util.Log
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlin.time.toDuration

/**
 * We don't use [kotlin.time.measureTime] or [kotlin.time.measureTimedValue],
 * because those are not available in the Kotlin version shipped with Android 11.
 * So when building with AOSP 11, things will blow up.
 */

@OptIn(ExperimentalContracts::class, ExperimentalTime::class)
internal inline fun measure(block: () -> Unit): Duration {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val start = System.currentTimeMillis()
    block()
    return (System.currentTimeMillis() - start).toDuration(DurationUnit.MILLISECONDS)
}

@OptIn(ExperimentalContracts::class, ExperimentalTime::class)
internal inline fun <T> measure(text: String, block: () -> T): T {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }
    val start = System.currentTimeMillis()
    val result = block()
    val duration = (System.currentTimeMillis() - start).toDuration(DurationUnit.MILLISECONDS)
    Log.e("Time", "$text took $duration")
    return result
}
