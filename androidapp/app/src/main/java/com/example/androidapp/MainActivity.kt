package com.example.androidapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pingJob: Job? = null
    private var lastStartedHost: String? = null
    private var lastStartedPort: Int? = null
    private var isServiceRunning: Boolean = false
    private var pendingStartAfterPermission: Boolean = false

    private lateinit var hostInput: EditText
    private lateinit var portInput: EditText
    private lateinit var hostStatus: TextView
    private lateinit var streamStatus: TextView
    private lateinit var toggleServiceButton: Button
    private var isStreamReceiverRegistered = false

    private val streamStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioGrpcForegroundService.ACTION_STREAM_STATUS) {
                return
            }
            val status = intent.getStringExtra(AudioGrpcForegroundService.EXTRA_STREAM_STATUS)
                ?: AudioGrpcForegroundService.STATUS_IDLE
            Log.d(TAG, "Service status update: $status")
            updateStreamStatus(status)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.i(TAG, "onCreate")

        hostInput = findViewById(R.id.hostInput)
        portInput = findViewById(R.id.portInput)
        hostStatus = findViewById(R.id.hostStatus)
        streamStatus = findViewById(R.id.streamStatus)
        toggleServiceButton = findViewById(R.id.stopServiceButton)

        hostInput.doAfterTextChanged {
            schedulePingCheck()
        }
        portInput.doAfterTextChanged {
            schedulePingCheck()
        }

        toggleServiceButton.setOnClickListener { onToggleServiceClicked() }

        updateStreamStatus(AudioGrpcForegroundService.STATUS_IDLE)
        schedulePingCheck()
    }

    override fun onStart() {
        super.onStart()
        registerStreamReceiver()
    }

    override fun onStop() {
        if (isStreamReceiverRegistered) {
            unregisterReceiver(streamStatusReceiver)
            isStreamReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    private fun ensureMicPermissionAndStartService() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "RECORD_AUDIO granted=$hasPermission")

        if (hasPermission) {
            startAudioForegroundService()
            return
        }

        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO
        )
        Log.i(TAG, "Requested RECORD_AUDIO permission")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.i(TAG, "onRequestPermissionsResult requestCode=$requestCode granted=${grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED}")
        if (
            requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED &&
            pendingStartAfterPermission
        ) {
            pendingStartAfterPermission = false
            startAudioForegroundService()
        } else if (requestCode == REQUEST_RECORD_AUDIO) {
            pendingStartAfterPermission = false
        }
    }

    private fun onToggleServiceClicked() {
        if (isServiceRunning) {
            Log.i(TAG, "Stop service requested by user")
            lastStartedHost = null
            lastStartedPort = null
            AudioGrpcForegroundService.stop(this)
            updateStreamStatus(AudioGrpcForegroundService.STATUS_STOPPED)
            return
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startAudioForegroundService()
            return
        }

        pendingStartAfterPermission = true
        ensureMicPermissionAndStartService()
    }

    private fun startAudioForegroundService() {
        val host = selectedHost()
        val port = selectedPort()
        if (isServiceRunning && host == lastStartedHost && port == lastStartedPort) {
            Log.d(TAG, "Skipping restart; endpoint unchanged host=$host port=$port")
            return
        }
        lastStartedHost = host
        lastStartedPort = port
        Log.i(TAG, "Starting foreground service host=$host port=$port")
        AudioGrpcForegroundService.start(this, host, port)
    }

    private fun selectedHost(): String {
        val current = hostInput.text?.toString()?.trim().orEmpty()
        return if (current.isEmpty()) getString(R.string.default_host) else current
    }

    private fun selectedPort(): Int {
        val defaultPort = getString(R.string.default_port).toInt()
        val parsed = portInput.text?.toString()?.trim()?.toIntOrNull() ?: return defaultPort
        return if (parsed in 1..65535) parsed else defaultPort
    }

    private fun schedulePingCheck() {
        pingJob?.cancel()
        hostStatus.text = getString(R.string.host_status_checking)
        hostStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))

        pingJob = activityScope.launch {
            delay(250)
            val isReachable = pingHost(selectedHost())
            Log.d(TAG, "Ping reachable=$isReachable host=${selectedHost()}")
            if (isReachable) {
                hostStatus.text = getString(R.string.host_status_reachable)
                hostStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_dark))
            } else {
                hostStatus.text = getString(R.string.host_status_unreachable)
                hostStatus.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
            }
        }
    }

    private fun registerStreamReceiver() {
        if (isStreamReceiverRegistered) {
            return
        }
        val filter = IntentFilter(AudioGrpcForegroundService.ACTION_STREAM_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(streamStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(streamStatusReceiver, filter)
        }
        isStreamReceiverRegistered = true
    }

    private fun updateStreamStatus(status: String) {
        val (textRes, colorRes) = when (status) {
            AudioGrpcForegroundService.STATUS_STARTING -> R.string.stream_status_starting to android.R.color.darker_gray
            AudioGrpcForegroundService.STATUS_RUNNING -> R.string.stream_status_running to android.R.color.holo_green_dark
            AudioGrpcForegroundService.STATUS_STOPPED -> R.string.stream_status_stopped to android.R.color.darker_gray
            AudioGrpcForegroundService.STATUS_ERROR -> R.string.stream_status_error to android.R.color.holo_red_dark
            else -> R.string.stream_status_idle to android.R.color.darker_gray
        }
        isServiceRunning = status == AudioGrpcForegroundService.STATUS_STARTING ||
            status == AudioGrpcForegroundService.STATUS_RUNNING
        streamStatus.text = getString(textRes)
        streamStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        toggleServiceButton.text = if (isServiceRunning) {
            getString(R.string.stop_service_button)
        } else {
            getString(R.string.start_service_button)
        }
    }

    private suspend fun pingHost(host: String): Boolean = withContext(Dispatchers.IO) {
        if (host.isBlank()) {
            return@withContext false
        }

        runCatching {
            val process = ProcessBuilder("ping", "-c", "1", "-W", "1", host)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_RECORD_AUDIO = 10
    }
}
