package dev.igun997.fluteisland.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class HttpException(val code: Int, message: String, val body: String = "") : Exception(message)

object Http {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 10_000): String =
        request("GET", url, null, headers, timeoutMs)

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000): String =
        request("POST", url, json, headers + ("Content-Type" to "application/json"), timeoutMs)

    private suspend fun request(method: String, url: String, body: String?, headers: Map<String, String>, timeoutMs: Int): String =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = method
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw HttpException(code, "HTTP $code", text)
                text
            } finally {
                conn.disconnect()
            }
        }
}
