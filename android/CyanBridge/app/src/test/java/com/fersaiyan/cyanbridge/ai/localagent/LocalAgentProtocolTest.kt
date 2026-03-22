package com.fersaiyan.cyanbridge.ai.localagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAgentProtocolTest {

    @Test
    fun `parses valid response`() {
        val raw = """
            {
              "version": 1,
              "assistant_message": "Starting recording now.",
              "actions": [
                {"type": "start_meeting_capture", "timer_seconds": 900},
                {"type": "speak", "text": "Recording started"}
              ]
            }
        """.trimIndent()

        val parsed = LocalAgentProtocol.parseBrainResponse(raw)
        assertEquals(1, parsed.version)
        assertEquals("Starting recording now.", parsed.assistantMessage)
        assertEquals(2, parsed.actions.size)
        assertTrue(parsed.actions[0] is LocalAgentProtocol.StartMeetingCaptureAction)
        assertTrue(parsed.actions[1] is LocalAgentProtocol.SpeakAction)
    }

    @Test
    fun `extracts json from fenced block`() {
        val raw = """
            Sure, here's what I'll do:

            ```json
            {"version":1,"actions":[{"type":"noop"}]}
            ```

            Done.
        """.trimIndent()

        val parsed = LocalAgentProtocol.parseBrainResponse(raw)
        assertEquals(1, parsed.version)
        assertNotNull(parsed.actions)
        assertEquals(1, parsed.actions.size)
        assertTrue(parsed.actions.first() is LocalAgentProtocol.NoOpAction)
    }

    @Test
    fun `unknown action is preserved`() {
        val raw = """{"version":1,"actions":[{"type":"do_magic","x":1}]}"""
        val parsed = LocalAgentProtocol.parseBrainResponse(raw)
        assertEquals(1, parsed.actions.size)
        val a = parsed.actions.first()
        assertTrue(a is LocalAgentProtocol.UnknownAction)
        assertEquals("do_magic", (a as LocalAgentProtocol.UnknownAction).type)
    }
}
