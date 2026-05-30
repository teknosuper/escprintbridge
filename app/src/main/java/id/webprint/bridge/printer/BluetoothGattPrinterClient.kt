package id.webprint.bridge.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BluetoothGattPrinterClient(
    private val context: Context,
) {

    fun validate(macAddress: String): String {
        val session = connect(macAddress)
        session.gatt.disconnect()
        session.gatt.close()
        return "Printer BLE siap: ${session.device.name ?: "Unknown"} / ${session.characteristic.uuid}"
    }

    fun print(macAddress: String, payload: ByteArray) {
        val session = connect(macAddress)
        try {
            writePayload(session, payload)
            Thread.sleep(400)
        } finally {
            session.gatt.disconnect()
            session.gatt.close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(macAddress: String): GattSession {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: error("Bluetooth tidak tersedia di device ini")
        require(adapter.isEnabled) { "Bluetooth sedang nonaktif" }

        val device = runCatching { adapter.getRemoteDevice(macAddress) }.getOrElse {
            throw IllegalStateException("MAC address Bluetooth BLE tidak valid.")
        }

        val state = GattState()
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, state, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, state)
        }

        val ready = state.readyLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!ready) {
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
            error("Timeout saat membuka koneksi BLE printer.")
        }
        state.errorRef.get()?.let {
            runCatching {
                gatt.disconnect()
                gatt.close()
            }
            throw IllegalStateException(it, state.throwableRef.get())
        }

        val characteristic = state.characteristicRef.get()
            ?: error("Characteristic tulis BLE printer tidak ditemukan.")

        return GattSession(device, gatt, characteristic, state)
    }

    @SuppressLint("MissingPermission")
    private fun writePayload(session: GattSession, payload: ByteArray) {
        val chunkSize = 180
        payload.toList().chunked(chunkSize).forEach { chunk ->
            val writeLatch = CountDownLatch(1)
            session.state.writeLatchRef.set(writeLatch)
            session.state.errorRef.set(null)
            session.state.throwableRef.set(null)

            val characteristic = session.characteristic.apply {
                writeType = when {
                    properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    else -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
                value = ByteArray(chunk.size) { index -> chunk[index] }
            }

            val started = session.gatt.writeCharacteristic(characteristic)
            check(started) { "Gagal memulai write BLE ke printer." }

            if (characteristic.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                Thread.sleep(90)
                return@forEach
            }

            check(writeLatch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                "Timeout saat mengirim data BLE ke printer."
            }
            session.state.errorRef.get()?.let {
                throw IllegalStateException(it, session.state.throwableRef.get())
            }
        }
    }

    private fun findWritableCharacteristic(gatt: BluetoothGatt): BluetoothGattCharacteristic? {
        val preferredUuids = listOf(
            UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("49535343-1e4d-4bd9-ba61-23c647249616"),
            UUID.fromString("49535343-8841-43f4-a8d4-ecbe34729bb3"),
            UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb"),
        )

        preferredUuids.forEach { target ->
            gatt.services
                .flatMap { it.characteristics }
                .firstOrNull { it.uuid == target && it.isWritable() }
                ?.let { return it }
        }

        return gatt.services
            .flatMap { it.characteristics }
            .firstOrNull { it.isWritable() }
    }

    private fun BluetoothGattCharacteristic.isWritable(): Boolean {
        return properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    }

    private data class GattSession(
        val device: BluetoothDevice,
        val gatt: BluetoothGatt,
        val characteristic: BluetoothGattCharacteristic,
        val state: GattState,
    )

    private inner class GattState : BluetoothGattCallback() {
        val readyLatch = CountDownLatch(1)
        val writeLatchRef = AtomicReference<CountDownLatch?>()
        val characteristicRef = AtomicReference<BluetoothGattCharacteristic?>()
        val errorRef = AtomicReference<String?>()
        val throwableRef = AtomicReference<Throwable?>()

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                errorRef.set("BLE connect gagal: status $status")
                readyLatch.countDown()
                writeLatchRef.get()?.countDown()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!gatt.discoverServices()) {
                        errorRef.set("BLE discover services gagal dimulai.")
                        readyLatch.countDown()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (characteristicRef.get() == null && errorRef.get().isNullOrBlank()) {
                        errorRef.set("Koneksi BLE printer terputus.")
                    }
                    readyLatch.countDown()
                    writeLatchRef.get()?.countDown()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                errorRef.set("BLE discover services gagal: status $status")
                readyLatch.countDown()
                return
            }

            val characteristic = findWritableCharacteristic(gatt)
            if (characteristic == null) {
                errorRef.set("Characteristic tulis BLE printer tidak ditemukan.")
            } else {
                characteristicRef.set(characteristic)
            }
            readyLatch.countDown()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                errorRef.set("BLE write gagal: status $status")
            }
            writeLatchRef.get()?.countDown()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val WRITE_TIMEOUT_MS = 6_000L
    }
}
