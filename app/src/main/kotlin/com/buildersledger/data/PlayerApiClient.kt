package com.buildersledger.data

import com.buildersledger.domain.ApiError
import com.buildersledger.domain.ApiException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One GET with a bearer key, no extra dependencies. Redirects are never followed so the key cannot be forwarded. */
class PlayerApiClient {
    suspend fun get(url: String, key: String): String = withContext(Dispatchers.IO) {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw ApiException(ApiError.BadBaseUrl)
        }
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code in 200..299) {
                readLimited(conn.inputStream)
            } else {
                val body = runCatching { conn.errorStream?.let { readLimited(it) } }.getOrNull()
                throw ApiException(ApiError.fromStatus(code, body))
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(ApiError.Offline)
        } catch (e: SecurityException) {
            throw ApiException(ApiError.Offline)
        } finally {
            conn.disconnect()
        }
    }

    private fun readLimited(input: InputStream): String {
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) throw ApiException(ApiError.BadResponse)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
