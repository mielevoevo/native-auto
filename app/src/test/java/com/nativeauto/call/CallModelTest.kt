package com.nativeauto.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallModelTest {
    private fun facts(
        app: String = "Phone",
        category: String? = null,
        template: String? = null,
        callType: Int = 0,
        title: String? = null,
        person: String? = null,
        text: String? = null,
        actions: List<String> = emptyList(),
        answer: Boolean = false,
        decline: Boolean = false,
        hangUp: Boolean = false,
        whenMs: Long = 0,
        chronometer: Boolean = false,
        ongoing: Boolean = false,
    ) = NotificationFacts("k", app, category, template, callType, title, person, text, actions, answer, decline, hangUp, whenMs, chronometer, ongoing)

    // ---- classifying notifications of different apps and skins ----

    @Test fun `a CallStyle incoming call rings, with the person as the caller`() {
        val c = classifyCall(facts(app = "WhatsApp", category = "call", template = CALL_STYLE_TEMPLATE, callType = 1, person = "Ayu", title = "Ayu", answer = true, decline = true))!!
        assertEquals(CallPhase.RINGING, c.phase)
        assertEquals("Ayu", c.caller)
        assertEquals("WhatsApp", c.app)
    }

    @Test fun `a CallStyle ongoing call is active and carries its start for the duration`() {
        val c = classifyCall(facts(app = "Teams", template = CALL_STYLE_TEMPLATE, callType = 2, person = "Standup", hangUp = true, whenMs = 1_700_000_000_000, chronometer = true, ongoing = true))!!
        assertEquals(CallPhase.ACTIVE, c.phase)
        assertEquals(1_700_000_000_000, c.startedAtMs)
    }

    @Test fun `a call being screened still rings for the rider`() {
        assertEquals(CallPhase.RINGING, classifyCall(facts(template = CALL_STYLE_TEMPLATE, callType = 3, person = "Unknown"))!!.phase)
    }

    @Test fun `an older call-category notification with an answer action rings, without one it is on`() {
        val ringing = classifyCall(facts(app = "Zoom", category = "call", title = "Budi", actions = listOf("Decline", "Answer")))!!
        assertEquals(CallPhase.RINGING, ringing.phase)
        assertEquals("Budi", ringing.caller)
        val on = classifyCall(facts(app = "Zoom", category = "call", title = "Budi", actions = listOf("Hang up"), ongoing = true))!!
        assertEquals(CallPhase.ACTIVE, on.phase)
    }

    @Test fun `actions in Indonesian are understood`() {
        assertEquals(CallPhase.RINGING, classifyCall(facts(category = "call", title = "Siti", actions = listOf("Tolak", "Jawab")))!!.phase)
        assertEquals(1, findAnswerAction(listOf("Tolak", "Jawab")))
        assertEquals(0, findDeclineAction(listOf("Tolak", "Jawab")))
        assertEquals(0, findHangUpAction(listOf("Akhiri")))
    }

    @Test fun `notifications that are not calls are ignored`() {
        assertNull(classifyCall(facts(category = "msg", title = "Ayu", text = "Where are you?")))
        assertNull(classifyCall(facts(category = "transport", title = "Spotify")))
        assertNull(classifyCall(facts()))
    }

    @Test fun `a missed call and a leftover with no actions are not calls in progress`() {
        assertNull(classifyCall(facts(category = "missed_call", title = "Ayu", actions = listOf("Call back"))))
        assertNull(classifyCall(facts(category = "call", title = "Ayu")))
    }

    @Test fun `the caller falls back through the person, the title, the text and the app`() {
        assertEquals("Ayu", classifyCall(facts(template = CALL_STYLE_TEMPLATE, callType = 1, person = " Ayu ", title = "x"))!!.caller)
        assertEquals("Budi", classifyCall(facts(template = CALL_STYLE_TEMPLATE, callType = 1, title = "Budi"))!!.caller)
        assertEquals("Mobile", classifyCall(facts(template = CALL_STYLE_TEMPLATE, callType = 1, text = "Mobile"))!!.caller)
        assertEquals("Phone", classifyCall(facts(template = CALL_STYLE_TEMPLATE, callType = 1))!!.caller)
    }

    // ---- choosing an action by its label ----

    @Test fun `action labels match whole words only`() {
        assertNull(findHangUpAction(listOf("Send", "Pending")))
        assertEquals(1, findHangUpAction(listOf("Mute", "End call")))
        assertEquals(0, findAnswerAction(listOf("Accept", "Reject")))
        assertEquals(1, findDeclineAction(listOf("Accept", "Reject")))
    }

    @Test fun `with no decline label a hang up counts as the way to decline, and the reverse`() {
        assertEquals(1, findDeclineAction(listOf("Answer", "Hang up")))
        assertEquals(0, findHangUpAction(listOf("Decline")))
    }

    // ---- several notifications, the cellular state, the controls ----

    @Test fun `the ringing call comes first, then the longest running`() {
        val ringing = CallInfo("A", "WhatsApp", CallPhase.RINGING)
        val old = CallInfo("B", "Phone", CallPhase.ACTIVE, startedAtMs = 1_000)
        val newer = CallInfo("C", "Zoom", CallPhase.ACTIVE, startedAtMs = 5_000)
        assertEquals(ringing, pickCall(listOf(newer, old, ringing)))
        assertEquals(old, pickCall(listOf(newer, old)))
        assertNull(pickCall(emptyList()))
    }

    @Test fun `the call from a notification wins, the cellular state fills in when there is none`() {
        val n = CallInfo("Ayu", "WhatsApp", CallPhase.ACTIVE)
        assertEquals(n, mergeCall(n, PhoneState.RINGING))
        assertEquals(CallPhase.RINGING, mergeCall(null, PhoneState.RINGING)!!.phase)
        assertEquals(CallPhase.ACTIVE, mergeCall(null, PhoneState.OFFHOOK)!!.phase)
        assertNull(mergeCall(null, PhoneState.IDLE))
        assertNull(mergeCall(null, PhoneState.UNKNOWN))
    }

    @Test fun `ringing offers answer and decline from the intents the notification carries`() {
        val c = callCapabilities(CallPhase.RINGING, NotificationActions(answer = true, decline = true, hangUp = false), false, PhoneState.UNKNOWN, true, 0)
        assertTrue(c.canAnswer); assertTrue(c.canDecline)
        assertFalse(c.canEnd); assertFalse(c.canMute); assertFalse(c.canChooseAudio)
    }

    @Test fun `a plain phone call with no notification can be answered only with the permission`() {
        val without = callCapabilities(CallPhase.RINGING, null, telecomEnabled = false, PhoneState.RINGING, true, 0)
        assertFalse(without.canAnswer); assertFalse(without.canDecline)
        val with = callCapabilities(CallPhase.RINGING, null, telecomEnabled = true, PhoneState.RINGING, true, 0)
        assertTrue(with.canAnswer); assertTrue(with.canDecline)
    }

    @Test fun `an active call offers end, mute and audio only where something can do them`() {
        val full = callCapabilities(CallPhase.ACTIVE, NotificationActions(false, false, true), false, PhoneState.UNKNOWN, micMuteSupported = true, audioRoutesAvailable = 3)
        assertTrue(full.canEnd); assertTrue(full.canMute); assertTrue(full.canChooseAudio)
        assertFalse(full.canAnswer); assertFalse(full.canDecline)
        val bare = callCapabilities(CallPhase.ACTIVE, NotificationActions(false, false, false), false, PhoneState.UNKNOWN, micMuteSupported = false, audioRoutesAvailable = 1)
        assertFalse(bare.canEnd); assertFalse(bare.canMute); assertFalse(bare.canChooseAudio)
        // A cellular call in progress can be ended through Telecom with the permission.
        assertTrue(callCapabilities(CallPhase.ACTIVE, null, true, PhoneState.OFFHOOK, true, 0).canEnd)
    }

    @Test fun `durations read like a call timer`() {
        assertEquals("0:00", formatCallDuration(-5))
        assertEquals("0:42", formatCallDuration(42_900))
        assertEquals("12:07", formatCallDuration((12 * 60 + 7) * 1000L))
        assertEquals("1:02:09", formatCallDuration((3600 + 2 * 60 + 9) * 1000L))
    }
}
