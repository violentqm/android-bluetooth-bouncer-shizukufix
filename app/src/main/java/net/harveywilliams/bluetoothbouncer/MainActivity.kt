package net.harveywilliams.bluetoothbouncer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import net.harveywilliams.bluetoothbouncer.ui.theme.BluetoothBouncerTheme

/**
 * Single Activity entry point. Hosts the Compose navigation graph.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BluetoothBouncerTheme {
                AppNavigation()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Re-check Shizuku while visible so starting Shizuku or granting permission is
        // picked up immediately, without restarting the app.
        (application as BluetoothBouncerApp).shizukuHelper.startMonitoring()
    }

    override fun onStop() {
        (application as BluetoothBouncerApp).shizukuHelper.stopMonitoring()
        super.onStop()
    }
}
