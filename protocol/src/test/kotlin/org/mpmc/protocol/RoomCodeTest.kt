package org.mpmc.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoomCodeTest {
    @Test
    fun `generates eight valid characters`() {
        val codes = (1..200).map { RoomCode.generate() }
        for (code in codes) {
            assertEquals(8, code.length)
            assertTrue(RoomCode.isValid(code))
            assertTrue(code.all { it in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" })
        }
        assertNotEquals(1, codes.toSet().size)
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `normalizes case separators and ambiguous characters`() {
        assertEquals("ABCD2345", RoomCode.normalize("abcd-2345"))
        assertEquals("ABCD2345", RoomCode.normalize("ABCD 2345"))
        assertEquals("1102345V", RoomCode.normalize("ilo-2345u"))
        assertEquals("1102345V", RoomCode.normalize("ILO2345U"))
    }

    @Test
    fun `rejects malformed input`() {
        assertNull(RoomCode.normalize("ABC2345"))
        assertNull(RoomCode.normalize("ABCD23456"))
        assertNull(RoomCode.normalize("ABCD234!"))
        assertNull(RoomCode.normalize(""))
        assertEquals("ABCD2345", RoomCode.normalize("ABCD-2345-"))
    }

    @Test
    fun pretty_formats_with_dash() {
        assertEquals("ABCD-2345", RoomCode.pretty("ABCD2345"))
    }

    @Test
    fun `generateUnique retries on collision`() {
        var calls = 0
        val code = RoomCode.generateUnique {
            calls++
            calls <= 2
        }
        assertEquals(3, calls)
        assertTrue(RoomCode.isValid(code))
    }

    @Test
    fun `generateUnique gives up after five attempts`() {
        assertThrows(IllegalStateException::class.java) {
            RoomCode.generateUnique(isTaken = { true }, attempts = 5)
        }
    }
}
