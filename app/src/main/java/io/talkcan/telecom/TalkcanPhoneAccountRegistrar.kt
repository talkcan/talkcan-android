package io.talkcan.telecom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import io.talkcan.R

internal class TalkcanPhoneAccountRegistrar(
    private val context: Context,
) {
    private val telecom: TelecomManager?
        get() = context.getSystemService(TelecomManager::class.java)

    val handle: PhoneAccountHandle
        get() = PhoneAccountHandle(
            ComponentName(context, TalkcanConnectionService::class.java),
            PHONE_ACCOUNT_ID,
        )

    fun register() {
        val account = PhoneAccount.builder(handle, context.getString(R.string.app_name))
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setShortDescription(context.getString(R.string.telecom_phone_account_description))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_stat_talkcan))
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_SIP))
            .build()
        telecom?.registerPhoneAccount(account)
    }

    fun isEnabled(): Boolean = runCatching {
        telecom?.getPhoneAccount(handle)?.isEnabled != false
    }.getOrDefault(true)

    fun setupIntent(): Intent = if (Build.VERSION.SDK_INT >= 26) {
        Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
    } else {
        Intent(Settings.ACTION_SETTINGS)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun callAddress(): Uri = Uri.fromParts(PhoneAccount.SCHEME_SIP, "car-ptt@talkcan.local", null)

    companion object {
        const val PHONE_ACCOUNT_ID = "talkcan-car-ptt"
    }
}
