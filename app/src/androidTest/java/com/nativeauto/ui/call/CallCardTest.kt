package com.nativeauto.ui.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.nativeauto.MainActivity
import com.nativeauto.ResetSettingsRule
import com.nativeauto.media.NotificationAccess
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 14a: the call card against real call notifications (CallStyle, what WhatsApp / Teams / the dialers post on
 * Android 12+) posted from the test, with the notification access the media card already needs. The
 * emulator can't place a call, so the notification's own Answer / Decline / Hang up intents are broadcasts
 * the test listens to.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class CallCardTest {

    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @get:Rule(order = 1) val resetSettings = ResetSettingsRule()

    @get:Rule(order = 2) val rule = createAndroidComposeRule<MainActivity>()

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val received = ConcurrentLinkedQueue<String>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) { received += intent.action.orEmpty() }
    }

    private fun tag(t: String) = rule.onNodeWithTag(t, useUnmergedTree = true)
    private fun count(t: String) = rule.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun click(t: String) { tag(t).performClick(); rule.waitForIdle() }
    private fun text(t: String) = tag(t).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
    private fun await(t: String) = rule.waitUntil(15_000) { count(t) > 0 }
    private fun awaitGone(t: String) = rule.waitUntil(15_000) { count(t) == 0 }
    private fun slotArea(pane: String): Float = tag("slot_$pane").getBoundsInRoot().let { (it.right - it.left).value * (it.bottom - it.top).value }

    private fun intent(action: String): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(action).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun channel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Test calls", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun builder(): Notification.Builder = Notification.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.sym_call_incoming)
        .setCategory(Notification.CATEGORY_CALL)
        // CallStyle notifications must be a foreground service's or carry a full-screen intent.
        .setFullScreenIntent(intent("$PKG.FULL"), true)

    private fun postIncoming() {
        val person = Person.Builder().setName("Ayu Lestari").build()
        manager.notify(ID, builder().setStyle(Notification.CallStyle.forIncomingCall(person, intent(DECLINE), intent(ANSWER))).build())
    }

    private fun postOngoing() {
        val person = Person.Builder().setName("Ayu Lestari").build()
        manager.notify(
            ID,
            builder()
                .setStyle(Notification.CallStyle.forOngoingCall(person, intent(HANG_UP)))
                .setOngoing(true)
                .setWhen(System.currentTimeMillis() - 65_000)
                .setUsesChronometer(true)
                .setShowWhen(true)
                .build(),
        )
    }

    @Before fun setUp() {
        NotificationAccess.grant()
        channel()
        // A CallStyle notification needs a full-screen intent from an app that is not a system dialer: Android 14+ asks for this app-op.
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cmd appops set com.nativeauto USE_FULL_SCREEN_INTENT allow").close()
        val filter = IntentFilter().apply { addAction(ANSWER); addAction(DECLINE); addAction(HANG_UP) }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    @After fun tearDown() {
        manager.cancel(ID)
        runCatching { context.unregisterReceiver(receiver) }
        NotificationAccess.grant()
    }

    @Test fun anIncomingCallTakesMediasSlotAndAnswersThroughTheNotification() {
        val mediaArea = slotArea("MEDIA")
        assertTrue("no call card yet", slotArea("CALL") < 1f)
        postIncoming()
        await("call_name")
        // Media was the lowest-priority pane: the call card sits where it was, media folds away.
        assertEquals("Ayu Lestari", text("call_name"))
        assertEquals("Incoming call", text("call_status"))
        rule.waitUntil(10_000) { slotArea("MEDIA") < 1f }
        assertTrue("the card has media's room", slotArea("CALL") > mediaArea * 0.9f)
        tag("call_answer").assertIsDisplayed()
        tag("call_decline").assertIsDisplayed()

        click("call_answer")
        rule.waitUntil(5_000) { received.contains(ANSWER) }

        // The call goes on: the same notification, now ongoing with its chronometer.
        postOngoing()
        await("call_end")
        rule.waitUntil(10_000) { text("call_status").matches(Regex("\\d+:\\d\\d")) }
        assertEquals(0, count("call_answer"))
        click("call_end")
        rule.waitUntil(5_000) { received.contains(HANG_UP) }

        // The call ends: media gets its slot back.
        manager.cancel(ID)
        awaitGone("call_name")
        rule.waitUntil(10_000) { slotArea("MEDIA") > 1f }
    }

    @Test fun decliningFiresTheNotificationsOwnDeclineIntent() {
        postIncoming()
        await("call_decline")
        click("call_decline")
        rule.waitUntil(5_000) { received.contains(DECLINE) }
        assertTrue(!received.contains(ANSWER))
    }

    /** 4.6.4: not a check: the portrait call card for the `DESIGN.md` review (see `DesignShotsTest`). */
    @Test fun shotsOfThePortraitCard() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("designShots") == "1")
        kotlinx.coroutines.runBlocking { resetSettings.settings.setOrientationMode(com.nativeauto.settings.OrientationMode.PORTRAIT) }
        fun shot(name: String) {
            rule.waitForIdle()
            Thread.sleep(1_500)
            val p = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/design/$name.png")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(p).use { it.readBytes() }
        }
        Thread.sleep(2_500)
        postIncoming()
        await("call_answer")
        Thread.sleep(8_000) // the heads-up notification goes
        shot("call_port_ringing")
        postOngoing()
        await("call_end")
        shot("call_port_on")
    }

    @Test fun anActiveCallOffersMuteOnlyWhereItTakesEffect() {
        postOngoing()
        await("call_end")
        if (count("call_mute") == 0) return // a phone that never lets the app mute shows no button: fine
        click("call_mute")
        rule.waitForIdle()
        // Either the mute took (the switch reads On) or it didn't and the button went away: never a lying button.
        rule.waitUntil(5_000) {
            count("call_mute") == 0 ||
                tag("call_mute").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription) == "On"
        }
        if (count("call_mute") > 0) click("call_mute") // leave the microphone as it was
    }

    @Test fun aCallNeverTakesOverAFullscreenCard() {
        click("fullscreen_SPEED")
        postIncoming()
        Thread.sleep(2_000)
        rule.waitForIdle()
        assertTrue("the fullscreen card stays", slotArea("CALL") < 1f)
        // 14b: the call shows as a slim pill over it instead.
        tag("call_pill").assertIsDisplayed()
        // Leaving fullscreen while the call rings: now the card comes, and the pill goes.
        click("fullscreen_SPEED")
        rule.waitUntil(10_000) { slotArea("CALL") > 1f }
        tag("call_answer").assertIsDisplayed()
        assertEquals(0, count("call_pill"))
    }

    /** 14b: over a fullscreen pane the call is a pill: name, Decline / Answer, the card untouched, gone with the call. */
    @Test fun theFullscreenPillAnswersWithoutMovingTheCard() {
        click("fullscreen_SPEED")
        val before = tag("slot_SPEED").getBoundsInRoot()
        postIncoming()
        await("call_pill")
        assertEquals("Ayu Lestari", tag("call_pill_name").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text })
        assertEquals("Incoming call", tag("call_pill_status").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text })
        tag("call_pill_decline").assertIsDisplayed()
        assertEquals("the fullscreen card did not move or resize", before, tag("slot_SPEED").getBoundsInRoot())
        assertTrue("no call card over a fullscreen pane", slotArea("CALL") < 1f)
        click("call_pill_answer")
        rule.waitUntil(5_000) { received.contains(ANSWER) }

        // On the call: the pill offers End (and Mute where it works) instead.
        postOngoing()
        await("call_pill_end")
        assertEquals(0, count("call_pill_answer"))
        click("call_pill_end")
        rule.waitUntil(5_000) { received.contains(HANG_UP) }
        manager.cancel(ID)
        awaitGone("call_pill")
    }

    private companion object {
        const val CHANNEL = "test_calls"
        const val ID = 4242
        const val PKG = "com.nativeauto.test.call"
        const val ANSWER = "$PKG.ANSWER"
        const val DECLINE = "$PKG.DECLINE"
        const val HANG_UP = "$PKG.HANG_UP"
    }
}
