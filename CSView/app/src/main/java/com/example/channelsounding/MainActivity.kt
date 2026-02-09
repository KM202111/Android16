package com.example.channelsounding

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.ranging.RangingManager
import android.ranging.raw.RawRangingDevice
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.channelsounding.ui.theme.ChannelSoundingTheme
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.RANGING
    )

    private val rasServiceUuid = UUID.fromString("0000185B-0000-1000-8000-00805F9B34FB")
    private val rasControlPointUuid = UUID.fromString("00002C17-0000-1000-8000-00805F9B34FB")

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var currentUpdateRate: Int = RawRangingDevice.UPDATE_RATE_NORMAL

    private var rangingManager: RangingManager? = null
    private var bluetoothController: BluetoothController? = null
    private var channelSoundingRanger: ChannelSoundingRanger? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val statusText = mutableStateOf("Idle")
    private val distanceText = mutableStateOf("-- m")
    private val connectionStateText = mutableStateOf("Idle")

    @android.annotation.SuppressLint("MissingPermission")
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT) { _ ->
        startFlow()
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED != intent.action) return
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val oldState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)

            if (device?.address == bluetoothController?.targetDevice?.address) {
                if (newState == BluetoothDevice.BOND_BONDED && oldState != BluetoothDevice.BOND_BONDED) {
                    updateStatus("Paired. Starting ranging...")
                    updateConnectionState("Paired")
                    channelSoundingRanger?.startRanging(device, currentUpdateRate)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        rangingManager = getSystemService(RangingManager::class.java)
        bluetoothController = BluetoothController(
            context = this,
            bluetoothAdapter = bluetoothAdapter,
            mainHandler = mainHandler,
            rasServiceUuid = rasServiceUuid,
            rasControlPointUuid = rasControlPointUuid,
            hasAllPermissions = { hasAllPermissions() },
            onStatus = { updateStatus(it) },
            onConnectionState = { updateConnectionState(it) }
        )
        channelSoundingRanger = ChannelSoundingRanger(
            rangingManager = rangingManager,
            mainExecutor = mainExecutor,
            hasRangingPermission = { hasRangingPermission() },
            onStatus = { updateStatus(it) },
            onConnectionState = { updateConnectionState(it) },
            onDistance = { distanceText.value = it }
        )

        ContextCompat.registerReceiver(
            this,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            ChannelSoundingTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ChannelSoundingScreen(
                        status = statusText.value,
                        connectionState = connectionStateText.value,
                        distance = distanceText.value,
                        onStart = @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT) {
                            if (ActivityCompat.checkSelfPermission(
                                this,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            // TODO: Consider calling
                            //    ActivityCompat#requestPermissions
                            // here to request the missing permissions, and then overriding
                            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                            //                                          int[] grantResults)
                            // to handle the case where the user grants the permission. See the documentation
                            // for ActivityCompat#requestPermissions for more details.
                            //return
                        }
                            startFlow() },
                        onRateNormal = { changeUpdateRate(RawRangingDevice.UPDATE_RATE_NORMAL) },
                        onRateFrequent = { changeUpdateRate(RawRangingDevice.UPDATE_RATE_FREQUENT) },
                        onRateInfrequent = { changeUpdateRate(RawRangingDevice.UPDATE_RATE_INFREQUENT) },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        //startFlow()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(bondReceiver)
        bluetoothController?.stopScan()
        bluetoothController?.closeGatt()
        channelSoundingRanger?.stopRanging()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun startFlow() {
        if (!hasAllPermissions()) {
            permissionLauncher.launch(requiredPermissions)
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateStatus("Bluetooth is disabled")
            return
        }

        val pm = packageManager
        if (!pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE_CHANNEL_SOUNDING)) {
            updateStatus("Channel Sounding not supported on this device")
            return
        }

        channelSoundingRanger?.stopRanging()
        bluetoothController?.closeGatt()
        bluetoothController?.stopScan()
        bluetoothController?.resetTarget()
        distanceText.value = "-- cm"
        updateConnectionState("Scanning")
        updateStatus("Scanning for nRF54L15 DK...")
        bluetoothController?.startScan()
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateStatus(message: String) {
        statusText.value = message
    }

    private fun updateConnectionState(message: String) {
        connectionStateText.value = message
    }

    private fun hasRangingPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RANGING
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun changeUpdateRate(newRate: Int) {
        if (currentUpdateRate == newRate) return
        currentUpdateRate = newRate
        val device = bluetoothController?.targetDevice
        if (device == null) {
            updateStatus("No device. Press Start first.")
            return
        }
        updateStatus("Restarting ranging with new rate...")
        channelSoundingRanger?.stopRanging()
        channelSoundingRanger?.startRanging(device, currentUpdateRate)
    }
}
