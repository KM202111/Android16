package com.example.channelsounding

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import java.util.UUID

class BluetoothController(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val mainHandler: Handler,
    private val rasServiceUuid: UUID,
    private val rasControlPointUuid: UUID,
    private val hasAllPermissions: () -> Boolean,
    private val onStatus: (String) -> Unit,
    private val onConnectionState: (String) -> Unit
) {
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null

    var targetDevice: BluetoothDevice? = null
        private set

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasAllPermissions()) return

        scanner = bluetoothAdapter?.bluetoothLeScanner
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(rasServiceUuid))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (targetDevice != null) return
                val device = result.device ?: return
                targetDevice = device
                onConnectionState("Connecting")
                onStatus("Found: ${device.address}. Connecting...")
                stopScan()
                connectGatt(device)
            }

            override fun onScanFailed(errorCode: Int) {
                onStatus("Scan failed: $errorCode")
            }
        }

        scanner?.startScan(listOf(scanFilter), settings, scanCallback)
        mainHandler.postDelayed({
            if (targetDevice == null) {
                onStatus("Scan timeout")
                stopScan()
            }
        }, 15_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { callback ->
            scanner?.stopScan(callback)
        }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        targetDevice = device
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onConnectionState("GATT connected")
                onStatus("GATT connected. Discovering services...")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onConnectionState("Disconnected")
                onStatus("GATT disconnected")
                closeGatt()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onConnectionState("GATT error")
                onStatus("Service discovery failed: $status")
                return
            }
            val service = gatt.getService(rasServiceUuid)
            val controlPoint = service?.getCharacteristic(rasControlPointUuid)
            if (controlPoint == null) {
                onConnectionState("RAS not found")
                onStatus("RAS Control Point not found")
                return
            }
            onConnectionState("Pairing")
            onStatus("Triggering pairing...")
            triggerPairing(gatt, controlPoint)
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerPairing(gatt: BluetoothGatt, controlPoint: BluetoothGattCharacteristic) {
        val payload = byteArrayOf(0x00)
        gatt.writeCharacteristic(controlPoint, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun closeGatt() {
        gatt?.close()
        gatt = null
    }

    fun resetTarget() {
        targetDevice = null
    }
}
