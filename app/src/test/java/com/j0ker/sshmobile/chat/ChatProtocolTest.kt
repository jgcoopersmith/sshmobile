package com.j0ker.sshmobile.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val BOM = Char(0xFEFF).toString()

/**
 * The desktop SSHClient writes with .NET's Encoding.UTF8 and WriteLine, so its
 * first line carries a byte-order mark and every line ends CRLF. Getting this
 * wrong silently drops the handshake, which looks like "connected but nothing
 * happens" on the phone.
 */
class ChatProtocolTest {

    @Test
    fun `handshake is recognised despite the desktop's leading BOM`() {
        val line = normaliseLine(BOM + "HELLO:jgcoo")
        assertTrue(line.startsWith("HELLO:"))
        assertEquals("jgcoo", line.substring(6))
    }

    @Test
    fun `a stray carriage return is trimmed`() {
        assertEquals("MSG:hello", normaliseLine("MSG:hello\r"))
    }

    @Test
    fun `BOM and carriage return together are both removed`() {
        assertEquals("HELLO:pc", normaliseLine(BOM + "HELLO:pc\r"))
    }

    @Test
    fun `an ordinary line is left alone`() {
        assertEquals("MSG:plain", normaliseLine("MSG:plain"))
    }

    @Test
    fun `a BOM appearing mid-message is not stripped`() {
        // Only a leading mark is protocol noise; one inside the text is content.
        assertEquals("MSG:a${BOM}b", normaliseLine("MSG:a${BOM}b"))
    }

    @Test
    fun `message text survives intact`() {
        val line = normaliseLine(BOM + "MSG:hello from the desktop\r")
        assertEquals("hello from the desktop", line.substring(4))
    }

    @Test
    fun `an empty line normalises to empty rather than failing`() {
        assertEquals("", normaliseLine(""))
        assertEquals("", normaliseLine(BOM))
    }
}
