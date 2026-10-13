package dev.wonderkast.wondertranslate

/**
 * Minimal, fail-closed protection layer for Discord markup. This does not parse
 * Discord's complete Markdown grammar and intentionally leaves unsupported rich
 * constructs in the original chat rendering instead of rewriting its spans.
 *
 * Protected tokens are never sent to the translation service: only opaque
 * markers (or separate natural-language segments in fallback mode) leave the app.
 */
import java.util.regex.Pattern

internal object ProtectedMessage {
    data class Piece(val value: String, val protected: Boolean)
    data class Plan(val source: String, val pieces: List<Piece>) {
        val structured: Boolean get() = pieces.any { it.protected }
        val naturalParts: Int get() = pieces.count { !it.protected && it.value.any(Char::isLetter) }
        private fun marker(index: Int) = "⟦WT${index}X⟧"
        fun masked(): String {
            val out = StringBuilder()
            var index = 0
            for (piece in pieces) out.append(if (piece.protected) marker(index++) else piece.value)
            return out.toString()
        }
        // Requires exactly one occurrence of every marker, in its original order.
        // A translator may reorder/remove tokens; reject rather than corrupt links.
        fun restore(translated: String): String? {
            val protectedPieces = pieces.filter { it.protected }
            if (protectedPieces.isEmpty()) return translated
            if (source.contains("⟦WT")) return null
            var searchFrom = 0
            var checkIndex = 0
            while (checkIndex < protectedPieces.size) {
                val key = marker(checkIndex)
                val found = translated.indexOf(key, searchFrom)
                if (found < 0 || translated.indexOf(key, found + key.length) >= 0) return null
                searchFrom = found + key.length
                checkIndex++
            }
            val seen = ArrayList<String>()
            val markerMatcher = markerPattern.matcher(translated)
            while (markerMatcher.find()) seen.add(markerMatcher.group())
            // Avoid IntRange/IntIterator here. Discord's embedded, obfuscated
            // Kotlin runtime may return an Iterator that is NOT IntIterator;
            // `protectedPieces.indices.map { ... }` crashed in R21 at runtime.
            if (seen.size != protectedPieces.size) return null
            var verify = 0
            while (verify < protectedPieces.size) {
                if (seen[verify] != marker(verify)) return null
                verify++
            }
            val out = StringBuilder()
            var offset = 0
            var position = 0
            while (position < protectedPieces.size) {
                val key = marker(position)
                val pos = translated.indexOf(key, offset)
                if (pos < offset) return null
                out.append(translated, offset, pos).append(protectedPieces[position].value)
                offset = pos + key.length
                position++
            }
            out.append(translated, offset, translated.length)
            return out.toString()
        }
    }

    private val markerPattern = Pattern.compile("⟦WT[0-9]+X⟧")
    private val mention = Pattern.compile("<(?:@!?[0-9]+|@&[0-9]+|#[0-9]+|t:[0-9]+(?::[A-Za-z])?|a?:[A-Za-z0-9_]{1,64}:[0-9]+|/[A-Za-z0-9 _-]{1,70}:[0-9]+)>")
    private val url = Pattern.compile("(?:https?://|ftp://|www\\.|discord\\.gg/|mailto:)[^\\s<>`]+", Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
    private val listHead = Pattern.compile(" {0,3}(?:#{1,6}|[-+*]|[0-9]{1,3}\\.|>) +")

    fun parse(source: String): Plan {
        if (source.isEmpty()) return Plan(source, emptyList())
        val parts = mutableListOf<Piece>()
        // Accumulate natural text in O(n), avoiding quadratic String concat for
        // long messages (parsing also happens on the UI redraw path).
        val natural = StringBuilder()
        fun flushNatural() {
            if (natural.isNotEmpty()) {
                parts.add(Piece(natural.toString(), false))
                natural.setLength(0)
            }
        }
        fun add(value: String, protect: Boolean) {
            if (value.isEmpty()) return
            if (!protect) { natural.append(value); return }
            flushNatural()
            val old = parts.lastOrNull()
            if (old?.protected == true) parts[parts.lastIndex] = Piece(old.value + value, true)
            else parts.add(Piece(value, true))
        }
        var i = 0
        while (i < source.length) {
            val isLineStart = i == 0 || source[i - 1] == '\n'
            if (isLineStart) {
                var q = i
                while (q < source.length && q - i < 3 && source[q] == ' ') q++
                if (q < source.length && (source[q] == '`' || source[q] == '~')) {
                    val ch = source[q]
                    var e = q
                    while (e < source.length && source[e] == ch) e++
                    if (e - q >= 3) {
                        var line = source.indexOf('\n', e).let { if (it < 0) source.length else it + 1 }
                        var ending = source.length
                        while (line < source.length) {
                            var t = line
                            while (t < source.length && t - line < 3 && source[t] == ' ') t++
                            val startFence = t
                            while (t < source.length && source[t] == ch) t++
                            val next = source.indexOf('\n', t).let { if (it < 0) source.length else it + 1 }
                            val tail = source.substring(t, if (next > t && source[next - 1] == '\n') next - 1 else next).trimEnd('\r')
                            if (t - startFence >= e - q && tail.isBlank()) { ending = next; break }
                            line = next
                        }
                        add(source.substring(i, ending), true)
                        i = ending
                        continue
                    }
                }
                val h = listHead.matcher(source).region(i, source.length)
                if (h.lookingAt()) {
                    val token = h.group()
                    add(token, true)
                    i += token.length
                    continue
                }
            }
            // Parse balanced parentheses in Markdown link targets; keep URL bytes
            // local and send only the human-readable label for translation.
            if (source[i] == '[') {
                var close = -1
                var cursor = i + 1
                while (cursor < source.length - 1 && cursor - i <= 251) {
                    if (source[cursor] == ']' && source[cursor + 1] == '(') { close = cursor; break }
                    cursor++
                }
                if (close > i + 1 &&
                    !source.substring(i, close).contains('\n')) {
                    var depth = 1
                    var pos = close + 2
                    while (pos < source.length && pos - close <= 1100 && depth > 0) {
                        if (source[pos] == '(') depth++
                        if (source[pos] == ')') depth--
                        pos++
                    }
                    if (depth == 0) {
                        val destination = source.substring(close + 2, pos - 1)
                        if (url.matcher(destination).matches()) {
                            add("[", true)
                            add(source.substring(i + 1, close), false)
                            add("](", true)
                            add(destination, true)
                            add(")", true)
                            i = pos
                            continue
                        }
                    }
                }
            }
            // Triple/backtick fences not at line start count as inline code.
            if (source[i] == '`') {
                var t = i
                while (t < source.length && source[t] == '`') t++
                val marker = source.substring(i, t)
                val end = source.indexOf(marker, t)
                val bound = if (end >= 0) end + marker.length else source.indexOf('\n', t).let { if (it < 0) source.length else it }
                add(source.substring(i, bound), true)
                i = bound
                continue
            }
            val m = mention.matcher(source).region(i, source.length)
            if (m.lookingAt()) {
                val token = m.group()
                add(token, true); i += token.length; continue
            }
            val u = url.matcher(source).region(i, source.length)
            if (u.lookingAt()) {
                var safe = u.group().trimEnd('.', ',', '!', '?', ';', ':')
                // A closing parenthesis within a URL is legitimate when balanced.
                while (safe.endsWith(')') && safe.count { it == ')' } > safe.count { it == '(' }) safe = safe.dropLast(1)
                while (safe.endsWith(']') && safe.count { it == ']' } > safe.count { it == '[' }) safe = safe.dropLast(1)
                while (safe.endsWith('}') && safe.count { it == '}' } > safe.count { it == '{' }) safe = safe.dropLast(1)
                if (safe.isNotEmpty()) { add(safe, true); i += safe.length; continue }
            }
            if (source.startsWith("@everyone", i) || source.startsWith("@here", i)) {
                val token = if (source.startsWith("@everyone", i)) "@everyone" else "@here"
                val after = i + token.length
                if (after == source.length || !source[after].isLetterOrDigit()) {
                    add(token, true); i = after; continue
                }
            }
            if (source[i] == '\\' && i + 1 < source.length) {
                add(source.substring(i, i + 2), true); i += 2; continue
            }
            val marker = listOf("**", "__", "~~", "||", "*", "_").firstOrNull { source.startsWith(it, i) &&
                (it != "_" && it != "*" || source.indexOf(it, i + it.length) >= 0) }
            if (marker != null) { add(marker, true); i += marker.length; continue }
            add(source.substring(i, i + 1), false)
            i++
        }
        flushNatural()
        return Plan(source, parts)
    }
}
