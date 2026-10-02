package com.peaceantz.stagescope.phone.google

import com.peaceantz.stagescope.shared.show.EmailAddress
import com.peaceantz.stagescope.shared.show.EmailValidator
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * Builds a properly encoded RFC 5322 / MIME message for the Gmail API's `raw` field: ASCII-safe
 * headers (RFC 2047 encoded words for non-ASCII), a UTF-8 base64 body, CRLF line endings, and
 * strict refusal of any header value containing CR or LF (header injection).
 */
object MimeBuilder {

    class InvalidMessage(message: String) : IllegalArgumentException(message)

    fun build(
        from: String,
        to: List<EmailAddress>,
        cc: List<EmailAddress>,
        bcc: List<EmailAddress>,
        subject: String,
        body: String,
        nowUtc: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC),
        messageIdDomain: String = "stagescope.local",
    ): String {
        if (!EmailValidator.isValid(from)) throw InvalidMessage("The sender address isn't valid.")
        if (to.isEmpty()) throw InvalidMessage("There are no recipients.")
        (to + cc + bcc).forEach { if (!EmailValidator.isValid(it.address)) throw InvalidMessage("'${it.address}' isn't a valid email address.") }
        listOf(subject, from).forEach { noLineBreaks(it, "header") }

        val headers = buildList {
            add("From: $from")
            add("To: ${addresses(to)}")
            if (cc.isNotEmpty()) add("Cc: ${addresses(cc)}")
            if (bcc.isNotEmpty()) add("Bcc: ${addresses(bcc)}")
            add("Subject: ${encodeHeaderText(subject)}")
            add("Date: ${DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US).format(nowUtc.withZoneSameInstant(ZoneOffset.UTC))}")
            add("Message-ID: <${UUID.randomUUID()}@$messageIdDomain>")
            add("MIME-Version: 1.0")
            add("Content-Type: text/plain; charset=\"UTF-8\"")
            add("Content-Transfer-Encoding: base64")
        }
        val encodedBody = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(body.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
        return headers.joinToString("\r\n") + "\r\n\r\n" + encodedBody + "\r\n"
    }

    /** The Gmail API wants the whole message base64url-encoded. */
    fun toGmailRaw(message: String): String = Base64.getUrlEncoder().encodeToString(message.toByteArray(Charsets.UTF_8))

    private fun addresses(list: List<EmailAddress>): String = list.joinToString(", ") { a ->
        noLineBreaks(a.address, "address")
        val name = a.name?.trim().orEmpty()
        if (name.isEmpty()) a.address else "${encodeDisplayName(name)} <${a.address}>"
    }

    private fun encodeDisplayName(name: String): String {
        noLineBreaks(name, "name")
        return if (name.all { it.code in 32..126 && it != '"' && it != '\\' && it != '<' && it != '>' && it != ',' && it != '@' && it != ';' && it != ':' }) name
        else encodeWords(name)
    }

    private fun encodeHeaderText(text: String): String {
        noLineBreaks(text, "subject")
        return if (text.all { it.code in 32..126 }) text else encodeWords(text)
    }

    /** RFC 2047 encoded-words, split on character boundaries so each word stays within 75 chars. */
    private fun encodeWords(text: String): String {
        val words = ArrayList<String>()
        var current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                words += "=?UTF-8?B?" + Base64.getEncoder().encodeToString(current.toString().toByteArray(Charsets.UTF_8)) + "?="
                current = StringBuilder()
            }
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chars = String(Character.toChars(cp))
            if (current.toString().toByteArray(Charsets.UTF_8).size + chars.toByteArray(Charsets.UTF_8).size > 42) flush()
            current.append(chars)
            i += Character.charCount(cp)
        }
        flush()
        return words.joinToString("\r\n ")
    }

    private fun noLineBreaks(value: String, what: String) {
        if (value.any { it == '\r' || it == '\n' || it == '\u0000' }) throw InvalidMessage("A $what contained a line break.")
    }
}
