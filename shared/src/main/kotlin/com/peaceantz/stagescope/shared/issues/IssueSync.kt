package com.peaceantz.stagescope.shared.issues

import kotlinx.serialization.Serializable

/**
 * Per-issue version vector keyed by replica id (watch and phone each have one). Ordering between
 * two edits is decided by *causality* -- did the writer already see the other edit? -- never by a
 * wall clock, so devices with disagreeing clocks still converge on the same result.
 */
@Serializable
data class VersionVector(val counters: Map<String, Long> = emptyMap()) {
    fun increment(replica: String): VersionVector =
        VersionVector(counters + (replica to ((counters[replica] ?: 0L) + 1L)))

    fun merge(other: VersionVector): VersionVector {
        val keys = counters.keys + other.counters.keys
        return VersionVector(keys.associateWith { maxOf(counters[it] ?: 0L, other.counters[it] ?: 0L) })
    }

    /** True when this has seen everything [other] has (>= on every replica), including when equal. */
    fun dominates(other: VersionVector): Boolean =
        other.counters.all { (replica, n) -> (counters[replica] ?: 0L) >= n }

    fun strictlyDominates(other: VersionVector): Boolean = dominates(other) && !other.dominates(this)

    val total: Long get() = counters.values.sum()
}

/** One candidate value plus the causal history it was written with. [by] is the writing replica. */
@Serializable
data class Sibling<T>(val value: T, val vv: VersionVector, val by: String)

/**
 * Multi-value register: concurrent edits are *kept*, not overwritten. A new local edit's version
 * vector dominates every sibling (the writer had seen them all), so a deliberate edit resolves a
 * conflict; two devices editing the same field offline produce two siblings that both survive a
 * merge until a person picks. [merge] is commutative, associative and idempotent, which is what
 * makes duplicate deliveries and any delivery order safe.
 */
@Serializable
data class MVRegister<T>(val siblings: List<Sibling<T>>) {

    val hasConflict: Boolean get() = siblings.size > 1

    /** The deterministic display value: highest causal depth, then replica id, then value text. */
    val resolved: Sibling<T> get() = siblings.first()

    val value: T get() = resolved.value

    fun merge(other: MVRegister<T>): MVRegister<T> {
        val unique = (siblings + other.siblings).distinctBy { Triple(it.vv, it.by, it.value) }
        val kept = unique.filter { s -> unique.none { o -> o !== s && o.vv.strictlyDominates(s.vv) } }
        return MVRegister(kept.sortedWith(canonicalOrder()))
    }

    fun alternatives(): List<T> = siblings.drop(1).map { it.value }

    companion object {
        fun <T> of(value: T, vv: VersionVector, by: String) = MVRegister(listOf(Sibling(value, vv, by)))

        private fun <T> canonicalOrder(): Comparator<Sibling<T>> =
            compareByDescending<Sibling<T>> { it.vv.total }
                .thenBy { it.by }
                .thenBy { it.value.toString() }
                .thenBy { it.vv.counters.toSortedMap().toString() }
    }
}

@Serializable
enum class IssueStatus { OPEN, RESOLVED, REOPENED }

/** "Seems resolved" is kept as such -- it must not silently become a firm "resolved". */
@Serializable
enum class ResolutionCertainty { CONFIRMED, TENTATIVE }

@Serializable
enum class IssueSeverity { LOW, MEDIUM, HIGH, CRITICAL }

@Serializable
enum class IssueField {
    PRODUCTION, PERFORMANCE, OCCURRED_AT, DESCRIPTION, EQUIPMENT, CHANNEL, ATTEMPTED_FIX, SEVERITY,
    STATUS, CERTAINTY, RESOLUTION_NOTE, EVIDENCE, AI_WORDING, DELETED,
}
