package dev.xvin.palimpsest.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class OutputDevice(val key: String, val label: String)

/**
 * Tracks which output Android will route music to (headphones beat the speaker), so each pair
 * of headphones can have its own EQ profile.
 */
class OutputDevices(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _current = MutableStateFlow(detect())
    val current: StateFlow<OutputDevice> = _current

    init {
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { _current.value = detect() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { _current.value = detect() }
        }, Handler(Looper.getMainLooper()))
    }

    private fun detect(): OutputDevice {
        val outs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val best = outs.maxByOrNull { priority(it.type) }?.takeIf { priority(it.type) > 0 }
            ?: return OutputDevice("speaker", "Динамик телефона")
        val name = best.productName?.toString()?.takeIf { it.isNotBlank() } ?: kind(best.type)
        return OutputDevice("${best.type}:$name", name)
    }

    private fun priority(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 4
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 3
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
        else -> 0
    }

    private fun kind(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Проводные наушники"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB-аудио"
        else -> "Bluetooth"
    }
}
