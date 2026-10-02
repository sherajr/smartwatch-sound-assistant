package com.peaceantz.stagescope.shared.show

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientResolverTest {
    private val library = ShowLibrary(
        contacts = listOf(
            Contact("c1", "Dana Whitfield", "dana@theatre.example", verified = true, role = "Production manager"),
            Contact("c2", "Dan Okafor", "dan@theatre.example", verified = true),
            Contact("c3", "Priya Shah", "priya@theatre.example", verified = true, role = "Stage manager"),
            Contact("c4", "Sam Unverified", "sam@theatre.example", verified = false),
        ),
        groups = listOf(RecipientGroup("g1", "Production team", listOf("c1", "c3"))),
    )

    @Test
    fun `a unique name resolves to the configured address`() {
        val r = RecipientResolver.resolve(listOf("Priya"), library, "email Priya the report")
        assertTrue(RecipientResolver.allResolved(r))
        assertEquals("priya@theatre.example", RecipientResolver.resolvedAddresses(r).single().address)
    }

    @Test
    fun `an ambiguous first name shows candidates instead of guessing`() {
        val r = RecipientResolver.resolve(listOf("Dan"), library, "")
        val first = r.single()
        // 'Dan' is an exact word of Dan Okafor only; 'Dana' is a different word.
        assertTrue(first is RecipientResolution.Resolved)
        val ambiguous = RecipientResolver.resolve(listOf("production manager"), library.copy(
            contacts = library.contacts + Contact("c5", "Pat Manager", "pat@theatre.example", true, role = "Production manager"),
        ), "")
        assertTrue(ambiguous.single() is RecipientResolution.Ambiguous)
        assertEquals(2, (ambiguous.single() as RecipientResolution.Ambiguous).candidates.size)
    }

    @Test
    fun `an unknown name is unresolved and is never turned into an address`() {
        val r = RecipientResolver.resolve(listOf("Jordan"), library, "email Jordan")
        assertTrue(r.single() is RecipientResolution.Unresolved)
        assertTrue(RecipientResolver.resolvedAddresses(r).isEmpty())
    }

    @Test
    fun `a group expands to its verified members`() {
        val r = RecipientResolver.resolve(listOf("Production team"), library, "")
        assertEquals(setOf("dana@theatre.example", "priya@theatre.example"), RecipientResolver.resolvedAddresses(r).map { it.address }.toSet())
        assertTrue(r.filterIsInstance<RecipientResolution.Resolved>().all { it.viaGroup == "Production team" })
    }

    @Test
    fun `an unverified contact is flagged and not used automatically`() {
        val r = RecipientResolver.resolve(listOf("Sam"), library, "")
        assertTrue(r.single() is RecipientResolution.Unverified)
        assertFalse(RecipientResolver.allResolved(r))
    }

    @Test
    fun `a literal address is accepted only if it is a contact or was in the person's own words`() {
        val invented = RecipientResolver.resolve(listOf("lighting@venue.example"), library, "send it to the lighting department")
        assertTrue(invented.single() is RecipientResolution.Invalid)
        val said = RecipientResolver.resolve(listOf("lighting@venue.example"), library, "send it to lighting@venue.example please")
        assertTrue(said.single() is RecipientResolution.Resolved)
        val contact = RecipientResolver.resolve(listOf("DANA@theatre.example"), library, "")
        assertEquals("Dana Whitfield", (contact.single() as RecipientResolution.Resolved).address.name)
    }

    @Test
    fun `malformed or header-injecting addresses are rejected`() {
        assertTrue(RecipientResolver.resolve(listOf("not an address@"), library, "not an address@").single() is RecipientResolution.Invalid)
        assertFalse(EmailValidator.isValid("a@b.com\r\nBcc: evil@example.com"))
        assertFalse(EmailValidator.isValid("two@@example.com"))
        assertTrue(EmailValidator.isValid("first.last+tag@sub.example.co.uk"))
    }
}
