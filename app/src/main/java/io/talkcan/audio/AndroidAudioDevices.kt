package io.talkcan.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.talkcan.model.TARGET_DEVICE_NAME

internal const val ROUTE_LOG_TAG = "TalkcanRoute"

internal fun AudioDeviceInfo?.routeDebugString(): String {
    if (this == null) return "none"
    val product = productName?.toString()?.ifBlank { "<blank>" } ?: "null"
    return "id=$id type=${type.routeTypeDebugString()} product='$product' " +
        "sco=${isBluetoothScoEndpoint()} targetRsm=${isTargetRsmScoEndpoint()}"
}

internal fun Iterable<AudioDeviceInfo>.routeDebugString(): String =
    joinToString(prefix = "[", postfix = "]") { it.routeDebugString() }

internal fun Int.audioModeDebugString(): String = when (this) {
    AudioManager.MODE_NORMAL -> "$this/NORMAL"
    AudioManager.MODE_RINGTONE -> "$this/RINGTONE"
    AudioManager.MODE_IN_CALL -> "$this/IN_CALL"
    AudioManager.MODE_IN_COMMUNICATION -> "$this/IN_COMMUNICATION"
    AudioManager.MODE_CALL_SCREENING -> "$this/CALL_SCREENING"
    else -> "$this/unknown"
}

private fun Int.routeTypeDebugString(): String = when (this) {
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "$this/BLUETOOTH_SCO"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "$this/BLUETOOTH_A2DP"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "$this/BUILTIN_EARPIECE"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "$this/BUILTIN_MIC"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "$this/BUILTIN_SPEAKER"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "$this/WIRED_HEADPHONES"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "$this/WIRED_HEADSET"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "$this/USB_DEVICE"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "$this/USB_HEADSET"
    else -> "$this/unknown"
}

internal fun AudioDeviceInfo.isBluetoothScoEndpoint(): Boolean =
    type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

internal fun AudioDeviceInfo.isTargetRsmScoEndpoint(): Boolean =
    isBluetoothScoEndpoint() &&
        productName?.contains(TARGET_DEVICE_NAME, ignoreCase = true) == true

internal fun sameAudioDevice(left: AudioDeviceInfo, right: AudioDeviceInfo): Boolean =
    left.id == right.id && left.type == right.type
