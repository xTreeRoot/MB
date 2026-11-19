package com.xtreeroot.mb.bilibili

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

internal fun interface BilibiliTransport {
    suspend fun get(address: String, headers: Map<String, String>): JSONObject

    suspend fun getResponse(address: String, headers: Map<String, String>): BilibiliResponse =
        BilibiliResponse(get(address, headers))

    suspend fun post(address: String, headers: Map<String, String>, body: String): JSONObject =
        throw UnsupportedOperationException()
}

internal data class BilibiliResponse(val body: JSONObject, val setCookies: List<String> = emptyList())

internal class UrlConnectionTransport(private val open: (URL) -> java.net.URLConnection = URL::openConnection) : BilibiliTransport {
    override suspend fun get(address: String, headers: Map<String, String>): JSONObject = getResponse(address, headers).body

    override suspend fun getResponse(address: String, headers: Map<String, String>): BilibiliResponse =
        request(address, headers)

    override suspend fun post(address: String, headers: Map<String, String>, body: String): JSONObject =
        request(address, headers, body).body

    private suspend fun request(address: String, headers: Map<String, String>, form: String? = null): BilibiliResponse =
        runInterruptible(Dispatchers.IO) {
            val connection = open(URL(address)) as HttpsURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 12_000
                connection.readTimeout = 15_000
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                if (form != null) {
                    val bytes = form.toByteArray(Charsets.UTF_8)
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                if (status !in 200..299) throw IOException("哔哩哔哩请求失败（HTTP $status）")
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader -> JSONObject(reader.readText()) }
                val cookies = connection.headerFields.entries
                    .filter { entry -> entry.key.equals("Set-Cookie", ignoreCase = true) }.flatMap { it.value }
                BilibiliResponse(body, cookies)
            } finally {
                connection.disconnect()
            }
        }
}
