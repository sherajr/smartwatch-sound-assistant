package com.peaceantz.stagescope.shared.issues

/** What a dictated issue says, split into the fields an issue record carries. */
data class ParsedIssue(
    val observation: String,
    val description: String,
    val equipment: String?,
    val channel: String?,
    val attemptedFix: String?,
    val status: IssueStatus,
    val certainty: ResolutionCertainty,
    val resolutionNote: String?,
    val severity: IssueSeverity?,
)

/**
 * A deliberately conservative, offline parser used when no AI is reachable (and as a safe fallback
 * when the AI's own structuring is rejected). It only splits what the person actually said: the
 * full transcript is always kept verbatim as the original observation, nothing is invented, and an
 * unclear sentence stays in the description rather than being guessed into a field.
 */
object IssueTextParser {
    private val LEAD_IN = Regex("^\\s*(?:please\\s+)?(?:log|record|add|note)\\s+(?:an?\\s+|the\\s+)?(?:new\\s+)?issue\\s*[:,\\-–]?\\s*", RegexOption.IGNORE_CASE)
    private val SENTENCE_SPLIT = Regex("(?<=[.!?;])\\s+|\\n+")
    private val FIX_START = Regex(
        "^(?:i\\s+|we\\s+|then\\s+|and\\s+)*(?:swapped|replaced|changed|reset|re-?seated|reseated|rebooted|restarted|reconnected|re-?patched|repatched|" +
            "muted|unmuted|moved|re-?taped|retaped|taped|re-?routed|rerouted|cleaned|tightened|re-?tuned|retuned|swapped out|tried|checked|put in|put on|" +
            "new batteries|fresh batteries|gain(?:ed)? down|rolled off|eq'?d|cut)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val TENTATIVE_RESOLVED = Regex("\\b(?:seems|seemed|appears|looks|looked|think|thinks|hopefully|probably)\\s+(?:to\\s+be\\s+)?(?:resolved|fixed|ok|okay|good|sorted|better)\\b", RegexOption.IGNORE_CASE)
    private val FIRM_RESOLVED = Regex("\\b(?:resolved|fixed|sorted|all good|no longer|no more)\\b", RegexOption.IGNORE_CASE)
    private val STILL_OPEN = Regex("\\b(?:still|persists|persisted|not fixed|unresolved|didn'?t help|did not help|ongoing|keeps)\\b", RegexOption.IGNORE_CASE)
    private val EQUIPMENT = Regex("\\b(mic(?:rophone)?|lav(?:alier)?|handheld|pack|belt ?pack|transmitter|receiver|wedge|monitor|speaker|di|iem)\\s*(?:no\\.?|number|#)?\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
    private val CHANNEL = Regex("\\b(?:channel|ch\\.?)\\s*(?:no\\.?|number|#)?\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE)

    fun parse(rawText: String): ParsedIssue {
        val observation = rawText.trim()
        val text = LEAD_IN.replaceFirst(observation, "").trim().ifEmpty { observation }
        val sentences = text.split(SENTENCE_SPLIT).map { it.trim().trim('.', ';', '!', ' ') }.filter { it.isNotEmpty() }

        var status = IssueStatus.OPEN
        var certainty = ResolutionCertainty.CONFIRMED
        var resolutionNote: String? = null
        val descriptionParts = mutableListOf<String>()
        val fixParts = mutableListOf<String>()

        for (sentence in sentences) {
            val tentative = TENTATIVE_RESOLVED.find(sentence)
            val firm = if (tentative == null) FIRM_RESOLVED.find(sentence) else null
            val stillOpen = STILL_OPEN.containsMatchIn(sentence)
            when {
                tentative != null -> {
                    status = IssueStatus.RESOLVED
                    certainty = ResolutionCertainty.TENTATIVE
                    resolutionNote = tentative.value.trim()
                    val rest = sentence.replace(tentative.value, "").trim(' ', ',', ';', '-')
                    if (rest.isNotEmpty()) (if (FIX_START.containsMatchIn(rest)) fixParts else descriptionParts) += rest
                }
                firm != null && !stillOpen && FIX_START.containsMatchIn(sentence).not() && sentence.length <= 40 -> {
                    status = IssueStatus.RESOLVED
                    certainty = ResolutionCertainty.CONFIRMED
                    resolutionNote = sentence
                }
                FIX_START.containsMatchIn(sentence) -> {
                    fixParts += sentence
                    if (firm != null && !stillOpen) {
                        status = IssueStatus.RESOLVED
                        certainty = ResolutionCertainty.CONFIRMED
                        resolutionNote = firm.value
                    }
                }
                else -> descriptionParts += sentence
            }
        }

        val description = descriptionParts.joinToString(". ").ifBlank { text }.capitalized()
        val equipment = EQUIPMENT.find(text)?.let { m -> "${m.groupValues[1].replaceFirstChar { it.uppercase() }} ${m.groupValues[2]}" }
        val channel = CHANNEL.find(text)?.groupValues?.get(1)
        return ParsedIssue(
            observation = observation,
            description = description,
            equipment = equipment,
            channel = channel,
            attemptedFix = fixParts.joinToString(". ").takeIf { it.isNotBlank() }?.capitalized(),
            status = status,
            certainty = certainty,
            resolutionNote = resolutionNote,
            severity = severityOf(text),
        )
    }

    private fun severityOf(text: String): IssueSeverity? {
        val t = text.lowercase()
        return when {
            Regex("\\b(critical|show[- ]?stopper|emergency)\\b").containsMatchIn(t) -> IssueSeverity.CRITICAL
            Regex("\\b(urgent|high priority|major)\\b").containsMatchIn(t) -> IssueSeverity.HIGH
            Regex("\\b(medium priority)\\b").containsMatchIn(t) -> IssueSeverity.MEDIUM
            Regex("\\b(minor|low priority|cosmetic)\\b").containsMatchIn(t) -> IssueSeverity.LOW
            else -> null
        }
    }

    private fun String.capitalized(): String = replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }
}
