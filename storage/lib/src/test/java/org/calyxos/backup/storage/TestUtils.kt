package org.calyxos.backup.storage

import android.util.Log
import io.mockk.InternalPlatformDsl
import io.mockk.InternalPlatformDsl.toStr
import io.mockk.Matcher
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import org.calyxos.backup.storage.content.DocFile
import org.calyxos.backup.storage.db.CachedFile
import kotlin.random.Random

private val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')

internal fun getRandomString(length: Int = Random.nextInt(1, 64)): String = (1..length)
    .map { Random.nextInt(0, charPool.size) }
    .map(charPool::get)
    .joinToString("")

internal fun mockLog(error: Boolean = true) {
    mockkStatic(Log::class)
    every { Log.v(any(), any()) } returns 0
    every { Log.d(any(), any()) } returns 0
    every { Log.i(any(), any()) } returns 0
    every { Log.w(any(), ofType(String::class)) } returns 0
    every { Log.w(any(), ofType(String::class), any()) } returns 0
    if (error) {
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }
}

internal fun getRandomDocFile(size: Long = Random.nextLong()): DocFile {
    return DocFile(
        uri = mockk(),
        dirPath = getRandomString(),
        fileName = getRandomString(),
        lastModified = Random.nextLong(),
        size = size,
        volume = getRandomString()
    )
}

/**
 * Checks [CachedFile] equality, but ignores [CachedFile.lastSeen].
 */
internal fun MockKMatcherScope.sameCachedFile(value: CachedFile): CachedFile =
    match(CachedFileMatcher(value))

internal data class CachedFileMatcher(private val value: CachedFile) : Matcher<CachedFile> {
    override fun match(arg: CachedFile?): Boolean {
        return if (arg == null) {
            false
        } else {
            InternalPlatformDsl.deepEquals(arg.copy(lastSeen = value.lastSeen), value)
        }
    }

    override fun toString(): String {
        return "eq(${value.toStr()})"
    }
}
