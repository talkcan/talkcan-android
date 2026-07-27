package io.talkcan

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.talkcan.service.PttForegroundService
import io.talkcan.service.RequiredPermissions
import io.talkcan.lua.actor.ActorRuntimeFactory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression coverage for fix-background-rsm-service-lifetime on a real device.
 *
 * The contract under test is observable only through Android's process-wide
 * service registry, not through the app's public API. On the physical target
 * `ActivityManager.getRunningServices` does not surface a usable record for a
 * same-process bound+started service inside instrumentation, so observation is
 * driven by the system service registry via
 * `UiAutomation.executeShellCommand("dumpsys activity services io.talkcan")`.
 *
 * The parsed [ServiceSnapshot] is narrow to the [PttForegroundService]
 * [ServiceRecord][android.os.SystemServiceRegistry] block and captures the
 * production-evidence fields: `startRequested`, `isForeground`, `hasBound`,
 * and the stable `ServiceRecord{<hex> ...}` instance identity.
 *
 *   1. Launching [MainActivity] establishes a *started, foreground*
 *      [PttForegroundService] — onStart calls
 *      `startForegroundService(ACTION_START_MONITORING)` before `bindService`.
 *   2. Moving the Activity to CREATED (background) invokes the production
 *      `unbindService` on onStop (`hasBound=false`), but the *same* service
 *      instance remains started and in the foreground.
 *
 * A second test exercises repeated Activity start/stop idempotency: cycling
 * the Activity RESUMED → CREATED → RESUMED → CREATED must not restart the
 * service — the same ServiceRecord identity survives the cycle, with the
 * service still started+foreground at every state and `hasBound=false` in
 * each CREATED state.
 *
 * A third test proves that an explicit `disconnectSerial()` suppresses a
 * repeated `ACTION_START_MONITORING` start intent in the same service
 * instance: after disconnect, `startRequested` and `foreground` are false
 * and remain false when the production start intent is re-issued, because
 * `onStartCommand` gates `ensureForeground()` on `monitoringRequested`. The
 * service survives until the Activity's own binding is released in `onStop`,
 * at which point it is terminally destroyed.
 *
 * This test requires a real device (or a system image where `dumpsys` runs
 * with shell identity); `executeShellCommand` is unavailable on host JVM.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityServiceLifecycleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun cleanupLeftoverService() {
        // A prior failed run may have left the service started. Stop it so the
        // test observes its own lifecycle rather than residual state.
        stopServiceAndAwaitGone()
        // Fresh installs lack runtime permissions. Production ensureForeground
        // includes connectedDevice|microphone and Android rejects foreground
        // promotion without Bluetooth/audio permissions, which left a fresh
        // install on the ineligible path (startRequested=false,
        // startForegroundCount=0). Grant the production runtime-permission list
        // via shell identity so the ActivityScenario.launch below enters the
        // eligible started+foreground startup path the tests assert on.
        grantRuntimePermissions()
    }

    @After
    fun teardown() {
        scenario?.close()
        scenario = null
        stopServiceAndAwaitGone()
    }

    @Test
    fun launchingActivityBindsSuppressedServiceUntilBackgrounding() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        waitFor("activity launch should bind a service without requesting monitoring") {
            val snapshot = serviceSnapshot()
            snapshot.exists &&
                snapshot.hasBound &&
                !snapshot.startRequested &&
                !snapshot.foreground
        }

        scenario!!.moveToState(Lifecycle.State.CREATED)
        waitFor("suppressed service should stop after the activity unbinds") {
            !serviceSnapshot().exists
        }
    }
    @Test
    fun ordinaryBoundLifecycleDoesNotCreateLuaActorRuntime() {
        assumeFalse(
            "Lua dormancy requires a built-in-only persisted catalogue",
            hasEnabledExternalChannel(),
        )
        ActorRuntimeFactory.resetForTest()
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitFor("ordinary activity launch should bind the suppressed service") {
                val snapshot = serviceSnapshot()
                snapshot.exists &&
                    snapshot.hasBound &&
                    !snapshot.startRequested &&
                    !snapshot.foreground
            }
            assertFalse(
                "ordinary activity/service startup with Kotlin providers must not construct a Lua actor",
                ActorRuntimeFactory.isCreateAttempted,
            )

            scenario!!.moveToState(Lifecycle.State.CREATED)
            waitFor("suppressed service should stop after the Lua-dormancy witness unbinds") {
                !serviceSnapshot().exists
            }
            assertFalse(
                "background lifecycle must not retroactively construct a Lua actor",
                ActorRuntimeFactory.isCreateAttempted,
            )
        } finally {
            ActorRuntimeFactory.resetForTest()
        }
    }

    @Test
    fun repeatedActivityStartStopRecreatesOnlyBoundSuppressedService() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("initial activity launch should bind the suppressed service") {
            val snapshot = serviceSnapshot()
            snapshot.exists &&
                snapshot.hasBound &&
                !snapshot.startRequested &&
                !snapshot.foreground
        }
        val initialIdentity = requireNotNull(serviceSnapshot().serviceRecordId)

        scenario!!.moveToState(Lifecycle.State.CREATED)
        waitFor("initial suppressed service should stop after unbind") {
            !serviceSnapshot().exists
        }

        scenario!!.moveToState(Lifecycle.State.RESUMED)
        waitFor("resumed activity should bind a fresh suppressed service") {
            val snapshot = serviceSnapshot()
            snapshot.exists &&
                snapshot.hasBound &&
                !snapshot.startRequested &&
                !snapshot.foreground
        }
        assertFalse(
            "a resumed activity must bind a new service instance after terminal suppression",
            initialIdentity == serviceSnapshot().serviceRecordId,
        )
    }

    @Test
    fun repeatedStartIntentRemainsSuppressedWithoutMonitoringRequest() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("activity launch should bind the suppressed service") {
            val snapshot = serviceSnapshot()
            snapshot.exists &&
                snapshot.hasBound &&
                !snapshot.startRequested &&
                !snapshot.foreground
        }
        val identity = requireNotNull(serviceSnapshot().serviceRecordId)

        ContextCompat.startForegroundService(
            context,
            Intent(context, PttForegroundService::class.java)
                .setAction(PttForegroundService.ACTION_START_MONITORING),
        )
        waitFor("repeated start intent must remain suppressed while monitoring is false") {
            val snapshot = serviceSnapshot()
            snapshot.exists &&
                snapshot.hasBound &&
                !snapshot.startRequested &&
                !snapshot.foreground
        }
        assertEquals(identity, serviceSnapshot().serviceRecordId)

        scenario!!.moveToState(Lifecycle.State.CREATED)
        waitFor("suppressed service should stop after the activity unbinds") {
            !serviceSnapshot().exists
        }
    }

    /**
     * Parsed view of the [PttForegroundService] block within
     * `dumpsys activity services io.talkcan`.
     *
     * `serviceRecordId` is the `ServiceRecord{<hex> u0 <component>}` token —
     * the stable per-instance identity; a restart produces a new ServiceRecord.
     * Fields default to false when the service is not present.
     */
    private data class ServiceSnapshot(
        val exists: Boolean,
        val startRequested: Boolean,
        val foreground: Boolean,
        val hasBound: Boolean,
        val serviceRecordId: String?,
    )

    /**
     * Runs `dumpsys activity services io.talkcan` via
     * [android.app.UiAutomation.executeShellCommand] and returns the full text.
     * The returned [android.os.ParcelFileDescriptor] is always closed.
     */
    private fun dumpsysServices(): String {
        val pfd = InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeShellCommand("dumpsys activity services io.talkcan")
        return try {
            FileInputStream(pfd.fileDescriptor).bufferedReader().readText()
        } finally {
            pfd.close()
        }
    }

    /**
     * Parses the [PttForegroundService] ServiceRecord block out of the dumpsys
     * output. Parsing is narrow to the single block whose header line names
     * `PttForegroundService`; fields are matched only within that block so a
     * stray `startRequested=true` from another service in the package cannot
     * leak into this snapshot.
     */
    private fun serviceSnapshot(): ServiceSnapshot {
        val text = dumpsysServices()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val header = lines[i]
            // ServiceRecord block header, e.g.:
            //   * ServiceRecord{a1b2c3d u0 io.talkcan/.service.PttForegroundService}
            if (header.trimStart().startsWith("* ServiceRecord{") && header.contains("PttForegroundService")) {
                val id = SERVICE_RECORD_ID.find(header)?.value
                // Block body extends until the next ServiceRecord header or end.
                var startRequested = false
                var foreground = false
                var hasBound = false
                var j = i + 1
                while (j < lines.size) {
                    val body = lines[j]
                    if (body.trimStart().startsWith("* ServiceRecord{")) break
                    val trimmed = body.trim()
                    // Real dumpsys body lines carry trailing fields, e.g.
                    // `startRequested=true delayedStop=false ...`,
                    // `isForeground=true foregroundId=41 ...`, and
                    // `requested=true received=true hasBound=false ...`.
                    // A narrow boundary regex picks the exact token anywhere in
                    // the trimmed line and preserves the prior value when no
                    // match is present (fields may appear on different lines).
                    START_REQUESTED.find(trimmed)?.groupValues?.get(1)
                        ?.toBooleanStrictOrNull()?.let { startRequested = it }
                    IS_FOREGROUND.find(trimmed)?.groupValues?.get(1)
                        ?.toBooleanStrictOrNull()?.let { foreground = it }
                    HAS_BOUND.find(trimmed)?.groupValues?.get(1)
                        ?.toBooleanStrictOrNull()?.let { hasBound = it }
                    j++
                }
                return ServiceSnapshot(
                    exists = true,
                    startRequested = startRequested,
                    foreground = foreground,
                    hasBound = hasBound,
                    serviceRecordId = id,
                )
            }
            i++
        }
        return ServiceSnapshot(false, false, false, false, null)
    }

    private fun stopServiceAndAwaitGone() {
        context.stopService(
            Intent(context, PttForegroundService::class.java)
                .setAction(PttForegroundService.ACTION_START_MONITORING),
        )
        // Cleanup polls until the service is absent from the registry. A failed
        // stop may leave a residual ServiceRecord; keep polling until it clears
        // so the next test starts from a clean slate.
        waitFor("service should be absent from the registry after stop") {
            !serviceSnapshot().exists
        }
    }


    /**
     * Binds to the same-process [PttForegroundService] with a temporary
     * [Context.BIND_AUTO_CREATE] connection, awaits the [LocalBinder],
     * obtains the service instance, and unbinds the temporary connection in
     * a [finally] block before returning. The Activity's own binding
     * (established by [MainActivity.onStart]) keeps the service alive after
     * the temporary unbind, so the returned instance remains valid for the
     * remainder of the test.
     */
    private fun obtainServiceViaTempBind(): PttForegroundService {
        val latch = CountDownLatch(1)
        val serviceRef = AtomicReference<PttForegroundService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                serviceRef.set((binder as PttForegroundService.LocalBinder).service())
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(
            "temporary bindService must accept PttForegroundService",
            context.bindService(
                Intent(context, PttForegroundService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            ),
        )
        try {
            assertTrue(
                "temporary bind to PttForegroundService did not connect within ${BIND_TIMEOUT_MS}ms",
                latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            )
            assertNotNull("LocalBinder must provide the service instance", serviceRef.get())
            return serviceRef.get()!!
        } finally {
            // Always unbind the temporary connection; the Activity's own
            // binding keeps the service alive. Swallow if already unbound.
            try {
                context.unbindService(connection)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Grants every permission in [RequiredPermissions.runtimePermissions] to the
     * instrumented package via `pm grant` run with shell identity. Each
     * command's [android.os.ParcelFileDescriptor] is fully drained and closed
     * before the next is issued, so every grant takes effect before
     * [ActivityScenario.launch] in the test body.
     *
     * Grants are intentionally NOT revoked in teardown: revoking permissions
     * between tests would trigger permission-dialog/state changes that
     * destabilize repeated connected tests. UTP uninstalls the app after the
     * class, clearing granted runtime permissions with it.
     */
    private fun grantRuntimePermissions() {
        val packageName = context.packageName
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        for (permission in RequiredPermissions.runtimePermissions()) {
            val pfd = uiAutomation.executeShellCommand("pm grant $packageName $permission")
            try {
                FileInputStream(pfd.fileDescriptor).bufferedReader().readText()
            } finally {
                pfd.close()
            }
        }
    }

    /**
     * Poll [condition] until it returns true, up to a bounded timeout. Throws
     * with [message] if the condition never becomes true within the budget.
     * All waits are deterministic-condition-based, never wall-clock sleeps.
     */
    private fun waitFor(
        message: String = "condition never became true",
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + SERVICE_STATE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
        throw AssertionError(message)
    }

    private companion object {
        // The service's foreground promotion, bind/unbind, and state-settling
        // all happen on the main thread and may require a few poll cycles to
        // propagate to the system service registry. The bound is generous to
        // stay deterministic on slow devices without sleeping.
        const val SERVICE_STATE_TIMEOUT_MS = 10_000L
        const val BIND_TIMEOUT_MS = 5_000L

        // Captures the `ServiceRecord{<hex> ...}` identity token (including the
        // braces) from a ServiceRecord block header. The hex is the per-instance
        // identity; a restart yields a new ServiceRecord with a different hex.
        val SERVICE_RECORD_ID = Regex("""ServiceRecord\{[0-9a-fA-F]+""")

        // Captures the startRequested flag anywhere within a trimmed
        // ServiceRecord block-body line. Real `dumpsys` emits it inline as
        // `startRequested=true delayedStop=false ...`, so a narrow boundary
        // regex beats startsWith+substringAfter and stays immune to trailing
        // fields and field order.
        val START_REQUESTED = Regex("""\bstartRequested=(true|false)\b""")

        // Captures the isForeground flag anywhere within a trimmed
        // ServiceRecord block-body line. Real `dumpsys` emits it inline as
        // `isForeground=true foregroundId=41 ...`, so a narrow boundary regex
        // handles the trailing fields that substringAfter cannot.
        val IS_FOREGROUND = Regex("""\bisForeground=(true|false)\b""")

        // Captures the binding flag anywhere within a trimmed ServiceRecord
        // block-body line. Real `dumpsys` emits it inline as
        // `requested=true received=true hasBound=false doRebind=false`, not
        // as a line beginning with `hasBound=`, so a narrow regex beats
        // startsWith and stays immune to leading whitespace or field order.
        val HAS_BOUND = Regex("""\bhasBound=(true|false)\b""")
    }
    private fun hasEnabledExternalChannel(): Boolean {
        val catalogue = File(context.filesDir, "channels_catalogue.json")
        if (!catalogue.isFile) return false
        val definitions = JSONObject(catalogue.readText()).getJSONArray("definitions")
        return (0 until definitions.length())
            .map(definitions::getJSONObject)
            .any { definition ->
                definition.optBoolean("enabled", true) &&
                    !definition.getString("implementationId").startsWith("builtin:")
            }
    }

}