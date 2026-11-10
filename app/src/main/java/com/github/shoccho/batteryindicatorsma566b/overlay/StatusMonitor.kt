package com.github.shoccho.batteryindicatorsma566b.overlay

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.util.Log
import com.github.shoccho.batteryindicatorsma566b.core.MobileState
import com.github.shoccho.batteryindicatorsma566b.core.RingerState
import com.github.shoccho.batteryindicatorsma566b.core.StatusState
import com.github.shoccho.batteryindicatorsma566b.core.WifiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Collects everything the "Own icons" row shows, from public APIs only. Each source is optional:
 * if a permission is missing (Phone, Nearby devices) that icon simply shows less detail.
 */
class StatusMonitor(private val context: Context) {

    private val _state = MutableStateFlow(StatusState())
    val state: StateFlow<StatusState> = _state.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val bluetooth: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var started = false
    private var polling = false
    private val vpnNetworks = mutableSetOf<Network>()
    private val mobile = linkedMapOf<Int, MobileState>()
    private val telephonyCallbacks = mutableListOf<Pair<TelephonyManager, TelephonyCallback>>()
    private var lastRx = -1L
    private var lastTx = -1L

    // ---- lifecycle ----------------------------------------------------------------------------

    fun start() {
        if (started) return
        started = true
        refreshSimple()
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED)
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_TETHER_STATE_CHANGED)
            addAction(ACTION_WIFI_AP_STATE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)?.let { receiver.onReceive(context, it) }
        safely { cm.registerDefaultNetworkCallback(defaultCallback, handler) }
        safely {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                wifiCallback, handler,
            )
        }
        safely {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                vpnCallback, handler,
            )
        }
        registerTelephony()
    }

    fun stop() {
        if (!started) return
        started = false
        setPolling(false)
        safely { context.unregisterReceiver(receiver) }
        safely { cm.unregisterNetworkCallback(defaultCallback) }
        safely { cm.unregisterNetworkCallback(wifiCallback) }
        safely { cm.unregisterNetworkCallback(vpnCallback) }
        telephonyCallbacks.forEach { (tm, cb) -> safely { tm.unregisterTelephonyCallback(cb) } }
        telephonyCallbacks.clear()
        vpnNetworks.clear()
    }

    /** Activity arrows poll traffic counters once a second — only while the row is on screen. */
    fun setPolling(on: Boolean) {
        if (on == polling) return
        polling = on
        handler.removeCallbacks(pollTraffic)
        if (on) {
            lastRx = -1L
            handler.post(pollTraffic)
        } else {
            _state.update { it.copy(activityIn = false, activityOut = false) }
        }
    }

    // ---- simple broadcasts --------------------------------------------------------------------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                ACTION_TETHER_STATE_CHANGED -> {
                    val active = intent.getStringArrayListExtra("tetherArray").orEmpty()
                    _state.update { it.copy(hotspot = active.isNotEmpty() || wifiApOn) }
                }
                ACTION_WIFI_AP_STATE_CHANGED -> {
                    wifiApOn = intent.getIntExtra("wifi_state", 0) == WIFI_AP_STATE_ENABLED
                    _state.update { it.copy(hotspot = wifiApOn) }
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    refreshSimple()
                    registerTelephony()
                }
                else -> refreshSimple()
            }
        }
    }
    private var wifiApOn = false

    private fun refreshSimple() {
        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> RingerState.SILENT
            AudioManager.RINGER_MODE_VIBRATE -> RingerState.VIBRATE
            else -> RingerState.NORMAL
        }
        val dnd = notifications.currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val nfcOn = runCatching { NfcAdapter.getDefaultAdapter(context)?.isEnabled == true }.getOrDefault(false)
        val btOn = runCatching { bluetooth?.isEnabled == true }.getOrDefault(false)
        val btConnected = btOn && hasPermission(Manifest.permission.BLUETOOTH_CONNECT) && runCatching {
            listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO, BluetoothProfile.HEARING_AID)
                .any { bluetooth?.getProfileConnectionState(it) == BluetoothProfile.STATE_CONNECTED }
        }.getOrDefault(false)
        _state.update {
            it.copy(
                ringer = ringer,
                dnd = dnd,
                airplane = airplane,
                alarm = alarms.nextAlarmClock != null,
                dataSaver = cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                nfc = nfcOn,
                bluetoothOn = btOn,
                bluetoothConnected = btConnected,
            )
        }
    }

    // ---- networks ------------------------------------------------------------------------------

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val mobileDefault = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            _state.update {
                it.copy(
                    mobileIsDefault = mobileDefault,
                    wifi = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        it.wifi.copy(validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                    } else it.wifi,
                )
            }
        }

        override fun onLost(network: Network) {
            _state.update { it.copy(mobileIsDefault = false) }
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val rssi = caps.signalStrength
            val max = wifiManager.maxSignalLevel.coerceAtLeast(1)
            val level = if (rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) 4
            else (wifiManager.calculateSignalLevel(rssi) * 4 + max / 2) / max
            val info = caps.transportInfo as? WifiInfo
            val generation = info?.wifiStandard?.let(::wifiGeneration)
            val sixGhz = (info?.frequency ?: 0) >= 5925
            _state.update {
                it.copy(
                    wifi = it.wifi.copy(
                        connected = true, level = level.coerceIn(0, 4), generation = generation, sixGhz = sixGhz,
                    ),
                )
            }
        }

        override fun onLost(network: Network) {
            _state.update { it.copy(wifi = WifiState()) }
        }
    }

    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            vpnNetworks += network
            _state.update { it.copy(vpn = true) }
        }

        override fun onLost(network: Network) {
            vpnNetworks -= network
            _state.update { it.copy(vpn = vpnNetworks.isNotEmpty()) }
        }
    }

    private val pollTraffic = object : Runnable {
        override fun run() {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            if (lastRx >= 0 && rx >= 0 && tx >= 0) {
                val inActive = rx - lastRx > ACTIVITY_BYTES
                val outActive = tx - lastTx > ACTIVITY_BYTES
                _state.update {
                    if (it.activityIn == inActive && it.activityOut == outActive) it
                    else it.copy(activityIn = inActive, activityOut = outActive)
                }
            }
            lastRx = rx
            lastTx = tx
            if (polling) handler.postDelayed(this, 1000)
        }
    }

    // ---- mobile --------------------------------------------------------------------------------

    /** One signal icon per active SIM; needs the Phone permission to see both SIMs and 5G/LTE. */
    private fun registerTelephony() {
        telephonyCallbacks.forEach { (tm, cb) -> safely { tm.unregisterTelephonyCallback(cb) } }
        telephonyCallbacks.clear()
        mobile.clear()
        val subIds = if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            runCatching {
                context.getSystemService(SubscriptionManager::class.java)
                    .activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }
            }.getOrDefault(emptyList())
        } else emptyList()
        val targets = subIds.ifEmpty { listOf(SubscriptionManager.getDefaultSubscriptionId()) }
        for (subId in targets) {
            val tm = if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) telephony
            else telephony.createForSubscriptionId(subId)
            mobile[subId] = MobileState()
            val basic = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener,
                TelephonyCallback.ServiceStateListener {
                override fun onSignalStrengthsChanged(signal: SignalStrength) =
                    updateMobile(subId) { it.copy(level = signal.level.coerceIn(0, 4)) }

                override fun onServiceStateChanged(ss: ServiceState) =
                    updateMobile(subId) { it.copy(inService = ss.state == ServiceState.STATE_IN_SERVICE, roaming = ss.roaming) }
            }
            safely {
                tm.registerTelephonyCallback(context.mainExecutor, basic)
                telephonyCallbacks += tm to basic
            }
            if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
                val display = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                    override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) =
                        updateMobile(subId) { it.copy(typeLabel = networkLabel(info)) }
                }
                safely {
                    tm.registerTelephonyCallback(context.mainExecutor, display)
                    telephonyCallbacks += tm to display
                }
            }
        }
        publishMobile()
    }

    private fun updateMobile(subId: Int, change: (MobileState) -> MobileState) {
        mobile[subId] = change(mobile[subId] ?: MobileState())
        publishMobile()
    }

    private fun publishMobile() {
        val list = mobile.values.toList().ifEmpty { listOf(MobileState(inService = false)) }
        _state.update { it.copy(mobile = list) }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun hasPermission(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "status source unavailable: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "StatusMonitor"
        private const val ACTIVITY_BYTES = 512L

        // Sticky system broadcasts without public constants.
        private const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
        private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val WIFI_AP_STATE_ENABLED = 13

        fun wifiGeneration(standard: Int): Int? = when (standard) {
            ScanResult.WIFI_STANDARD_11N -> 4
            ScanResult.WIFI_STANDARD_11AC -> 5
            ScanResult.WIFI_STANDARD_11AX -> 6
            ScanResult.WIFI_STANDARD_11BE -> 7
            else -> null
        }

        fun networkLabel(info: TelephonyDisplayInfo): String? = when (info.overrideNetworkType) {
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G"
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "4G+"
            else -> when (info.networkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"
                TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
                TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA -> "H"
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE -> "E"
                TelephonyManager.NETWORK_TYPE_GPRS -> "G"
                else -> null
            }
        }
    }
}
