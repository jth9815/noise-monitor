package com.noiseMonitor

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private var service: AudioMonitorService? = null
    private var isBound = false
    private var isRecording = false

    private val clips = mutableListOf<ClipInfo>()
    private var filterSource: String? = null  // null = 전체

    // Views
    private lateinit var tvDb: TextView
    private lateinit var tvPeakDb: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressDb: ProgressBar
    private lateinit var btnStart: Button
    private lateinit var etLocation: EditText
    private lateinit var etApiKey: EditText
    private lateinit var seekThreshold: SeekBar
    private lateinit var tvThreshold: TextView
    private lateinit var llFilterButtons: LinearLayout
    private lateinit var llClips: LinearLayout
    private lateinit var tvClipCount: TextView
    private lateinit var scrollClips: ScrollView

    private val saveFilterSet = mutableSetOf<String>()
    private val allSources = listOf("오토바이", "승용차", "트럭", "버스", "경적", "기타")
    private val sourceColors = mapOf(
        "오토바이" to 0xFFf97316.toInt(),
        "승용차"   to 0xFF3b82f6.toInt(),
        "트럭"     to 0xFF8b5cf6.toInt(),
        "버스"     to 0xFF10b981.toInt(),
        "경적"     to 0xFFef4444.toInt(),
        "기타"     to 0xFF6b7280.toInt()
    )
    private val sourceIcons = mapOf(
        "오토바이" to "🏍️", "승용차" to "🚗", "트럭" to "🚛",
        "버스" to "🚌", "경적" to "📯", "기타" to "🔊"
    )

    private val prefs by lazy { getSharedPreferences("noise_monitor", MODE_PRIVATE) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as AudioMonitorService.LocalBinder).getService()
            isBound = true
            setupServiceCallbacks()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        restorePrefs()
        buildFilterButtons()
        setupSeekBar()

        btnStart.setOnClickListener {
            if (isRecording) stopRecording() else checkPermissionsAndStart()
        }
    }

    private fun bindViews() {
        tvDb           = findViewById(R.id.tvDb)
        tvPeakDb       = findViewById(R.id.tvPeakDb)
        tvStatus       = findViewById(R.id.tvStatus)
        progressDb     = findViewById(R.id.progressDb)
        btnStart       = findViewById(R.id.btnStart)
        etLocation     = findViewById(R.id.etLocation)
        etApiKey       = findViewById(R.id.etApiKey)
        seekThreshold  = findViewById(R.id.seekThreshold)
        tvThreshold    = findViewById(R.id.tvThreshold)
        llFilterButtons= findViewById(R.id.llFilterButtons)
        llClips        = findViewById(R.id.llClips)
        tvClipCount    = findViewById(R.id.tvClipCount)
        scrollClips    = findViewById(R.id.scrollClips)
    }

    private fun restorePrefs() {
        etApiKey.setText(prefs.getString("api_key", ""))
        etLocation.setText(prefs.getString("location", ""))
        seekThreshold.progress = prefs.getInt("threshold", 80) - 50
        updateThresholdLabel()
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("api_key", etApiKey.text.toString())
            .putString("location", etLocation.text.toString())
            .putInt("threshold", seekThreshold.progress + 50)
            .apply()
    }

    private fun setupSeekBar() {
        seekThreshold.max = 45  // 50~95 dB
        seekThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) { updateThresholdLabel() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun updateThresholdLabel() {
        val db = seekThreshold.progress + 50
        tvThreshold.text = "감지 임계값: ${db} dB"
    }

    private fun buildFilterButtons() {
        llFilterButtons.removeAllViews()
        allSources.forEach { src ->
            val btn = Button(this).apply {
                text = "${sourceIcons[src]} $src"
                textSize = 13f
                isAllCaps = false
                val active = saveFilterSet.contains(src)
                setBackgroundColor(if (active) (sourceColors[src] ?: 0xFF666680.toInt()) else 0xFF2a2a3e.toInt())
                setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF888899.toInt())
                setPadding(32, 16, 32, 16)
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 16, 0) }
                layoutParams = params
                setOnClickListener {
                    if (saveFilterSet.contains(src)) saveFilterSet.remove(src)
                    else saveFilterSet.add(src)
                    buildFilterButtons()
                    service?.saveFilter = saveFilterSet.toSet()
                }
            }
            llFilterButtons.addView(btn)
        }
        val hint = TextView(this).apply {
            text = if (saveFilterSet.isEmpty()) "전체 저장" else "${saveFilterSet.joinToString(", ")} 만 저장"
            textSize = 11f
            setTextColor(0xFF666680.toInt())
            setPadding(4, 8, 0, 0)
        }
        llFilterButtons.addView(hint)
    }

    private fun setupServiceCallbacks() {
        service?.apply {
            thresholdDb = seekThreshold.progress + 50
            saveFilter = saveFilterSet.toSet()
            locationLabel = etLocation.text.toString()
            ClaudeClassifier.apiKey = etApiKey.text.toString()
            saveDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ), "소음증거"
            )

            onDbUpdate = { db ->
                runOnUiThread {
                    tvDb.text = "${db} dB"
                    progressDb.progress = db.coerceIn(0, 100)
                    val threshold = seekThreshold.progress + 50
                    tvDb.setTextColor(if (db >= threshold) 0xFFf97316.toInt() else 0xFFe8e8f0.toInt())
                }
            }

            onStatusUpdate = { msg ->
                runOnUiThread { tvStatus.text = msg }
            }

            onSkipped = { src ->
                runOnUiThread { tvStatus.text = "건너뜀: $src (필터 대상 아님)" }
            }

            onClipSaved = { clip ->
                runOnUiThread {
                    clips.add(0, clip)
                    addClipView(clip)
                    tvClipCount.text = "총 ${clips.size}개 저장됨"
                }
            }

            startMonitoring()
        }
    }

    private fun addClipView(clip: ClipInfo) {
        val sdf = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())
        val color = sourceColors[clip.source] ?: 0xFF6b7280.toInt()
        val icon = sourceIcons[clip.source] ?: "🔊"

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF12121a.toInt())
            setPadding(32, 28, 32, 28)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 16) }
            layoutParams = lp
        }

        // 상단 행: 발생원 + 시각
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val tvSource = TextView(this).apply {
            text = "$icon ${clip.source}  ${(clip.confidence * 100).toInt()}%  ${clip.peakDb}dB"
            textSize = 14f
            setTextColor(color)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTime = TextView(this).apply {
            text = sdf.format(clip.timestamp)
            textSize = 12f
            setTextColor(0xFF888899.toInt())
        }
        row1.addView(tvSource)
        row1.addView(tvTime)

        val tvNote = TextView(this).apply {
            text = clip.note
            textSize = 12f
            setTextColor(0xFF555566.toInt())
            setPadding(0, 8, 0, 0)
        }

        val tvPath = TextView(this).apply {
            text = "📁 ${clip.file.name}"
            textSize = 11f
            setTextColor(0xFF444466.toInt())
            setPadding(0, 8, 0, 0)
        }

        card.addView(row1)
        card.addView(tvNote)
        if (clip.location.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = "📍 ${clip.location}"
                textSize = 11f
                setTextColor(0xFF444466.toInt())
                setPadding(0, 4, 0, 0)
            })
        }
        card.addView(tvPath)

        llClips.addView(card, 0)
        scrollClips.post { scrollClips.smoothScrollTo(0, 0) }
    }

    private fun checkPermissionsAndStart() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) startRecording()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }

    private fun startRecording() {
        val apiKey = etApiKey.text.toString().trim()
        if (apiKey.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("API 키 필요")
                .setMessage("Anthropic API 키를 입력해주세요.\nanthropomorphic.com에서 발급받을 수 있습니다.")
                .setPositiveButton("확인", null)
                .show()
            return
        }

        savePrefs()
        isRecording = true
        btnStart.text = "⏹ 녹음 중지"
        btnStart.setBackgroundColor(0xFFef4444.toInt())
        etLocation.isEnabled = false
        etApiKey.isEnabled = false
        seekThreshold.isEnabled = false
        tvStatus.text = "녹음 중... 감지 대기"

        val intent = Intent(this, AudioMonitorService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun stopRecording() {
        service?.stopMonitoring()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        isRecording = false
        btnStart.text = "🎙️ 녹음 시작"
        btnStart.setBackgroundColor(0xFF3b82f6.toInt())
        etLocation.isEnabled = true
        etApiKey.isEnabled = true
        seekThreshold.isEnabled = true
        tvStatus.text = "대기 중"
        tvDb.text = "0 dB"
        progressDb.progress = 0
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startRecording()
        } else {
            Toast.makeText(this, "마이크 권한이 필요합니다", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (isBound) { unbindService(serviceConnection); isBound = false }
        super.onDestroy()
    }
}
