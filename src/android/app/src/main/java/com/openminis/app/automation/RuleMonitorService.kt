package com.openminis.app.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openminis.app.R
import com.openminis.app.logging.AppLogger

/**
 * [T-android-automation-rules] Lightweight foreground service that only
 * exists while some rule needs runtime-registered broadcasts (Wi-Fi,
 * Bluetooth, power, battery, screen on/off/unlock). It never touches the
 * database directly — every event funnels through [AutomationEvents] into
 * [AutomationRuleManager]. Started/stopped exclusively by the manager's
 * sync(); manifest declaration (specialUse FGS + the
 * FOREGROUND_SERVICE_SPECIAL_USE permission) is owned by the manifest owner.
 *
 * Sticky-broadcast policy (same as DSH's RuleMonitorService): battery replays
 * its current state on registration — that is the BASELINE, not a transition,
 * otherwise every monitor start would fire "unplugged" / "battery low" rules
 * out of nowhere.
 */
class RuleMonitorService : Service() {

    private var lastBattery: Int? = null
    private var lastCharging: Boolean? = null
    /** False until the first network callback: the current Wi-Fi is the baseline. */
    private var wifiSeeded = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val sticky = isInitialStickyBroadcast
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED ->
                    AutomationEvents.emit(AutomationEvent.Power(true))
                Intent.ACTION_POWER_DISCONNECTED ->
                    AutomationEvents.emit(AutomationEvent.Power(false))
                Intent.ACTION_SCREEN_ON ->
                    AutomationEvents.emit(AutomationEvent.Screen("on"))
                Intent.ACTION_SCREEN_OFF ->
                    AutomationEvents.emit(AutomationEvent.Screen("off"))
                Intent.ACTION_USER_PRESENT ->
                    AutomationEvents.emit(AutomationEvent.Screen("unlock"))
                Intent.ACTION_BATTERY_CHANGED -> {
                    val pct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 /
                        i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    val prevPct = lastBattery
                    if (sticky) {
                        // Baseline only — never a transition.
                        lastBattery = pct
                        lastCharging = charging
                        return
                    }
                    if (pct == prevPct && charging == lastCharging) return
                    lastBattery = pct
                    lastCharging = charging
                    AutomationEvents.emit(AutomationEvent.Battery(pct, prevPct, charging))
                }
            }
        }
    }

    /**
     * Bluetooth ACL broadcasts come from the Bluetooth process (its own uid),
     * which a NOT_EXPORTED receiver refuses; they are protected by
     * BLUETOOTH_CONNECT on Android 12+, so only the system can send them to
     * us. Hence a separate, exported receiver. Without BLUETOOTH_CONNECT
     * granted the device name is unavailable (null) — rules without a
     * deviceName filter still fire.
     */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != BluetoothDevice.ACTION_ACL_CONNECTED &&
                i.action != BluetoothDevice.ACTION_ACL_DISCONNECTED
            ) return
            @Suppress("DEPRECATION")
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val canName = Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            val name = if (canName) runCatching { d?.name }.getOrNull() else null
            AutomationEvents.emit(
                AutomationEvent.Bluetooth(i.action == BluetoothDevice.ACTION_ACL_CONNECTED, name),
            )
        }
    }

    private fun onWifi(caps: NetworkCapabilities) {
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 31) caps.transportInfo as? WifiInfo
        else runCatching { getSystemService(WifiManager::class.java).connectionInfo }.getOrNull()
        val ssid = info?.ssid?.trim('"')
            ?.takeIf { it != "<unknown ssid>" && it.isNotEmpty() }
        if (!wifiSeeded) {
            wifiSeeded = true
            return
        }
        AutomationEvents.emit(AutomationEvent.Wifi(true, ssid))
    }

    // NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) is API 31+; constructing it
    // on older APIs crashed the service in the reference implementation.
    private val netCallback: ConnectivityManager.NetworkCallback =
        if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifi(caps)

                override fun onLost(network: Network) {
                    wifiSeeded = true
                    AutomationEvents.emit(AutomationEvent.Wifi(false, null))
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    onWifi(caps)

                override fun onLost(network: Network) {
                    wifiSeeded = true
                    AutomationEvents.emit(AutomationEvent.Wifi(false, null))
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        running = true
        if (!startMonitorForeground()) return
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                listOf(
                    Intent.ACTION_POWER_CONNECTED,
                    Intent.ACTION_POWER_DISCONNECTED,
                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_SCREEN_OFF,
                    Intent.ACTION_USER_PRESENT,
                    Intent.ACTION_BATTERY_CHANGED,
                ).forEach(::addAction)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            btReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            // Already on Wi-Fi: the first callback reports it and is only the
            // baseline. Not on Wi-Fi: the first callback IS a connect event.
            wifiSeeded = cm.allNetworks.none {
                cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                netCallback,
            )
        }.onFailure { AppLogger.warning(TAG, "network callback registration failed: ${it.message}") }
    }

    private fun startMonitorForeground(): Boolean {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.automation_monitor_notification))
            .setOngoing(true)
            .setSilent(true)
            .build()
        return runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.onFailure {
            AppLogger.warning(TAG, "startForeground refused: ${it.message}")
            lastStartRefused = true
            stopSelf()
        }.isSuccess
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN,
            ).apply { description = getString(R.string.automation_monitor_notification) },
        )
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(receiver) }
        runCatching { unregisterReceiver(btReceiver) }
        runCatching {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "RuleMonitorService"
        const val CHANNEL_ID = "minis_automation_monitor"
        private const val CHANNEL_NAME = "Automation Monitor"
        private const val NOTIFICATION_ID = 0x4175746f.toInt() and 0x7FFFFFFF

        /** Whether the monitor is up (observability). */
        @Volatile
        var running = false
            private set

        /** Set when Android refused to start it from the background. */
        @Volatile
        var lastStartRefused = false
            private set

        /**
         * Start or stop the monitor so it exists exactly while some enabled
         * rule needs it. Never throws.
         */
        fun setRunning(context: Context, running: Boolean) {
            val i = Intent(context, RuleMonitorService::class.java)
            if (running) {
                lastStartRefused = runCatching { context.startForegroundService(i) }
                    .onFailure { AppLogger.warning(TAG, "start refused: ${it.message}") }
                    .isFailure
            } else {
                runCatching { context.stopService(i) }
            }
        }
    }
}
