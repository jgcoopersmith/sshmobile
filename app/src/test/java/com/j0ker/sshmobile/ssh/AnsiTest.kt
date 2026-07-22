package com.j0ker.sshmobile.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

private val ESC = Char(27).toString()
private val BEL = Char(7).toString()

class AnsiTest {

    @Test
    fun `plain text passes through`() {
        assertEquals("hello world", stripAnsi("hello world"))
    }

    @Test
    fun `csi colour sequences are removed`() {
        assertEquals("red", stripAnsi(ESC + "[31mred" + ESC + "[0m"))
    }

    @Test
    fun `multi parameter csi is removed`() {
        assertEquals("bold", stripAnsi(ESC + "[1;32;40mbold" + ESC + "[m"))
    }

    @Test
    fun `cursor movement is removed`() {
        assertEquals("ab", stripAnsi("a" + ESC + "[2Kb"))
    }

    @Test
    fun `two character escapes are removed`() {
        assertEquals("x", stripAnsi(ESC + "cx"))
    }

    @Test
    fun `osc title sequence terminated by bel is removed`() {
        assertEquals("prompt", stripAnsi(ESC + "]0;user@host" + BEL + "prompt"))
    }

    @Test
    fun `osc terminated by string terminator is removed`() {
        assertEquals("prompt", stripAnsi(ESC + "]0;title" + ESC + "\\prompt"))
    }

    @Test
    fun `crlf collapses to lf`() {
        assertEquals("a\nb\n", stripAnsi("a\r\nb\r\n"))
    }

    @Test
    fun `backspace erases the previous character`() {
        assertEquals("ac", stripAnsi("ab\bc"))
    }

    @Test
    fun `backspace does not eat across a newline`() {
        assertEquals("a\nb", stripAnsi("a\n\bb"))
    }

    @Test
    fun `trailing lone escape does not overrun`() {
        assertEquals("done" + ESC, stripAnsi("done" + ESC))
    }
}

class SftpHelpersTest {

    @Test
    fun `size formatting matches the desktop thresholds`() {
        assertEquals("512 B", SftpSession.formatSize(512))
        assertEquals("1.0 KB", SftpSession.formatSize(1024))
        assertEquals("1.0 MB", SftpSession.formatSize(1_048_576))
        assertEquals("1.0 GB", SftpSession.formatSize(1_073_741_824))
    }

    @Test
    fun `parent of a nested path drops the last segment`() {
        assertEquals("/var", SftpSession.parentOf("/var/log"))
        assertEquals("/var", SftpSession.parentOf("/var/log/"))
    }

    @Test
    fun `parent of a top level path is root`() {
        assertEquals("/", SftpSession.parentOf("/var"))
        assertEquals("/", SftpSession.parentOf("/"))
    }
}
