package com.itantra.walkie

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.itantra.walkie.ui.Console
import com.itantra.walkie.ui.SetupPanel
import com.itantra.walkie.ui.VaniColors
import com.itantra.walkie.ui.VaniTheme

/**
 * The shell. It owns the three things Compose cannot: the microphone permission, the radio
 * permissions, and the system dialog that is the only legal way to switch Bluetooth on from an
 * app. Everything above it is the console.
 */
class MainActivity : ComponentActivity() {

    private val vm: WalkieViewModel by viewModels()

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.setStatus("microphone permission denied · typing still works")
    }

    private val radioPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val all = granted.values.all { it }
        vm.setStatus(
            if (all) "radio allowed · listening for phones"
            else "radio permission denied · no phone can be seen"
        )
        if (all) {
            ensureNotifications()
            ensureAwake()
        }
        vm.bringUpMesh()
    }

    private val enableRadio = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.setStatus(
            if (r.resultCode == RESULT_OK) "bluetooth on · listening for phones"
            else "bluetooth left off · no phone can be reached"
        )
        bringUpRadio()
    }

    /**
     * Android 13+ will not show the listening notification without this, and the notification is
     * what makes a permanent radio honest. Refusing it costs a line in the shade, not the mesh.
     */
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun ensureNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) runCatching { notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    /**
     * Asked for by default, because coordinates are what a rescue team can act on and this app has
     * no network to fall back on. Denied, the product still works - it just cannot say where, and
     * the Mesh pane says so instead of showing a dot that is not a position.
     */
    private val locPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.setStatus("location denied · phones still hear each other, no position")
        else vm.bringUpMesh()
    }

    private fun ensureLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) runCatching { locPerm.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }

    /**
     * Doze stops a background scan on most skins, which silently turns a walkie into an app that
     * only works while it is open. Asked once, and once only: a refusal is a legitimate answer, and
     * the console says what it costs instead of nagging.
     */
    private fun ensureAwake() {
        val p = getSharedPreferences("vani", android.content.Context.MODE_PRIVATE)
        if (p.getBoolean("awake-asked", false)) return
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        p.edit().putBoolean("awake-asked", true).apply()
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:$packageName"))
            )
        }
    }

    private fun ensureMic(): Boolean {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!ok) micPerm.launch(Manifest.permission.RECORD_AUDIO)
        return ok
    }

    /** What the radio needs on this Android version, and nothing beyond it. */
    private fun radioPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** @return true when every radio permission is already held. */
    private fun ensureRadio(): Boolean {
        val missing = radioPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) radioPerm.launch(missing.toTypedArray())
        return missing.isEmpty()
    }

    /** Permissions first, then the radio itself: asking in the other order just throws. */
    private fun bringUpRadio() {
        if (ensureRadio()) {
            ensureNotifications()
            ensureAwake()
            ensureLocation()
            vm.bringUpMesh()
        }
    }

    /**
     * An app may not switch Bluetooth on silently on Android 12+: the platform requires the user
     * to confirm, so the console asks rather than pretending it can reach the switch.
     */
    private fun requestRadio() {
        if (!ensureRadio()) return
        runCatching { enableRadio.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            .onFailure { vm.setStatus("this phone cannot switch Bluetooth on") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        vm.initModels(intent?.getStringExtra("debug"))
        // Loud by default so an incoming line carries across rain and generators - and the
        // observer is what makes "the user can turn it down" true rather than a promise.
        com.itantra.walkie.audio.Loudness.ensure(this, vm.ui.loudInbound)
        com.itantra.walkie.audio.Loudness.watchForUser(this) {
            vm.setStatus("volume set by you · VANI will not raise it again")
        }
        setContent {
            VaniTheme {
                val setup = !vm.ui.setupDone
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(VaniColors.Ground)
                        .imePadding()
                ) {
                    if (setup) {
                        // The setup pane has no chrome of its own, so it takes the safe-drawing
                        // box directly. The console places its own strips against the cutout and
                        // the gesture inset instead.
                        Column(
                            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                        ) {
                            SetupPanel(vm, firstRun = true, onDone = { })
                        }
                    } else {
                        Console(vm, ::requestRadio, ::ensureRadio, ::ensureMic)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the console is the moment a field team expects the mesh to have settled.
        if (vm.ui.setupDone) bringUpRadio()
    }
}
