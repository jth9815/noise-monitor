package com.noiseMonitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.log10
import kotlin.math.sqrt
import android.media.AudioFormat
import android.media.AudioRecord

data class ClipInfo(
    val file: File,
    val source: String,
    val confidence: Float,
    val note: String,
    val peakDb: Int,
    val timestamp: Date,
    val location: String
)

class AudioMonitorService : Service() {

    inner class LocalBinder : Binder() {
        fun getService() = this@AudioMonitorService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 설정값 (MainActivity에서 주입)
    var thresholdDb: Int = 80
    var saveFilter: Set<String> = emptySet()
    var locationLabel: String = ""
    var saveDir: File? = null

    // 상태 콜백
    var onDbUpdate: ((Int) -> Unit)? = null
    var onClipSaved: ((ClipInfo) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null
    var onSkipped: ((String) -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val sampleRate = 44100
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 4

    // 캡처 상태
    private var isCapturing = false
    private var captureStartTime: Date? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentTempFile: File? = null
    private var clipPeakDb = 0
    private var lastClipEndMs = 0L

    private val PRE_ROLL_MS = 1500L
    private val POST_ROLL_MS = 2000L
    private val MIN_GAP_MS = 3000L
    private val MAX_CLIP_MS = 15000L

    companion object {
        const val CHANNEL_ID = "noise_monitor_channel"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.noiseMonitor.STOP"
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun startMonitoring() {
        acquireWakeLock()
        startForeground(NOTIF_ID, buildNotification("감지 대기 중..."))
        monitorJob = serviceScope.launch { monitorLoop() }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        stopCapture(save = false)
        wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun monitorLoop() = withContext(Dispatchers.IO) {
        audioRecord = AudioRecord(
            android.media.MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord?.startRecording()

        val buffer = ShortArray(bufferSize / 2)
        var silenceMs = 0L
        var lastSampleMs = System.currentTimeMillis()

        while (isActive) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (read <= 0) continue

            val now = System.currentTimeMillis()
            val elapsed = now - lastSampleMs
            lastSampleMs = now

            // RMS → dB
            var sum = 0.0
            for (i in 0 until read) sum += buffer[i].toDouble() * buffer[i].toDouble()
            val rms = sqrt(sum / read)
            val db = if (rms > 0) (20 * log10(rms / 32768.0) + 90).toInt() else 0

            withContext(Dispatchers.Main) { onDbUpdate?.invoke(db) }

            if (!isCapturing) {
                if (db >= thresholdDb && now - lastClipEndMs > MIN_GAP_MS) {
                    startCapture()
                    clipPeakDb = db
                    withContext(Dispatchers.Main) { onStatusUpdate?.invoke("🔴 소음 감지! 녹음 중...") }
                }
            } else {
                clipPeakDb = maxOf(clipPeakDb, db)

                if (db < thresholdDb - 5) {
                    silenceMs += elapsed
                    if (silenceMs >= POST_ROLL_MS) {
                        val captured = captureStartTime!!
                        val peak = clipPeakDb
                        stopCapture(save = true)
                        silenceMs = 0L
                        val tempFile = currentTempFile
                        if (tempFile != null) {
                            withContext(Dispatchers.Main) { onStatusUpdate?.invoke("소음 분류 중...") }
                            processClip(tempFile, captured, peak)
                        }
                    }
                } else {
                    silenceMs = 0L
                    // 최대 15초 강제 종료
                    if (now - (captureStartTime?.time ?: now) > MAX_CLIP_MS) {
                        val captured = captureStartTime!!
                        val peak = clipPeakDb
                        stopCapture(save = true)
                        silenceMs = 0L
                        val tempFile = currentTempFile
                        if (tempFile != null) {
                            withContext(Dispatchers.Main) { onStatusUpdate?.invoke("소음 분류 중...") }
                            processClip(tempFile, captured, peak)
                        }
                    }
                }
            }
        }
    }

    private fun startCapture() {
        isCapturing = true
        captureStartTime = Date()
        clipPeakDb = 0

        val tempFile = File(cacheDir, "temp_clip_${System.currentTimeMillis()}.m4a")
        currentTempFile = tempFile

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(tempFile.absolutePath)
            prepare()
            start()
        }
    }

    private fun stopCapture(save: Boolean) {
        isCapturing = false
        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        mediaRecorder?.release()
        mediaRecorder = null
        lastClipEndMs = System.currentTimeMillis()
        captureStartTime = null
        if (!save) currentTempFile?.delete()
    }

    private suspend fun processClip(tempFile: File, capturedAt: Date, peakDb: Int) {
        try {
            val result = ClaudeClassifier.classify(tempFile)

            // 필터 체크
            if (saveFilter.isNotEmpty() && !saveFilter.contains(result.source)) {
                tempFile.delete()
                withContext(Dispatchers.Main) {
                    onSkipped?.invoke(result.source)
                    onStatusUpdate?.invoke("건너뜀: ${result.source} (필터 대상 아님)")
                }
                return
            }

            // 파일명 생성
            val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            val locPart = if (locationLabel.isNotBlank()) "_${locationLabel.take(30).replace(" ", "_")}" else ""
            val filename = "${sdf.format(capturedAt)}_${result.source}_${peakDb}dB${locPart}.m4a"

            // 저장 폴더
            val dir = saveDir ?: File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ), "소음증거"
            ).also { it.mkdirs() }
            dir.mkdirs()

            val finalFile = File(dir, filename)
            tempFile.copyTo(finalFile, overwrite = true)
            tempFile.delete()

            val clipInfo = ClipInfo(
                file = finalFile,
                source = result.source,
                confidence = result.confidence,
                note = result.note,
                peakDb = peakDb,
                timestamp = capturedAt,
                location = locationLabel
            )

            withContext(Dispatchers.Main) {
                onClipSaved?.invoke(clipInfo)
                onStatusUpdate?.invoke("저장됨: ${result.source} ${peakDb}dB (${(result.confidence * 100).toInt()}%)")
                updateNotification("저장됨: ${result.source} ${peakDb}dB")
            }
        } catch (e: Exception) {
            tempFile.delete()
            withContext(Dispatchers.Main) {
                onStatusUpdate?.invoke("오류: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "소음 모니터링",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "소음 감지 및 녹음 서비스"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, AudioMonitorService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("소음 증거 수집 중")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "중지", stopPending)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NoiseMonitor::WakeLock"
        ).also { it.acquire(24 * 60 * 60 * 1000L) } // 최대 24시간
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
