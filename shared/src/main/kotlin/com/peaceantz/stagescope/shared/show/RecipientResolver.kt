package com.peaceantz.stagescope.shared.show

sealed interface RecipientResolution {
    /** An address StageScope may use: a verified configured contact, or one the person actually said/typed. */
    data class Resolved(val address: EmailAddress, val viaGroup: String? = null) : RecipientResolution
    data class Ambiguous(val spoken: String, val candidates: List<Contact>) : RecipientResolution
    data class Unresolved(val spoken: String) : RecipientResolution
    /** A configured contact whose address the owner never verified. */
    data class Unverified(val contact: Contact) : RecipientResolution
    data class Invalid(val value: String, val reason: String) : RecipientResolution
}

/**
 * Resolves who an email goes to from *configured* show contacts/groups only. A spoken first name is
 * never turned into an address by guesswork: a name must match exactly one verified contact, and a
 * literal address is accepted only if it is already a contact or appears verbatim in what the
 * person said or typed (so a model can't conjure one from nothing).
 */
object RecipientResolver {

    fun resolve(refs: List<String>, library: ShowLibrary, userSaid: String): List<RecipientResolution> =
        refs.flatMap { resolveOne(it.trim(), library, userSaid) }

    private fun resolveOne(ref: String, library: ShowLibrary, userSaid: String): List<RecipientResolution> {
        if (ref.isEmpty()) return emptyList()

        library.contacts.firstOrNull { it.id == ref }?.let { return listOf(forContact(it, null)) }

        library.groups.firstOrNull { it.id == ref || it.name.equals(ref, ignoreCase = true) }?.let { g ->
            val members = g.contactIds.mapNotNull { id -> library.contacts.firstOrNull { it.id == id } }
            return if (members.isEmpty()) listOf(RecipientResolution.Unresolved(ref)) else members.map { forContact(it, g.name) }
        }

        if ('@' in ref) {
            val address = ref.removePrefix("<").removeSuffix(">").trim()
            if (!EmailValidator.isValid(address)) return listOf(RecipientResolution.Invalid(ref, "That doesn't look like a valid email address."))
            val known = library.contacts.firstOrNull { EmailValidator.normalize(it.email) == EmailValidator.normalize(address) }
            if (known != null) return listOf(forContact(known, null))
            return if (userSaid.contains(address, ignoreCase = true)) {
                listOf(RecipientResolution.Resolved(EmailAddress(address)))
            } else {
                listOf(RecipientResolution.Invalid(ref, "That address isn't one of your contacts and wasn't in your message."))
            }
        }

        val lower = ref.lowercase()
        val exact = library.contacts.filter { it.name.equals(ref, ignoreCase = true) }
        val candidates = when {
            exact.isNotEmpty() -> exact
            else -> library.contacts.filter { c ->
                c.name.lowercase().split(Regex("\\s+")).any { it == lower } || c.role?.equals(ref, ignoreCase = true) == true
            }
        }
        return when {
            candidates.isEmpty() -> listOf(RecipientResolution.Unresolved(ref))
            candidates.size == 1 -> listOf(forContact(candidates.first(), null))
            else -> listOf(RecipientResolution.Ambiguous(ref, candidates))
        }
    }

    private fun forContact(contact: Contact, group: String?): RecipientResolution =
        if (contact.verified && EmailValidator.isValid(contact.email)) {
            RecipientResolution.Resolved(EmailAddress(contact.email.trim(), contact.name), group)
        } else {
            RecipientResolution.Unverified(contact)
        }

    /** True when every reference resolved cleanly (nothing ambiguous, unknown, invalid or unverified). */
    fun allResolved(results: List<RecipientResolution>): Boolean = results.isNotEmpty() && results.all { it is RecipientResolution.Resolved }

    fun resolvedAddresses(results: List<RecipientResolution>): List<EmailAddress> =
        results.filterIsInstance<RecipientResolution.Resolved>().map { it.address }
            .distinctBy { EmailValidator.normalize(it.address) }
}
