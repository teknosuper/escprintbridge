package id.webprint.bridge

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import id.webprint.bridge.bridge.ServiceController
import id.webprint.bridge.data.BridgeSettings
import id.webprint.bridge.data.PrinterMode
import id.webprint.bridge.data.QueueType
import id.webprint.bridge.data.SettingsRepository
import id.webprint.bridge.databinding.ActivityMainBinding
import id.webprint.bridge.printer.BluetoothPrinterOption
import id.webprint.bridge.printer.BluetoothPrinterRepository

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var serviceController: ServiceController
    private lateinit var bluetoothPrinterRepository: BluetoothPrinterRepository

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.notification_permission_warning, Toast.LENGTH_LONG).show()
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            showBluetoothPrinterPicker()
        } else {
            Toast.makeText(this, R.string.bluetooth_permission_warning, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsRepository = SettingsRepository(this)
        serviceController = ServiceController(this)
        bluetoothPrinterRepository = BluetoothPrinterRepository()

        requestNotificationPermissionIfNeeded()
        bindForm()
        bindActions()
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    private fun bindForm() {
        val settings = settingsRepository.load()
        binding.clientUrlInput.setText(settings.clientUrl)
        binding.baseUrlInput.setText(settings.baseUrl)
        binding.apiKeyInput.setText(settings.token)
        binding.outletIdInput.setText(settings.outletId.toString())
        binding.deviceIdInput.setText(settings.deviceId)
        binding.stationIdInput.setText(settings.stationId)
        binding.paperWidthInput.setText(settings.paperWidthColumns.toString())
        binding.pollingSecondsInput.setText(settings.pollingSeconds.toString())
        binding.autoStartSwitch.isChecked = settings.autoStart

        binding.queueTypeGroup.check(
            if (QueueType.fromValue(settings.queueType) == QueueType.KITCHEN) {
                R.id.queueTypeKitchen
            } else {
                R.id.queueTypeCashier
            },
        )

        binding.printerModeGroup.check(
            if (PrinterMode.fromValue(settings.printerMode) == PrinterMode.BLUETOOTH) {
                R.id.printerModeBluetooth
            } else {
                R.id.printerModeTcp
            },
        )

        binding.printerHostInput.setText(settings.printerHost)
        binding.printerPortInput.setText(settings.printerPort.toString())
        binding.bluetoothMacAddressInput.setText(settings.bluetoothMacAddress)
        binding.bluetoothPrinterNameInput.setText(settings.bluetoothPrinterName)

        updateQueueTypeSection()
        updatePrinterModeSections()
    }

    private fun bindActions() {
        binding.importClientUrlButton.setOnClickListener {
            importSettingsFromClientUrl()
        }

        binding.queueTypeGroup.setOnCheckedChangeListener { _, _ ->
            updateQueueTypeSection()
        }

        binding.printerModeGroup.setOnCheckedChangeListener { _, _ ->
            updatePrinterModeSections()
        }

        binding.chooseBluetoothPrinterButton.setOnClickListener {
            ensureBluetoothPermissionsAndPick()
        }

        binding.saveButton.setOnClickListener {
            val current = settingsRepository.load()
            val updated = current.copy(
                clientUrl = binding.clientUrlInput.text.toString().trim(),
                baseUrl = binding.baseUrlInput.text.toString().trim().removeSuffix("/"),
                token = binding.apiKeyInput.text.toString().trim(),
                outletId = binding.outletIdInput.text.toString().trim().toIntOrNull() ?: 0,
                queueType = currentQueueType().value,
                deviceId = binding.deviceIdInput.text.toString().trim(),
                stationId = binding.stationIdInput.text.toString().trim(),
                paperWidthColumns = binding.paperWidthInput.text.toString().trim().toIntOrNull() ?: 32,
                printerMode = currentPrinterMode().value,
                printerHost = binding.printerHostInput.text.toString().trim(),
                printerPort = binding.printerPortInput.text.toString().trim().toIntOrNull() ?: 9100,
                bluetoothMacAddress = binding.bluetoothMacAddressInput.text.toString().trim(),
                bluetoothPrinterName = binding.bluetoothPrinterNameInput.text.toString().trim(),
                pollingSeconds = binding.pollingSecondsInput.text.toString().trim().toLongOrNull() ?: 3L,
                autoStart = binding.autoStartSwitch.isChecked,
            )

            val errors = settingsRepository.validate(updated)
            if (errors.isNotEmpty()) {
                binding.statusText.text = errors.joinToString("\n")
                return@setOnClickListener
            }

            settingsRepository.save(updated)
            renderStatus(getString(R.string.settings_saved))
        }

        binding.startButton.setOnClickListener {
            serviceController.start()
            renderStatus(getString(R.string.service_start_requested))
        }

        binding.stopButton.setOnClickListener {
            serviceController.stop()
            renderStatus(getString(R.string.service_stop_requested))
        }
    }

    private fun renderStatus(message: String? = null) {
        val settings = settingsRepository.load()
        val status = buildString {
            appendLine(if (serviceController.isRunning()) getString(R.string.service_running) else getString(R.string.service_stopped))
            appendLine(getString(R.string.status_queue_type, QueueType.fromValue(settings.queueType).name))
            appendLine(getString(R.string.status_outlet_id, settings.outletId))
            appendLine(getString(R.string.status_token, settings.token.ifBlank { "-" }))
            appendLine(getString(R.string.status_device_id, settings.deviceId.ifBlank { "-" }))
            appendLine(getString(R.string.status_server, settings.baseUrl.ifBlank { "-" }))
            appendLine(getString(R.string.status_printer_mode, PrinterMode.fromValue(settings.printerMode).name))
            appendLine(printerSummary(settings))
            appendLine(getString(R.string.status_polling, settings.pollingSeconds))
            if (!message.isNullOrBlank()) {
                appendLine()
                appendLine(message)
            }
        }
        binding.statusText.text = status.trim()
    }

    private fun printerSummary(settings: BridgeSettings): String {
        return when (PrinterMode.fromValue(settings.printerMode)) {
            PrinterMode.TCP -> getString(
                R.string.status_printer_tcp,
                settings.printerHost.ifBlank { "-" },
                settings.printerPort,
            )
            PrinterMode.BLUETOOTH -> getString(
                R.string.status_printer_bluetooth,
                settings.bluetoothPrinterName.ifBlank { "-" },
                settings.bluetoothMacAddress.ifBlank { "-" },
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun currentPrinterMode(): PrinterMode {
        return if (binding.printerModeBluetooth.isChecked) {
            PrinterMode.BLUETOOTH
        } else {
            PrinterMode.TCP
        }
    }

    private fun currentQueueType(): QueueType {
        return if (binding.queueTypeKitchen.isChecked) {
            QueueType.KITCHEN
        } else {
            QueueType.CASHIER
        }
    }

    private fun updatePrinterModeSections() {
        val mode = currentPrinterMode()
        binding.tcpPrinterSection.visibility = if (mode == PrinterMode.TCP) View.VISIBLE else View.GONE
        binding.bluetoothPrinterSection.visibility = if (mode == PrinterMode.BLUETOOTH) View.VISIBLE else View.GONE
    }

    private fun updateQueueTypeSection() {
        binding.stationIdSection.visibility = if (currentQueueType() == QueueType.KITCHEN) View.VISIBLE else View.GONE
    }

    private fun ensureBluetoothPermissionsAndPick() {
        if (!bluetoothPrinterRepository.isBluetoothAvailable()) {
            Toast.makeText(this, R.string.bluetooth_not_available, Toast.LENGTH_LONG).show()
            return
        }

        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions()
            return
        }

        showBluetoothPrinterPicker()
    }

    private fun hasBluetoothPermissions(): Boolean {
        return requiredBluetoothPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = requiredBluetoothPermissions()
        if (permissions.isEmpty()) {
            showBluetoothPrinterPicker()
            return
        }
        bluetoothPermissionLauncher.launch(permissions)
    }

    private fun requiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            emptyArray()
        }
    }

    private fun showBluetoothPrinterPicker() {
        if (!bluetoothPrinterRepository.isBluetoothEnabled()) {
            Toast.makeText(this, R.string.bluetooth_enable_first, Toast.LENGTH_LONG).show()
            return
        }

        val printers = bluetoothPrinterRepository.getBondedPrinters()
        if (printers.isEmpty()) {
            Toast.makeText(this, R.string.bluetooth_no_paired_printer, Toast.LENGTH_LONG).show()
            return
        }

        val items = printers.map { it.label }.toTypedArray()
        val currentAddress = binding.bluetoothMacAddressInput.text?.toString()?.trim()
        val selectedIndex = printers.indexOfFirst { it.address.equals(currentAddress, ignoreCase = true) }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_bluetooth_printer_title)
            .setSingleChoiceItems(items, selectedIndex) { dialog, which ->
                val printer: BluetoothPrinterOption = printers[which]
                binding.bluetoothMacAddressInput.setText(printer.address)
                binding.bluetoothPrinterNameInput.setText(printer.name)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importSettingsFromClientUrl() {
        val rawUrl = binding.clientUrlInput.text.toString().trim()
        if (rawUrl.isBlank()) {
            binding.statusText.text = getString(R.string.error_client_url_required)
            return
        }

        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull()
        if (uri == null) {
            binding.statusText.text = getString(R.string.error_client_url_invalid)
            return
        }

        val imported = settingsRepository.load().copy(
            clientUrl = rawUrl,
            baseUrl = uri.getQueryParameter("base_url")?.trim()?.removeSuffix("/").orEmpty(),
            token = uri.getQueryParameter("token")?.trim().orEmpty(),
            outletId = uri.getQueryParameter("outlet_id")?.trim()?.toIntOrNull() ?: 0,
            queueType = QueueType.fromValue(uri.getQueryParameter("type")).value,
            stationId = uri.getQueryParameter("station_id")?.trim().orEmpty(),
            autoStart = uri.getQueryParameter("autostart") == "1" ||
                uri.getQueryParameter("autostart").equals("true", ignoreCase = true),
        )

        settingsRepository.save(imported)
        if (imported.autoStart) {
            serviceController.start()
        }
        bindForm()
        renderStatus(getString(R.string.client_url_imported))
    }
}
