package com.stevesoltys.backup

import kotlin.random.Random

fun assertContains(stack: String?, needle: String) {
    if (stack?.contains(needle) != true) throw AssertionError()
}

fun getRandomByteArray(size: Int = Random.nextInt(1337)) = ByteArray(size).apply {
    Random.nextBytes(this)
}

private val charPool : List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9') + '_' + '.'

fun getRandomString(size: Int = Random.nextInt(1, 255)): String {
    return (1..size)
            .map { Random.nextInt(0, charPool.size) }
            .map(charPool::get)
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
