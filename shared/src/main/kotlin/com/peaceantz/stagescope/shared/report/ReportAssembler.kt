package com.peaceantz.stagescope.shared.report

import com.peaceantz.stagescope.shared.issues.IssueStatus
import com.peaceantz.stagescope.shared.issues.IssueView
import com.peaceantz.stagescope.shared.issues.ResolutionCertainty
import com.peaceantz.stagescope.shared.show.Performance
import com.peaceantz.stagescope.shared.show.Production

/**
 * The facts a performance report may use -- and nothing else. No measurement is in here on
 * purpose: StageScope's readings do not establish that a show "went well" or that nothing went
 * wrong, so they are never turned into report statements.
 */
data class ReportEvidence(
    val production: Production,
    val performance: Performance,
    /** The person's own dictated/typed observations, verbatim. */
    val observations: List<String>,
    /** This performance's issues only (deleted ones excluded by the caller). */
    val issues: List<IssueView>,
    /** Extra instruction facts the person gave, e.g. "mention dialogue clarity was good". */
    val requestedInclusions: List<String> = emptyList(),
)

data class AssembledReport(val subject: String, val body: String, val usedIssueIds: List<String>)

/**
 * A deterministic baseline report (also the no-AI fallback). Sections come from the production's
 * configured list; empty sections are omitted, or marked accurately ("none logged in StageScope")
 * when [markEmpty] is set -- never filled with invented observations, names, channels or attendees.
 */
object ReportAssembler {

    fun assemble(evidence: ReportEvidence, markEmpty: Boolean = false): AssembledReport {
        val p = evidence.production
        val perf = evidence.performance
        val open = evidence.issues.filter { it.status != IssueStatus.RESOLVED }
        val subject = "${p.name} – ${perf.displayName().substringBefore(" · ")} (${perf.localDate}) sound report"

        val body = buildString {
            appendLine(p.writing.greeting)
            appendLine()
            appendLine("Sound report for ${p.name}${p.venue?.let { " at $it" } ?: ""}, ${perf.displayName()}.")
            for (section in p.reportSections.filter { it.enabled }) {
                val lines: List<String> = when (section.id) {
                    "overall" -> evidence.observations.map { it.trim().trimEnd('.') + "." }
                    "incidents" -> evidence.issues.map(::issueLine)
                    "fixes" -> evidence.issues.mapNotNull { v -> v.attemptedFix?.let { "${v.description.trimEnd('.')}: $it" } }
                    "unresolved" -> open.map(::issueLine)
                    "next" -> open.map { "Follow up: ${it.description.trimEnd('.')}." }
                    else -> emptyList()
                }
                if (lines.isEmpty() && !markEmpty) continue
                appendLine()
                appendLine(section.title)
                if (lines.isEmpty()) appendLine("- None ${if (section.id == "overall") "recorded" else "logged in StageScope"}.")
                else lines.forEach { appendLine("- $it") }
            }
            if (evidence.requestedInclusions.isNotEmpty() && evidence.observations.isEmpty()) {
                // Requested inclusions are the person's own words; surface them rather than drop them.
                appendLine()
                appendLine("Notes")
                evidence.requestedInclusions.forEach { appendLine("- ${it.trim().trimEnd('.')}.") }
            }
            appendLine()
            append(p.writing.signature?.takeIf { it.isNotBlank() } ?: "Thanks")
        }.trim()

        return AssembledReport(subject.take(200), body, evidence.issues.map { it.id })
    }

    private fun issueLine(v: IssueView): String = buildString {
        append(v.description.trimEnd('.'))
        v.equipment?.let { append(" (").append(it).append(')') }
        v.channel?.let { append(" [channel ").append(it).append(']') }
        v.attemptedFix?.let { append(" — fix tried: ").append(it.trimEnd('.')) }
        append(" — ")
        append(
            when {
                v.status == IssueStatus.RESOLVED && v.certainty == ResolutionCertainty.TENTATIVE -> "seems resolved (not confirmed)"
                v.status == IssueStatus.RESOLVED -> "resolved"
                v.status == IssueStatus.REOPENED -> "reopened"
                else -> "open"
            },
        )
        append('.')
    }
}

/**
 * Cheap provenance check for model-written email text: flag equipment/channel numbers, times and
 * addresses the body mentions that appear nowhere in the facts the person supplied. It is a
 * warning on the review card, not a blocker -- a person can still send, but sees it first.
 */
object ReportGuard {
    private val EQUIPMENT = Regex("\\b(?:mic(?:rophone)?|channel|ch\\.?|pack|dca|vca|lav|handheld|wedge|monitor)\\s*#?\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    fun unsupportedMentions(draftText: String, allowedFactsText: String): List<String> {
        val facts = allowedFactsText.lowercase()
        val out = linkedSetOf<String>()
        for (m in EQUIPMENT.findAll(draftText)) {
            val phrase = m.value.lowercase().replace(Regex("\\s+"), " ")
            val number = m.groupValues[1]
            val supported = facts.contains(phrase) || Regex("\\b(?:mic(?:rophone)?|channel|ch\\.?|pack|dca|vca|lav|handheld)\\s*#?\\s*$number\\b").containsMatchIn(facts)
            if (!supported) out += m.value.trim()
        }
        for (m in EMAIL.findAll(draftText)) {
            if (!facts.contains(m.value.lowercase())) out += m.value
        }
        return out.toList()
    }
}
