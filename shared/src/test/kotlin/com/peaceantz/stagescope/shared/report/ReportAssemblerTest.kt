package com.peaceantz.stagescope.shared.report

import com.peaceantz.stagescope.shared.issues.IssueFilter
import com.peaceantz.stagescope.shared.issues.IssueLedger
import com.peaceantz.stagescope.shared.issues.IssueLedgerState
import com.peaceantz.stagescope.shared.issues.IssueOps
import com.peaceantz.stagescope.shared.issues.ResolutionCertainty
import com.peaceantz.stagescope.shared.show.Performance
import com.peaceantz.stagescope.shared.show.Production
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportAssemblerTest {
    private val production = Production("p1", "Our Town", venue = "Grover's Corners Playhouse")
    private val performance = Performance("perf-1", "p1", "2026-03-14", "19:30", number = 12)

    private fun issues(vararg specs: Triple<String, String?, ResolutionCertainty?>) =
        specs.mapIndexed { i, (desc, fix, resolved) ->
            var s = IssueOps.create("i$i", "w", 1L + i, desc, performanceId = "perf-1", attemptedFix = fix, equipment = null)
            if (resolved != null) s = IssueOps.resolve(s, "w", resolved, null)
            s.view()
        }

    @Test
    fun `report uses only the observations and this performance's issues`() {
        val ev = ReportEvidence(
            production, performance,
            observations = listOf("Dialogue clarity was good"),
            issues = issues(Triple("Mic 12 crackled during the opening number", "Swapped the cable at intermission", ResolutionCertainty.TENTATIVE)),
        )
        val r = ReportAssembler.assemble(ev)
        assertTrue(r.body.contains("Dialogue clarity was good."))
        assertTrue(r.body.contains("Mic 12 crackled during the opening number"))
        assertTrue(r.body.contains("seems resolved (not confirmed)"))
        assertTrue(r.body.contains("Swapped the cable at intermission"))
        assertEquals(listOf("i0"), r.usedIssueIds)
        assertTrue(r.subject.startsWith("Our Town"))
    }

    @Test
    fun `empty sections are omitted by default and never invented`() {
        val ev = ReportEvidence(production, performance, observations = emptyList(), issues = emptyList())
        val r = ReportAssembler.assemble(ev)
        assertFalse(r.body.contains("Incidents"))
        assertFalse(r.body.contains("Unresolved issues"))
        assertFalse("no claim that the show went well", r.body.lowercase().contains("went well"))
    }

    @Test
    fun `marking empty sections says exactly what is known - none logged in StageScope`() {
        val ev = ReportEvidence(production, performance, observations = emptyList(), issues = emptyList())
        val r = ReportAssembler.assemble(ev, markEmpty = true)
        assertTrue(r.body.contains("None logged in StageScope."))
        assertFalse(r.body.contains("no incidents occurred"))
    }

    @Test
    fun `unresolved issues feed the unresolved and next-performance sections only`() {
        val ev = ReportEvidence(
            production, performance, emptyList(),
            issues(
                Triple("Wireless dropout on pack 4", null, null),
                Triple("Wedge ringing", "Notched 2k", ResolutionCertainty.CONFIRMED),
            ),
        )
        val body = ReportAssembler.assemble(ev).body
        val unresolved = body.substringAfter("Unresolved issues").substringBefore("Next-performance actions")
        assertTrue(unresolved.contains("Wireless dropout on pack 4"))
        assertFalse(unresolved.contains("Wedge ringing"))
        assertTrue(body.substringAfter("Next-performance actions").contains("Follow up: Wireless dropout on pack 4."))
    }

    @Test
    fun `guard flags channel numbers and addresses that nobody supplied`() {
        val facts = "Mic 12 crackled during the opening number. Swapped the cable. Change mic 12 to mic 14 please"
        val flagged = ReportGuard.unsupportedMentions("Mic 14 crackled; channel 7 also dropped; contact lights@x.example", facts)
        assertTrue(flagged.any { it.equals("channel 7", true) })
        assertTrue(flagged.contains("lights@x.example"))
        assertFalse("mic 14 was in the person's own edit", flagged.any { it.equals("Mic 14", true) })
    }

    @Test
    fun `ledger filtering feeds a report only the selected performance`() {
        var ledger = IssueLedgerState(replicaId = "w")
        ledger = IssueLedger.create(ledger, "o1", IssueOps.create("a", "w", 1, "Tonight issue", performanceId = "perf-1"))
        ledger = IssueLedger.create(ledger, "o2", IssueOps.create("b", "w", 2, "Last night issue", performanceId = "perf-0"))
        val tonight = IssueLedger.views(ledger, IssueFilter(performanceId = "perf-1"))
        val r = ReportAssembler.assemble(ReportEvidence(production, performance, emptyList(), tonight))
        assertTrue(r.body.contains("Tonight issue"))
        assertFalse(r.body.contains("Last night issue"))
    }
}
