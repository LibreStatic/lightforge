package com.librestatic.lightforge.feature.viewer.textselect

/** Actionable entity found inside selected text. */
internal sealed interface SmartEntity {
    val value: String

    data class Link(override val value: String) : SmartEntity
    data class Email(override val value: String) : SmartEntity
    data class Phone(override val value: String) : SmartEntity
    data class Address(override val value: String) : SmartEntity
}

/**
 * Lightweight, locale-agnostic entity detection for the selection toolbar. Pure Kotlin so it can be
 * unit tested without android.util.Patterns; it only needs to be good enough to offer a shortcut.
 */
internal object SmartEntities {
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val link = Regex(
        """(?i)\b(?:https?://|www\.)[^\s<>"]+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|org|net|io|dev|app|edu|gov|ar|es|fr|it|pt|br|de|uk|co|info)(?:/[^\s<>"]*)?\b""",
    )
    private val phone = Regex("""(?<![\w.])\+?\d[\d\s().-]{6,}\d(?![\w.])""")
    private val streetWords =
        "street|st\\.|avenue|ave\\.?|road|rd\\.|boulevard|blvd|lane|drive|calle|avenida|av\\.|rue|rua|via|viale|piazza|straße|strasse|platz"
    private val address = Regex(
        """(?i)(?:\b\d{1,5}\s+(?:[\p{L}.'-]+\s+){0,4}(?:$streetWords)\b|\b(?:$streetWords)\s+[\p{L}.'-]+(?:\s+[\p{L}.'-]+){0,3}\s+\d{1,5}\b)""",
    )

    fun detect(text: String, limit: Int = 3): List<SmartEntity> {
        val found = linkedMapOf<String, SmartEntity>()
        val emails = email.findAll(text).map { it.value }.toList()
        emails.forEach { found.putIfAbsent("e:$it", SmartEntity.Email(it)) }
        link.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':', ')') }
            .filter { candidate -> emails.none { it.contains(candidate) } }
            .forEach { found.putIfAbsent("l:$it", SmartEntity.Link(it)) }
        phone.findAll(text)
            .map { it.value.trim() }
            .filter { candidate -> candidate.count(Char::isDigit) in 7..15 }
            .forEach { found.putIfAbsent("p:$it", SmartEntity.Phone(it)) }
        address.find(text)?.let { found.putIfAbsent("a", SmartEntity.Address(it.value.trim())) }
        return found.values.take(limit)
    }
}
