package com.example.channelsounding

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.ranging.RangingData
import android.ranging.RangingDevice
import android.ranging.RangingManager
import android.ranging.RangingPreference
import android.ranging.RangingSession
import android.ranging.SensorFusionParams
import android.ranging.SessionConfig
import android.ranging.ble.cs.BleCsRangingCapabilities
import android.ranging.ble.cs.BleCsRangingParams
import android.ranging.raw.RawInitiatorRangingConfig
import android.ranging.raw.RawRangingDevice
import java.util.UUID
import java.util.concurrent.Executor

class ChannelSoundingRanger(
    private val rangingManager: RangingManager?,
    private val mainExecutor: Executor,
    private val hasRangingPermission: () -> Boolean,
    private val onStatus: (String) -> Unit,
    private val onConnectionState: (String) -> Unit,
    private val onDistance: (String) -> Unit
) {
    private var rangingSession: RangingSession? = null
    private var capabilitiesCallback: RangingManager.RangingCapabilitiesCallback? = null

    fun startRanging(device: BluetoothDevice?, updateRate: Int) {
        val manager = rangingManager
        if (manager == null) {
            onStatus("RangingManager unavailable")
            return
        }

        if (!BluetoothAdapter.checkBluetoothAddress(device?.address)) {
            onStatus("Invalid Bluetooth address")
            return
        }
        if (!hasRangingPermission()) {
            onStatus("Missing RANGING permission")
            return
        }

        val rangingDevice = RangingDevice.Builder()
            .setUuid(UUID.nameUUIDFromBytes(device?.address!!.toByteArray()))
            .build()

        val csParams = BleCsRangingParams.Builder(device.address)
            .setRangingUpdateRate(updateRate)
            .setSecurityLevel(BleCsRangingCapabilities.CS_SECURITY_LEVEL_ONE)
            .setLocationType(BleCsRangingParams.LOCATION_TYPE_UNKNOWN)
            .setSightType(BleCsRangingParams.SIGHT_TYPE_UNKNOWN)
            .build()

        val rawDevice = RawRangingDevice.Builder()
            .setRangingDevice(rangingDevice)
            .setCsRangingParams(csParams)
            .build()

        val config = RawInitiatorRangingConfig.Builder()
            .addRawRangingDevice(rawDevice)
            .build()

        val sensorFusionParams = SensorFusionParams.Builder()
            .setSensorFusionEnabled(true)
            .build()

        val sessionConfig = SessionConfig.Builder()
            .setRangingMeasurementsLimit(1000)
            .setAngleOfArrivalNeeded(true)
            .setSensorFusionParams(sensorFusionParams)
            .build()

        val preference = RangingPreference.Builder(
            RangingPreference.DEVICE_ROLE_INITIATOR,
            config
        ).setSessionConfig(sessionConfig).build()

        val callback = RangingManager.RangingCapabilitiesCallback { capabilities ->
            val csCapabilities = capabilities.csCapabilities
            if (csCapabilities == null) {
                onStatus("CS not supported")
                stopRanging()
                return@RangingCapabilitiesCallback
            }
            if (!csCapabilities.supportedSecurityLevels.contains(1)) {
                onStatus("CS security level not supported")
                stopRanging()
                return@RangingCapabilitiesCallback
            }
            if (!hasRangingPermission()) {
                onStatus("Missing RANGING permission")
                stopRanging()
                return@RangingCapabilitiesCallback
            }

            rangingSession = manager.createRangingSession(mainExecutor, rangingCallback)
            rangingSession?.let { session ->
                try {
                    session.addDeviceToRangingSession(config)
                } catch (e: Exception) {
                    onStatus("Add device failed: ${e.message}")
                } finally {
                    session.start(preference)
                }
            } ?: onStatus("Ranging session creation failed")
        }

        capabilitiesCallback = callback
        manager.registerCapabilitiesCallback(mainExecutor, callback)
    }

    private val rangingCallback = object : RangingSession.Callback {
        override fun onOpened() {
            onConnectionState("Ranging opened")
            onStatus("Ranging session opened")
        }

        override fun onOpenFailed(reason: Int) {
            onConnectionState("Ranging open failed")
            onStatus("Ranging open failed: $reason")
        }

        override fun onClosed(reason: Int) {
            onConnectionState("Ranging closed")
            onStatus("Ranging closed: $reason")
        }

        override fun onStarted(peer: RangingDevice, technology: Int) {
            onConnectionState("Ranging started")
            onStatus("Ranging started")
        }

        override fun onStopped(peer: RangingDevice, technology: Int) {
            onConnectionState("Ranging stopped")
            onStatus("Ranging stopped")
        }

        override fun onResults(peer: RangingDevice, data: RangingData) {
            val distanceMeters = data.distance?.measurement
            if (distanceMeters != null) {
                onDistance(String.format("%.2f m", distanceMeters))
            }
        }
    }

    fun stopRanging() {
        rangingSession?.close()
        rangingSession = null
        onConnectionState("Idle")
        capabilitiesCallback?.let { cb ->
            rangingManager?.unregisterCapabilitiesCallback(cb)
        }
        capabilitiesCallback = null
    }
}
