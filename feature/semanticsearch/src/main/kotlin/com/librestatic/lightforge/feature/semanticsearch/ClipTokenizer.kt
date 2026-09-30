package com.librestatic.lightforge.feature.semanticsearch

import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import android.text.Html

internal class ClipTokenizer(vocabularyFile: File, mergesFile: File) {
    private val vocabulary = JSONObject(vocabularyFile.readText()).let { json ->
        buildMap { json.keys().forEach { token -> put(token, json.getInt(token)) } }
    }
    private val merges = mergesFile.readLines().asSequence()
        .filterNot { it.isBlank() || it.startsWith("#") }
        .mapIndexed { rank, line -> line.trim().split(Regex("\\s+")).let { it[0] to it[1] } to rank }
        .toMap()
    private val byteEncoder = byteEncoder()
    private val cache = object : LinkedHashMap<String, List<String>>(512, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 512
    }
    private val startToken = requireNotNull(vocabulary["<|startoftext|>"])
    private val endToken = requireNotNull(vocabulary["<|endoftext|>"])

    fun encode(text: String, contextLength: Int = 77): IntArray {
        require(contextLength in 2..77)
        val ids = mutableListOf(startToken)
        TokenPattern.findAll(clean(text).lowercase(Locale.ROOT)).forEach { match ->
            val encoded = match.value.toByteArray(Charsets.UTF_8).joinToString("") { byteEncoder[it.toInt() and 0xff].toString() }
            bpe(encoded).forEach { token -> vocabulary[token]?.let(ids::add) }
        }
        ids += endToken
        if (ids.size > contextLength) {
            ids.subList(contextLength, ids.size).clear()
            ids[contextLength - 1] = endToken
        }
        return IntArray(contextLength) { ids.getOrElse(it) { 0 } }
    }

    private fun bpe(token: String): List<String> = cache.getOrPut(token) {
        if (token == "<|startoftext|>" || token == "<|endoftext|>") return@getOrPut listOf(token)
        var word = token.mapIndexed { index, char -> if (index == token.lastIndex) "$char</w>" else char.toString() }
        while (word.size > 1) {
            val candidate = word.zipWithNext().minByOrNull { merges[it] ?: Int.MAX_VALUE } ?: break
            if (merges[candidate] == null) break
            val next = mutableListOf<String>()
            var index = 0
            while (index < word.size) {
                if (index < word.lastIndex && word[index] == candidate.first && word[index + 1] == candidate.second) {
                    next += candidate.first + candidate.second
                    index += 2
                } else {
                    next += word[index++]
                }
            }
            word = next
        }
        word
    }

    private companion object {
        val TokenPattern = Regex("<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+", RegexOption.IGNORE_CASE)
        private val Entity = Regex("&(?:#[xX][0-9a-fA-F]+|#[0-9]+|[A-Za-z][A-Za-z0-9]+);")
        private fun clean(raw: String): String {
            // CLIP's clean/whitespace/uncurl normalization; decode entities only, never markup.
            require(raw.length <= 16_384) { "Semantic query is too long" }
            var text = Normalizer.normalize(raw, Normalizer.Form.NFC)
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201c', '"').replace('\u201d', '"')
            repeat(2) { text = Entity.replace(text) { Html.fromHtml(it.value, Html.FROM_HTML_MODE_LEGACY).toString() } }
            return text.replace(Regex("[\\s\\p{Z}]+"), " ").trim()
        }

        fun byteEncoder(): Map<Int, Char> {
            val bytes = ((33..126) + (161..172) + (174..255)).toMutableList()
            val chars = bytes.toMutableList()
            var extra = 0
            repeat(256) { value ->
                if (value !in bytes) {
                    bytes += value
                    chars += 256 + extra++
                }
            }
            return bytes.zip(chars.map(Int::toChar)).toMap()
        }
    }
}

