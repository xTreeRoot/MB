package com.xtreeroot.mb.bilibili

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

internal object WbiSigner {
    private val order = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
        27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    )

    fun key(imageUrl: String, subUrl: String): String {
        val joined = listOf(imageUrl, subUrl).joinToString("") { url ->
            URI(url).path.substringAfterLast('/').substringBeforeLast('.')
        }
        require(joined.length == 64) { "未取得有效的哔哩哔哩签名密钥" }
        return order.map { joined[it] }.joinToString("")
    }

    fun sign(parameters: Map<String, String>, key: String, timestamp: Long): String {
        val query = query((parameters - "w_rid" + ("wts" to timestamp.toString())).mapValues { entry ->
            entry.value.filterNot { it in "!'()*" }
        })
        val digest = MessageDigest.getInstance("MD5").digest((query + key).toByteArray(Charsets.UTF_8))
        return "$query&w_rid=${digest.joinToString("") { "%02x".format(it) }}"
    }

    fun query(parameters: Map<String, String>): String = parameters.toSortedMap().entries.joinToString("&") { entry ->
        "${encode(entry.key)}=${encode(entry.value)}"
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
}
