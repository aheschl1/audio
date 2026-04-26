package com.example.androidapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.grpc.Status
import io.grpc.ManagedChannel
import io.grpc.StatusRuntimeException
import io.grpc.android.AndroidChannelBuilder
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import audio.Audio
import audio.AudioServiceGrpc
import kotlin.math.max

class AudioGrpcForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var streamingJob: Job? = null
    @Volatile
    private var activeStreamToken: Long = 0L
    @Volatile
    private var activeHost: String? = null
    @Volatile
    private var activePort: Int? = null

    private var channel: ManagedChannel? = null
    private var audioRecord: AudioRecord? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand flags=$flags startId=$startId")
        startForeground(NOTIFICATION_ID, buildNotification("Streaming microphone audio"))
        val host = intent?.getStringExtra(EXTRA_HOST).orEmpty().ifBlank { DEFAULT_HOST }
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        val safePort = if (port in 1..65535) port else DEFAULT_PORT

        if (streamingJob?.isActive == true && host == activeHost && safePort == activePort) {
            Log.d(TAG, "Ignoring duplicate start for host=$host port=$safePort")
            return START_STICKY
        }

        activeHost = host
        activePort = safePort
        activeStreamToken += 1
        val streamToken = activeStreamToken
        Log.i(TAG, "Starting stream for host=$host port=$safePort")
        publishStreamStatus(STATUS_STARTING)

        streamingJob?.cancel()
        channel?.shutdownNow()
        channel = null
        streamingJob = serviceScope.launch {
            streamMicrophoneToGrpc(host, safePort, streamToken)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        publishStreamStatus(STATUS_STOPPED)
        streamingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        channel?.shutdownNow()
        channel = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun streamMicrophoneToGrpc(host: String, port: Int, streamToken: Long) = withContext(Dispatchers.IO) {
        val sampleRateHz = 16_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBuffer")
            publishStreamStatus(STATUS_ERROR)
            stopSelf()
            return@withContext
        }
        val bufferSize = max(minBuffer, 2048)
        Log.d(TAG, "Audio config sampleRate=$sampleRateHz minBuffer=$minBuffer bufferSize=$bufferSize")

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRateHz,
            channelConfig,
            encoding,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed. state=${recorder.state}")
            publishStreamStatus(STATUS_ERROR)
            stopSelf()
            return@withContext
        }

        audioRecord = recorder
        if (!isValidIpv4(host)) {
            Log.e(TAG, "Invalid host format: $host")
            publishStreamStatus(STATUS_ERROR)
            stopSelf()
            return@withContext
        }
        channel = AndroidChannelBuilder
            .forAddress(host, port)
            .context(applicationContext)
            .usePlaintext()
            .build()
        Log.d(TAG, "gRPC channel created")

        val responseObserver = object : StreamObserver<Audio.UploadAudioResponse> {
            override fun onNext(value: Audio.UploadAudioResponse) {
                Log.d(TAG, "Received UploadAudioResponse status=${value.status}")
            }

            override fun onError(t: Throwable) {
                if (streamToken != activeStreamToken) {
                    Log.d(TAG, "Ignoring stale stream error callback token=$streamToken")
                    return
                }
                val status = (t as? StatusRuntimeException)?.status
                val expectedShutdown = status?.code == Status.Code.CANCELLED ||
                    (status?.code == Status.Code.UNAVAILABLE &&
                        status.description?.contains("Channel shutdownNow invoked") == true)
                if (expectedShutdown) {
                    Log.i(TAG, "Stream closed during restart/shutdown: code=${status?.code}")
                    publishStreamStatus(STATUS_STOPPED)
                    return
                }
                Log.e(TAG, "gRPC response observer error", t)
                publishStreamStatus(STATUS_ERROR)
                stopSelf()
            }

            override fun onCompleted() {
                if (streamToken != activeStreamToken) {
                    Log.d(TAG, "Ignoring stale stream completed callback token=$streamToken")
                    return
                }
                Log.i(TAG, "gRPC response stream completed")
                publishStreamStatus(STATUS_STOPPED)
                stopSelf()
            }
        }

        try {
            val requestObserver = AudioServiceGrpc
                .newStub(channel)
                .uploadAudioStream(responseObserver)

            recorder.startRecording()
            Log.i(TAG, "Microphone recording started")
            publishStreamStatus(STATUS_RUNNING)
            val audioBuffer = ByteArray(bufferSize)
            var sentChunks = 0

            while (isActive) {
                val bytesRead = recorder.read(audioBuffer, 0, audioBuffer.size)
                if (bytesRead <= 0) {
                    Log.w(TAG, "Audio read returned $bytesRead")
                    continue
                }

                val request = Audio.UploadAudioRequest.newBuilder()
                    .setSessionId(DEFAULT_SESSION_ID)
                    .setAudioData(com.google.protobuf.ByteString.copyFrom(audioBuffer, 0, bytesRead))
                    .build()

                requestObserver.onNext(request)
                sentChunks++
                if (sentChunks % 100 == 0) {
                    Log.d(TAG, "Sent audio chunks=$sentChunks")
                }
            }

            Log.i(TAG, "Stopping stream loop; completing request stream")
            requestObserver.onCompleted()
        } catch (e: StatusRuntimeException) {
            if (streamToken != activeStreamToken) {
                Log.d(TAG, "Ignoring stale stream failure token=$streamToken status=${e.status}")
                return@withContext
            }
            val expectedShutdown = e.status.code == Status.Code.CANCELLED ||
                (e.status.code == Status.Code.UNAVAILABLE &&
                    e.status.description?.contains("Channel shutdownNow invoked") == true)
            if (expectedShutdown) {
                Log.i(TAG, "Stream ended during restart/shutdown: ${e.status}")
                publishStreamStatus(STATUS_STOPPED)
                return@withContext
            }
            Log.e(TAG, "gRPC stream failed: ${e.status}", e)
            publishStreamStatus(STATUS_ERROR)
            stopSelf()
        } catch (e: Exception) {
            if (streamToken != activeStreamToken) {
                Log.d(TAG, "Ignoring stale unexpected exception token=$streamToken")
                return@withContext
            }
            Log.e(TAG, "Unexpected streaming failure", e)
            publishStreamStatus(STATUS_ERROR)
            stopSelf()
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            audioRecord = null
            channel?.shutdownNow()
            channel = null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.audio_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel ensured: $CHANNEL_ID")
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_call_mute)
            .setContentTitle(getString(R.string.audio_service_notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .build()
    }

    private fun publishStreamStatus(status: String) {
        sendBroadcast(Intent(ACTION_STREAM_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STREAM_STATUS, status)
        })
    }

    companion object {
        private const val TAG = "AudioGrpcService"
        const val ACTION_STREAM_STATUS = "com.example.androidapp.STREAM_STATUS"
        const val EXTRA_STREAM_STATUS = "extra_stream_status"
        const val STATUS_IDLE = "idle"
        const val STATUS_STARTING = "starting"
        const val STATUS_RUNNING = "running"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error"
        private const val EXTRA_HOST = "extra_host"
        private const val EXTRA_PORT = "extra_port"
        private const val CHANNEL_ID = "audio_stream_channel"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_HOST = "10.8.0.1"
        private const val DEFAULT_PORT = 4392
        private const val DEFAULT_SESSION_ID = 1

        fun start(context: Context, host: String, port: Int) {
            Log.i(TAG, "start() called with host=$host port=$port")
            context.sendBroadcast(Intent(ACTION_STREAM_STATUS).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_STREAM_STATUS, STATUS_STARTING)
            })
            val intent = Intent(context, AudioGrpcForegroundService::class.java).apply {
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            Log.i(TAG, "stop() called")
            context.stopService(Intent(context, AudioGrpcForegroundService::class.java))
            context.sendBroadcast(Intent(ACTION_STREAM_STATUS).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_STREAM_STATUS, STATUS_STOPPED)
            })
        }

        private fun isValidIpv4(host: String): Boolean {
            val parts = host.trim().split('.')
            if (parts.size != 4) {
                return false
            }
            return parts.all { part ->
                val value = part.toIntOrNull() ?: return@all false
                value in 0..255
            }
        }
    }
}