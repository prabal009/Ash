package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanTranscriptTest {
    private fun c(s: String) = VoiceInput.clean(s)

    @Test fun stripsDetectedWakePhraseOnly() {
        assertEquals("Send my wife a message", VoiceInput.clean("hey ash send my wife a message", "Hey Ash"))
        assertEquals("Turn on the flashlight", VoiceInput.clean("Hello Ash, turn on the flashlight", "Hello Ash"))
        assertEquals("", VoiceInput.clean("Hey Ash.", "Hey Ash"))
        assertEquals("", VoiceInput.clean("Hello Ash!", "Hello Ash"))
        assertEquals("", VoiceInput.clean("Hello asked", "Hello Ash"))
        assertEquals("", VoiceInput.clean("Hey asked", "Hey Ash"))
        assertEquals("Pay Ash back", VoiceInput.clean("Pay Ash back", "Hey Ash"))
        assertEquals("Hey Ash, open YouTube", VoiceInput.clean("Hey Ash, open YouTube", "Hello Ash"))
    }

    @Test fun stripsOnlyTheDetectedCustomPhraseAtTheStart() {
        assertEquals("Open maps", VoiceInput.clean("Hello computer, open maps", "Hello Computer"))
        assertEquals("Computer open maps", VoiceInput.clean("computer open maps", "Hello Computer"))
        assertEquals("Call the computer shop", VoiceInput.clean("Call the computer shop", "Computer"))
        assertEquals("Computer repair", VoiceInput.clean("computer repair"))
    }

    @Test fun leavesRealCommandsAlone() {
        assertEquals("Text Ash that I'm late", c("text Ash that I'm late"))
        assertEquals("Turn on the flashlight", c("turn on the flashlight"))
        assertEquals("Play the harvest playlist", c("play the harvest playlist"))
        assertEquals("Yes, open settings", c("yes, open settings"))
    }

    private val reply = VoiceInput.words("Done. I sent your wife a message on WhatsApp saying I love you.").toSet()

    @Test
    fun jarvissOwnEchoIsNotAnInterruption() {
        // What the recogniser makes of Jarvis's own voice, including small mishearings.
        assertFalse(VoiceInput.isBargeIn("DONE I SENT YOUR WIFE A MESSAGE", reply))
        assertFalse(VoiceInput.isBargeIn("SENT YOUR WIVE A MESSAGE ON WHATSAP", reply))
        assertFalse(VoiceInput.isBargeIn("", reply))
    }

    @Test
    fun talkingOverJarvisIsAnInterruption() {
        assertTrue(VoiceInput.isBargeIn("NO SEND IT TO MY BROTHER", reply))
        assertTrue("one stop word is enough", VoiceInput.isBargeIn("STOP", reply))
        assertTrue(VoiceInput.isBargeIn("I SENT YOUR WIFE WAIT", reply))
    }

    @Test
    fun echoAtTheStartOfAnInterruptionIsDropped() {
        assertEquals("No send it to my brother", VoiceInput.stripEcho("message on WhatsApp No send it to my brother", reply))
        assertEquals("Call mum", VoiceInput.stripEcho("Call mum", reply))
        assertEquals("Call mum", VoiceInput.stripEcho("Call mum", null))
    }

    @Test
    fun stopPhrasesAreRecognised() {
        listOf("Stop", "stop it", "Never mind", "Shut up.", "Okay, stop", "that's enough").forEach {
            assertTrue(it, VoiceInput.isStopCommand(it))
        }
        listOf("Stop the music", "Cancel my alarm", "Wait for me").forEach { assertFalse(it, VoiceInput.isStopCommand(it)) }
    }

    @Test
    fun partsOfJarvissOwnWordsAreEcho() {
        val spoken = VoiceInput.words("I set a stopwatch for ten minutes on WhatsApp.").toSet()
        assertFalse("'stop' is the start of 'stopwatch'", VoiceInput.isBargeIn("I SET A STOP", spoken))
        assertFalse(VoiceInput.isBargeIn("WHAT'S APP", spoken))
    }

    @Test
    fun stopKeywordIsOffWhenJarvisSaysSomethingLikeIt() {
        assertTrue(VoiceInput.soundsLikeStop("The top story is about the stock market."))
        assertTrue(VoiceInput.soundsLikeStop("Your next stop is Oxford Street."))
        assertTrue(VoiceInput.soundsLikeStop("Let me start the timer."))
        assertFalse(VoiceInput.soundsLikeStop("Here is what I found on the weather today."))
        assertFalse(VoiceInput.soundsLikeStop("Done. I sent your wife a message on WhatsApp."))
    }
}
