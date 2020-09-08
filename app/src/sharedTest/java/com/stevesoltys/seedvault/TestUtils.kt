package com.stevesoltys.seedvault

import com.stevesoltys.seedvault.plugins.saf.MAX_KEY_LENGTH_NEXTCLOUD
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random

fun assertContains(stack: String?, needle: String) {
    if (stack?.contains(needle) != true) throw AssertionError()
}

@Suppress("MagicNumber")
fun getRandomByteArray(size: Int = Random.nextInt(1337)) = ByteArray(size).apply {
    Random.nextBytes(this)
}

private val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9') + '_' + '.'

@Suppress("MagicNumber")
fun getRandomString(size: Int = Random.nextInt(1, 255)): String {
    return (1..size)
            .map { Random.nextInt(0, charPool.size) }
            .map(charPool::get)
            .joinToString("")
}

// URL-save version (RFC 4648)
private val base64CharPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9') // + '+' + '_' + '='

@Suppress("MagicNumber")
fun getRandomBase64(size: Int = Random.nextInt(1, MAX_KEY_LENGTH_NEXTCLOUD)): String {
    return (1..size)
            .map { Random.nextInt(0, base64CharPool.size) }
            .map(base64CharPool::get)
            .joinToString("")
}

fun ByteArray.toHexString(): String {
    var str = ""
    for (b in this) {
        str += String.format("%02X ", b)
    }
    return str
}

fun ByteArray.toIntString(): String {
    var str = ""
    for (b in this) {
        str += String.format("%02d ", b)
    }
    return str
}

fun OutputStream.writeAndClose(data: ByteArray) = use {
    it.write(data)
}

fun assertReadEquals(data: ByteArray, inputStream: InputStream?) = inputStream?.use {
    assertArrayEquals(data, it.readBytes())
} ?: error("no input stream")

fun <T : Throwable> coAssertThrows(clazz: Class<T>, block: suspend () -> Unit) {
    var thrown = false
    @Suppress("TooGenericExceptionCaught")
    try {
        runBlocking {
            block()
        }
    } catch (e: Throwable) {
        assertEquals(clazz, e.javaClass)
        thrown = true
    }
    if (!thrown) fail("Exception was not thrown: " + clazz.name)
}
