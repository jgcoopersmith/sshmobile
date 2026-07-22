package com.j0ker.sshmobile.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

/**
 * `ask` parks the calling thread the way sshj's transport thread is parked, so
 * each test answers from a second thread.
 */
class HostKeyPrompterTest {

    /** Spins until the dialog would be on screen, then runs [answer]. */
    private fun respondOffThread(prompter: HostKeyPrompter, answer: () -> Unit) =
        thread {
            while (prompter.pending.value == null) Thread.sleep(2)
            answer()
        }

    @Test
    fun `accepting unblocks the caller with true`() {
        val prompter = HostKeyPrompter()
        val responder = respondOffThread(prompter) { prompter.respond(true) }

        assertTrue(prompter.ask("example.com", 22, "SHA256:abc"))

        responder.join()
        assertFalse(prompter.declined)
    }

    @Test
    fun `refusing unblocks the caller with false and records the refusal`() {
        val prompter = HostKeyPrompter()
        val responder = respondOffThread(prompter) { prompter.respond(false) }

        assertFalse(prompter.ask("example.com", 22, "SHA256:abc"))

        responder.join()
        assertTrue(prompter.declined)
    }

    @Test
    fun `cancelling a torn-down session refuses rather than trusts`() {
        val prompter = HostKeyPrompter()
        val responder = respondOffThread(prompter) { prompter.cancel() }

        assertFalse(prompter.ask("example.com", 22, "SHA256:abc"))

        responder.join()
        assertTrue(prompter.declined)
    }

    @Test
    fun `the pending prompt carries the host and fingerprint`() {
        val prompter = HostKeyPrompter()
        var seen: HostKeyPrompt? = null
        val responder = respondOffThread(prompter) {
            seen = prompter.pending.value
            prompter.respond(true)
        }

        prompter.ask("example.com", 2222, "SHA256:abc")

        responder.join()
        assertEquals("example.com", seen?.hostname)
        assertEquals(2222, seen?.port)
        assertEquals("SHA256:abc", seen?.fingerprint)
        assertEquals("example.com:2222", seen?.target)
    }

    @Test
    fun `the prompt clears once answered so the dialog goes away`() {
        val prompter = HostKeyPrompter()
        val responder = respondOffThread(prompter) { prompter.respond(true) }

        prompter.ask("example.com", 22, "SHA256:abc")

        responder.join()
        assertNull(prompter.pending.value)
    }

    @Test
    fun `a second prompt works after the first was refused`() {
        val prompter = HostKeyPrompter()

        respondOffThread(prompter) { prompter.respond(false) }.also {
            assertFalse(prompter.ask("a.example", 22, "SHA256:one"))
            it.join()
        }

        // declined must reset, or a later success would still look like a refusal.
        respondOffThread(prompter) { prompter.respond(true) }.also {
            assertTrue(prompter.ask("b.example", 22, "SHA256:two"))
            it.join()
        }
        assertFalse(prompter.declined)
    }
}
