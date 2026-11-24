package dev.igun997.fluteisland.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One SIM's signal. [level] is 0..4, or -1 when there is no service. */
data class SimBars(val slot: Int, val level: Int, val name: String = "")

data class StatusState(
    /** -1 = wifi off / not connected, otherwise 0..4. */
    val wifiLevel: Int = -1,
    val sims: List<SimBars> = emptyList(),
    val batteryPercent: Int = 100,
    val charging: Boolean = false,
)

/**
 * The few status-bar facts the cover bar draws: wifi, signal for each SIM, battery. Everything is registered only while
 * the cover is switched on and released as soon as it is off, so it costs nothing otherwise.
 */
class StatusIndicators(private val context: Context) {
    private val _state = MutableStateFlow(StatusState())
    val state: StateFlow<StatusState> = _state.asStateFlow()

    private var started = false
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    private val mainExecutor = context.mainExecutor

    private val levels = HashMap<Int, Int>()          // slot -> bars
    private val names = HashMap<Int, String>()        // slot -> carrier
    private val modernCallbacks = ArrayList<Pair<TelephonyManager, TelephonyCallback>>()
    @Suppress("DEPRECATION")
    private val legacyListeners = ArrayList<Pair<TelephonyManager, PhoneStateListener>>()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = readBattery(i)
    }
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val rssi = caps.signalStrength
            val level = if (rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) 3 else when {
                rssi > -55 -> 4; rssi > -65 -> 3; rssi > -75 -> 2; rssi > -85 -> 1; else -> 0
            }
            _state.value = _state.value.copy(wifiLevel = level)
        }

        override fun onLost(network: Network) {
            _state.value = _state.value.copy(wifiLevel = -1)
        }
    }
    private val subscriptionListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() = registerSims()
    }

    fun start() {
        if (started) return
        started = true
        readBattery(context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        runCatching {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback)
        }
        runCatching { subscriptions.addOnSubscriptionsChangedListener(mainExecutor, subscriptionListener) }
        registerSims()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(batteryReceiver) }
        runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
        runCatching { subscriptions.removeOnSubscriptionsChangedListener(subscriptionListener) }
        unregisterSims()
    }

    private fun readBattery(i: Intent?) {
        i ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        _state.value = _state.value.copy(
            batteryPercent = if (level >= 0) level * 100 / scale else _state.value.batteryPercent,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }

    // --- SIMs. With the phone permission every active SIM gets its own entry; without it we can only see the default one. ---

    private fun hasPhoneState() = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun registerSims() {
        unregisterSims()
        levels.clear()
        names.clear()
        val managers: List<Pair<Int, TelephonyManager>> = if (hasPhoneState()) {
            val subs = runCatching { subscriptions.activeSubscriptionInfoList }.getOrNull().orEmpty().sortedBy { it.simSlotIndex }
            subs.forEach { names[it.simSlotIndex] = it.carrierName?.toString().orEmpty() }
            subs.map { it.simSlotIndex to telephony.createForSubscriptionId(it.subscriptionId) }
        } else {
            names[0] = runCatching { telephony.networkOperatorName }.getOrDefault("")
            listOf(0 to telephony)
        }
        managers.forEach { (slot, tm) -> listen(slot, tm) }
        publishSims(managers.map { it.first })
    }

    private fun listen(slot: Int, tm: TelephonyManager) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val cb = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener, TelephonyCallback.ServiceStateListener {
                    override fun onSignalStrengthsChanged(s: SignalStrength) = update(slot, s.level)
                    override fun onServiceStateChanged(s: ServiceState) { if (s.state != ServiceState.STATE_IN_SERVICE) update(slot, -1) }
                }
                tm.registerTelephonyCallback(mainExecutor, cb)
                modernCallbacks += tm to cb
            } else {
                @Suppress("DEPRECATION")
                val l = object : PhoneStateListener() {
                    override fun onSignalStrengthsChanged(s: SignalStrength) = update(slot, s.level)
                    override fun onServiceStateChanged(s: ServiceState) { if (s.state != ServiceState.STATE_IN_SERVICE) update(slot, -1) }
                }
                @Suppress("DEPRECATION")
                tm.listen(l, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS or PhoneStateListener.LISTEN_SERVICE_STATE)
                legacyListeners += tm to l
            }
        }
        // Seed with whatever the system already knows so the bars are right before the first callback.
        runCatching { update(slot, tm.signalStrength?.level ?: -1) }
    }

    private fun update(slot: Int, level: Int) {
        levels[slot] = level
        publishSims(levels.keys.sorted())
    }

    private fun publishSims(slots: List<Int>) {
        _state.value = _state.value.copy(sims = slots.map { SimBars(it, levels[it] ?: -1, names[it].orEmpty()) })
    }

    private fun unregisterSims() {
        modernCallbacks.forEach { (tm, cb) -> runCatching { tm.unregisterTelephonyCallback(cb) } }
        modernCallbacks.clear()
        @Suppress("DEPRECATION")
        legacyListeners.forEach { (tm, l) -> runCatching { tm.listen(l, PhoneStateListener.LISTEN_NONE) } }
        legacyListeners.clear()
    }
}
