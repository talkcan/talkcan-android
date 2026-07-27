package io.talkcan

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.talkcan.model.OfflineNavigationVoiceIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side contract tests for the setup intent chosen from the engine package
 * discovered by bootstrap. This is instrumentation rather than a host JVM test:
 * Android's package visibility rules and installed TTS-data activities are part of
 * the behavior being verified.
 *
 * [MainActivity.resolveVoiceSetupIntent] is private and has no public observable
 * surface outside the Compose activity-result launcher. The reflection boundary is
 * therefore deliberately limited to invoking that pure intent-selection helper;
 * assertions inspect only the returned Android [Intent]. Attaching a base context
 * avoids launching the activity and its foreground service for a package-manager
 * contract that does not require an activity lifecycle.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityVoiceSetupIntentInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun resolvableEngineDataInstallersRemainScopedToTheirPassedEnginePackage() {
        val enginePackages = resolvableEnginePackages()
        assertFalse(
            "The device must expose at least one visible TTS engine with an install-data activity",
            enginePackages.isEmpty(),
        )

        enginePackages.forEach { enginePackage ->
            val intent = resolveVoiceSetupIntent(
                OfflineNavigationVoiceIssue(
                    diagnostic = "Offline English voice is missing",
                    enginePackage = enginePackage,
                ),
            )

            assertEquals(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA, intent.action)
            assertEquals(enginePackage, intent.`package`)
            assertNoProhibitedVoiceSettingsIntent(intent)
        }
    }

    @Test
    fun unresolvableEngineDataInstallerFallsBackToGeneralSettings() {
        val intent = resolveVoiceSetupIntent(
            OfflineNavigationVoiceIssue(
                diagnostic = "Offline English voice is missing",
                enginePackage = "io.talkcan.test.no-tts-data-installer",
            ),
        )

        assertEquals(Settings.ACTION_SETTINGS, intent.action)
        assertEquals(null, intent.`package`)
        assertNoProhibitedVoiceSettingsIntent(intent)
    }

    private fun resolvableEnginePackages(): List<String> =
        context.packageManager.queryIntentServices(
            Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
            .filter { enginePackage ->
                context.packageManager.queryIntentActivities(
                    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(enginePackage),
                    PackageManager.MATCH_DEFAULT_ONLY,
                ).isNotEmpty()
            }

    private fun resolveVoiceSetupIntent(issue: OfflineNavigationVoiceIssue?): Intent =
        io.talkcan.resolveVoiceSetupIntent(context, issue)

    private fun assertNoProhibitedVoiceSettingsIntent(intent: Intent) {
        assertFalse(intent.action == "com.android.settings.TTS_SETTINGS")
        assertFalse(intent.action == Settings.ACTION_VOICE_INPUT_SETTINGS)
    }
}
