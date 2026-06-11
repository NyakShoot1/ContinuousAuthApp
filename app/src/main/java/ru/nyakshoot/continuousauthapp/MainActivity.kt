package ru.nyakshoot.continuousauthapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var sessionTagInput: EditText
    private lateinit var userTypeSpinner: Spinner
    private lateinit var scenarioSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var openImeSettingsButton: Button
    private lateinit var showImePickerButton: Button
    private lateinit var openOpenBoardButton: Button
    private lateinit var openboardStatusText: TextView
    private lateinit var statusText: TextView
    private lateinit var filePathText: TextView
    private lateinit var typingEditText: EditText
    private lateinit var touchArea: FrameLayout

    private val uiHandler = Handler(Looper.getMainLooper())
    private var lastTypingEventMs: Long = 0L

    private val statusUpdater = object : Runnable {
        override fun run() {
            refreshUiState()
            uiHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        setupSpinners()
        setupTypingCollector()
        setupTouchCollector()
        setupButtons()
        requestNotificationPermissionIfNeeded()
        refreshUiState()
    }

    override fun onStart() {
        super.onStart()
        uiHandler.post(statusUpdater)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(statusUpdater)
        super.onStop()
    }

    private fun initViews() {
        sessionTagInput = findViewById(R.id.sessionTagInput)
        userTypeSpinner = findViewById(R.id.userTypeSpinner)
        scenarioSpinner = findViewById(R.id.scenarioSpinner)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        openImeSettingsButton = findViewById(R.id.openImeSettingsButton)
        showImePickerButton = findViewById(R.id.showImePickerButton)
        openOpenBoardButton = findViewById(R.id.openOpenBoardButton)
        openboardStatusText = findViewById(R.id.openboardStatusText)
        statusText = findViewById(R.id.statusText)
        filePathText = findViewById(R.id.filePathText)
        typingEditText = findViewById(R.id.typingEditText)
        touchArea = findViewById(R.id.touchArea)
    }

    private fun setupSpinners() {
        val userTypeItems = listOf("owner", "impostor")
        val scenarioItems = listOf("static", "walking", "in_vehicle", "custom")
        userTypeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, userTypeItems).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        scenarioSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, scenarioItems).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun setupButtons() {
        startButton.setOnClickListener { startCollection() }
        stopButton.setOnClickListener { stopCollection() }
        openImeSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        showImePickerButton.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        openOpenBoardButton.setOnClickListener {
            val launchIntent = packageManager.getLaunchIntentForPackage(OPENBOARD_PACKAGE)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }
    }

    private fun setupTypingCollector() {
        typingEditText.addTextChangedListener(
            object : TextWatcher {
                private var beforeCount = 0
                private var startIndex = 0

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    beforeCount = count
                    startIndex = start
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!DataCollectionService.isCollecting()) return
                    val now = System.currentTimeMillis()
                    val deltaMs = if (lastTypingEventMs == 0L) null else now - lastTypingEventMs
                    lastTypingEventMs = now
                    DataCollectionService.logUiEvent(
                        this@MainActivity,
                        "keyboard",
                        jsonOf(
                            "event" to "text_changed",
                            "system_time_ms" to now,
                            "start_index" to start,
                            "before_count" to before,
                            "inserted_count" to count,
                            "total_length" to s?.length,
                            "delta_since_last_key_ms" to deltaMs,
                            "derived_operation" to when {
                                count > 0 && before == 0 -> "insert"
                                before > 0 && count == 0 -> "delete"
                                before > 0 && count > 0 -> "replace"
                                else -> "other"
                            },
                            "previous_before_count" to beforeCount,
                            "previous_start_index" to startIndex,
                        ),
                    )
                }

                override fun afterTextChanged(s: Editable?) = Unit
            },
        )

        typingEditText.setOnEditorActionListener { _, actionId, _ ->
            if (DataCollectionService.isCollecting()) {
                DataCollectionService.logUiEvent(
                    this@MainActivity,
                    "keyboard",
                    jsonOf(
                        "event" to "editor_action",
                        "action_id" to actionId,
                        "system_time_ms" to System.currentTimeMillis(),
                    ),
                )
            }
            false
        }
    }

    private fun setupTouchCollector() {
        touchArea.setOnTouchListener { _, event ->
            if (DataCollectionService.isCollecting()) {
                DataCollectionService.logUiEvent(
                    this@MainActivity,
                    "gesture",
                    jsonOf(
                        "source" to "touch_area",
                        "action" to actionToString(event.actionMasked),
                        "pointer_count" to event.pointerCount,
                        "x" to event.x,
                        "y" to event.y,
                        "pressure" to event.pressure,
                        "size" to event.size,
                        "event_time_ms" to event.eventTime,
                        "down_time_ms" to event.downTime,
                    ),
                )
            }
            true
        }

        typingEditText.setOnTouchListener { _, event ->
            if (DataCollectionService.isCollecting()) {
                DataCollectionService.logUiEvent(
                    this@MainActivity,
                    "gesture",
                    jsonOf(
                        "source" to "typing_edit_text",
                        "action" to actionToString(event.actionMasked),
                        "pointer_count" to event.pointerCount,
                        "x" to event.x,
                        "y" to event.y,
                        "pressure" to event.pressure,
                        "size" to event.size,
                        "event_time_ms" to event.eventTime,
                        "down_time_ms" to event.downTime,
                    ),
                )
            }
            false
        }
    }

    private fun startCollection() {
        val tag = sessionTagInput.text?.toString()?.trim().orEmpty()
        val userType = userTypeSpinner.selectedItem?.toString() ?: "owner"
        val scenario = scenarioSpinner.selectedItem?.toString() ?: "static"
        lastTypingEventMs = 0L
        DataCollectionService.start(this, tag, userType, scenario)
        refreshUiState()
    }

    private fun stopCollection() {
        DataCollectionService.stop(this)
        refreshUiState()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshUiState() {
        val isRunning = DataCollectionService.isCollecting()
        statusText.setText(if (isRunning) R.string.status_recording else R.string.status_idle)
        filePathText.text = "File: ${DataCollectionService.currentFilePath() ?: "n/a"}"
        startButton.isEnabled = !isRunning
        stopButton.isEnabled = isRunning
        refreshOpenBoardStatus()
    }

    @SuppressLint("SetTextI18n")
    private fun refreshOpenBoardStatus() {
        val isInstalled = isPackageInstalled(OPENBOARD_PACKAGE)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val isEnabled = imm.enabledInputMethodList.any { it.packageName == OPENBOARD_PACKAGE }
        val defaultIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val isSelected = defaultIme.contains(OPENBOARD_PACKAGE)

        openboardStatusText.text = "OpenBoard: installed=$isInstalled, enabled=$isEnabled, selected=$isSelected"
        openOpenBoardButton.isEnabled = isInstalled
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrElse { false }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS_CODE,
        )
    }

    private fun actionToString(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "down"
        MotionEvent.ACTION_MOVE -> "move"
        MotionEvent.ACTION_UP -> "up"
        MotionEvent.ACTION_CANCEL -> "cancel"
        MotionEvent.ACTION_POINTER_DOWN -> "pointer_down"
        MotionEvent.ACTION_POINTER_UP -> "pointer_up"
        else -> action.toString()
    }

    private fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in pairs) {
            when (value) {
                null -> json.put(key, JSONObject.NULL)
                is Float -> json.put(key, value.toDouble())
                else -> json.put(key, value)
            }
        }
        return json
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS_CODE = 1001
        private const val OPENBOARD_PACKAGE = "org.dslul.openboard.inputmethod.latin"
    }
}