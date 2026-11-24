package com.jarvis.assistant.audio

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import androidx.core.content.ContextCompat

/** Minimal immutable device descriptor so classification and routing are deterministically testable. */
data class AudioEndpoint(
    val id: Int,
    val type: Int,
    val productName: String = "",
    val platformDevice: AudioDeviceInfo? = null,
) {
    companion object {
        fun from(info: AudioDeviceInfo): AudioEndpoint = AudioEndpoint(
            id = info.id,
            type = info.type,
            productName = info.productName?.toString().orEmpty(),
            platformDevice = info,
        )
    }
}

/** Events observed from Android's audio & Bluetooth subsystems. */
interface AudioSystemObserver {
    fun onDevicesAdded(devices: List<AudioEndpoint>) {}
    fun onDevicesRemoved(devices: List<AudioEndpoint>) {}
    fun onCommunicationDeviceChanged(device: AudioEndpoint?) {}
    fun onLegacyScoStateChanged(state: Int) {}
    fun onBluetoothAdapterDisabled() {}
}

/**
 * Narrow platform boundary over [AudioManager] and Bluetooth state broadcasts.
 * Production code uses [AndroidAudioSystemGateway]; unit tests supply an in-memory fake.
 */
interface AudioSystemGateway {
    val sdkInt: Int
    val audioMode: Int
    val isMusicActive: Boolean
    val isMicrophoneMute: Boolean

    fun hasBluetoothConnectPermission(): Boolean
    fun getInputEndpoints(): List<AudioEndpoint>
    fun getOutputEndpoints(): List<AudioEndpoint>
    fun getAvailableCommunicationEndpoints(): List<AudioEndpoint>
    fun getActiveCommunicationEndpoint(): AudioEndpoint?
    fun setAudioMode(mode: Int)
    fun setCommunicationEndpoint(endpoint: AudioEndpoint): Boolean
    fun clearCommunicationEndpoint()
    fun isBluetoothScoAvailableOffCall(): Boolean
    fun startLegacyBluetoothSco()
    fun stopLegacyBluetoothSco()
    fun registerObserver(observer: AudioSystemObserver, handler: Handler)
    fun unregisterObserver()
    fun registerLegacyScoReceiver(observer: AudioSystemObserver)
    fun unregisterLegacyScoReceiver()
}

/** Production Android [AudioSystemGateway] guarding API 26–35 differences cleanly. */
class AndroidAudioSystemGateway(context: Context) : AudioSystemGateway {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private var deviceCallback: AudioDeviceCallback? = null
    private var commListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var btStateReceiver: BroadcastReceiver? = null
    private var scoReceiver: BroadcastReceiver? = null

    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override val audioMode: Int get() = audio?.mode ?: AudioManager.MODE_NORMAL
    override val isMusicActive: Boolean get() = audio?.isMusicActive == true
    override val isMicrophoneMute: Boolean get() = audio?.isMicrophoneMute == true

    override fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    override fun getInputEndpoints(): List<AudioEndpoint> =
        audio?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.map(AudioEndpoint::from).orEmpty()

    override fun getOutputEndpoints(): List<AudioEndpoint> =
        audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.map(AudioEndpoint::from).orEmpty()

    override fun getAvailableCommunicationEndpoints(): List<AudioEndpoint> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audio?.availableCommunicationDevices?.map(AudioEndpoint::from).orEmpty() }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }

    override fun getActiveCommunicationEndpoint(): AudioEndpoint? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audio?.communicationDevice?.let(AudioEndpoint::from) }.getOrNull()
        } else {
            null
        }

    override fun setAudioMode(mode: Int) {
        runCatching { audio?.mode = mode }
    }

    override fun setCommunicationEndpoint(endpoint: AudioEndpoint): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val device = endpoint.platformDevice ?: return false
        return runCatching { audio?.setCommunicationDevice(device) == true }.getOrDefault(false)
    }

    override fun clearCommunicationEndpoint() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audio?.clearCommunicationDevice() }
        }
    }

    @Suppress("DEPRECATION")
    override fun isBluetoothScoAvailableOffCall(): Boolean =
        runCatching { audio?.isBluetoothScoAvailableOffCall == true }.getOrDefault(false)

    @Suppress("DEPRECATION")
    override fun startLegacyBluetoothSco() {
        runCatching { audio?.startBluetoothSco() }
    }

    @Suppress("DEPRECATION")
    override fun stopLegacyBluetoothSco() {
        runCatching { audio?.stopBluetoothSco() }
    }

    override fun registerObserver(observer: AudioSystemObserver, handler: Handler) {
        unregisterObserver()
        val am = audio ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                val list = addedDevices?.map(AudioEndpoint::from).orEmpty()
                if (list.isNotEmpty()) observer.onDevicesAdded(list)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                val list = removedDevices?.map(AudioEndpoint::from).orEmpty()
                if (list.isNotEmpty()) observer.onDevicesRemoved(list)
            }
        }
        runCatching {
            am.registerAudioDeviceCallback(callback, handler)
            deviceCallback = callback
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                observer.onCommunicationDeviceChanged(device?.let(AudioEndpoint::from))
            }
            runCatching {
                am.addOnCommunicationDeviceChangedListener(app.mainExecutor, listener)
                commListener = listener
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    observer.onBluetoothAdapterDisabled()
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            btStateReceiver = receiver
        }
    }

    override fun unregisterObserver() {
        val am = audio
        deviceCallback?.let { cb -> runCatching { am?.unregisterAudioDeviceCallback(cb) } }
        deviceCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            commListener?.let { listener -> runCatching { am?.removeOnCommunicationDeviceChangedListener(listener) } }
        }
        commListener = null
        btStateReceiver?.let { r -> runCatching { app.unregisterReceiver(r) } }
        btStateReceiver = null
        unregisterLegacyScoReceiver()
    }

    @Suppress("DEPRECATION")
    override fun registerLegacyScoReceiver(observer: AudioSystemObserver) {
        unregisterLegacyScoReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                observer.onLegacyScoStateChanged(state)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                ContextCompat.RECEIVER_EXPORTED,
            )
            scoReceiver = receiver
        }
    }

    override fun unregisterLegacyScoReceiver() {
        scoReceiver?.let { r -> runCatching { app.unregisterReceiver(r) } }
        scoReceiver = null
    }
}
