package dev.wonderkast.wondertranslate

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** No key required, but Google's public web endpoint is undocumented and can change. */
internal class TranslationClient {
    sealed class Result {
        data class Success(val text: String) : Result()
        data class Failure(val code: Int, val category: String = "HTTP_ERROR") : Result()
    }

    fun translate(input: String, target: String): Result =
        translateWith(input, target, ::translatePlain)

    // Testable without network: protected source tokens must never reach transport.
    internal fun translateWith(input: String, target: String, transport: (String, String) -> Result): Result {
        val plan = ProtectedMessage.parse(input)
        if (!plan.structured) return transport(input, target)
        if (plan.naturalParts == 0) return Result.Success(input)
        // Fail closed on pathological markup, not dozens of HTTP requests.
        if (plan.pieces.size > 80 || plan.naturalParts > 16) return Result.Success(input)
        val masked = plan.masked()
        if (!input.contains("⟦WT") && masked.length <= 1_300) {
            val result = transport(masked, target)
            if (result is Result.Success) {
                val restored = plan.restore(result.text)
                if (restored != null) return Result.Success(restored)
                // If the service modifies/reorders a marker, retry only natural text.
            } else return result
        }
        val translated = StringBuilder()
        for (part in plan.pieces) {
            if (part.protected || part.value.none(Char::isLetter)) {
                translated.append(part.value)
            } else {
                val result = transport(part.value, target)
                if (result !is Result.Success) return result
                translated.append(result.text)
            }
        }
        return Result.Success(translated.toString())
    }

    private fun translatePlain(input: String, target: String): Result {
        if (input.isEmpty()) return Result.Success(input)
        val parts = splitMessage(input)
        val result = StringBuilder()
        for (part in parts) {
            val translated = translatePart(part, target)
            if (translated !is Result.Success) return translated
            result.append(translated.text)
        }
        return Result.Success(result.toString())
    }

    private fun translatePart(part: String, target: String): Result {
        var connection: HttpURLConnection? = null
        return try {
            val address = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" +
                URLEncoder.encode(target, "UTF-8") + "&dt=t&q=" + URLEncoder.encode(part, "UTF-8")
            connection = (URL(address).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Aliucord WonderTranslate/0.1)")
                setRequestProperty("Accept", "application/json")
                instanceFollowRedirects = false
            }
            val code = connection.responseCode
            if (code !in 200..299) return Result.Failure(code)
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val sections = JSONArray(raw).getJSONArray(0)
            val translated = StringBuilder()
            for (i in 0 until sections.length()) {
                translated.append(sections.getJSONArray(i).optString(0, ""))
            }
            Result.Success(translated.toString())
        } catch (_: java.net.UnknownHostException) {
            Result.Failure(-1, "DNS")
        } catch (_: java.net.SocketTimeoutException) {
            Result.Failure(-1, "TIMEOUT")
        } catch (_: javax.net.ssl.SSLException) {
            Result.Failure(-1, "TLS")
        } catch (_: org.json.JSONException) {
            Result.Failure(-1, "INVALID_JSON")
        } catch (_: java.io.IOException) {
            Result.Failure(-1, "NETWORK")
        } catch (_: Exception) {
            Result.Failure(-1, "UNEXPECTED")
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val MAX_MESSAGE_CHARS = 8_000
        private const val CHUNK_SIZE = 1_500

        /** Chunks at spaces/newlines and never cuts a UTF-16 surrogate pair in half. */
        fun splitMessage(s: String): List<String> {
            if (s.length <= CHUNK_SIZE) return listOf(s)
            val chunks = ArrayList<String>()
            var start = 0
            while (start < s.length) {
                if (s.length - start <= CHUNK_SIZE) {
                    chunks.add(s.substring(start))
                    break
                }
                val idealEnd = start + CHUNK_SIZE
                val range = s.substring(start, idealEnd)
                val delimiter = maxOf(range.lastIndexOf(' '), range.lastIndexOf('\n'))
                var end = if (delimiter > CHUNK_SIZE / 2) start + delimiter + 1 else idealEnd
                if (end < s.length && end > start && Character.isHighSurrogate(s[end - 1]) && Character.isLowSurrogate(s[end])) end--
                if (end <= start) end = idealEnd
                chunks.add(s.substring(start, end))
                start = end
            }
            return chunks
        }
    }
}
