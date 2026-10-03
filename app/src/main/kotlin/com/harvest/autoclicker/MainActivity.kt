package com.harvest.autoclicker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var pointsCountText: TextView
    private lateinit var intervalInput: EditText
    private lateinit var startStopButton: Button
    private lateinit var pointsListContainer: LinearLayout

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        pointsCountText = findViewById(R.id.pointsCountText)
        intervalInput = findViewById(R.id.intervalInput)
        startStopButton = findViewById(R.id.startStopButton)
        pointsListContainer = findViewById(R.id.pointsListContainer)

        findViewById<Button>(R.id.wordsButton).setOnClickListener {
            startActivity(Intent(this, WordListActivity::class.java))
        }

        findViewById<Button>(R.id.profilesButton).setOnClickListener {
            startActivity(Intent(this, ProfileListActivity::class.java))
        }

        findViewById<Button>(R.id.discoveredScreensButton).setOnClickListener {
            startActivity(Intent(this, DiscoveredScreensActivity::class.java))
        }

        intervalInput.setText((ClickPrefs.loadIntervalMs(this) / 1000.0).toString())

        findViewById<Button>(R.id.enableServiceButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.placePointsButton).setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            AutoClickAccessibilityService.instance?.setEditMode(true)
            moveTaskToBack(true)
        }

        findViewById<Button>(R.id.clearButton).setOnClickListener {
            ClickPrefs.savePoints(this, emptyList())
            AutoClickAccessibilityService.instance?.reloadPoints()
            refreshStatus()
        }

        startStopButton.setOnClickListener { toggleRunning() }

        findViewById<Button>(R.id.testRunButton).setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            AutoClickAccessibilityService.instance?.startTestRun()
            moveTaskToBack(true)
        }

        requestNotificationPermissionIfNeeded()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        intervalInput.setText((ClickPrefs.loadIntervalMs(this) / 1000.0).toString())
        refreshStatus()
    }

    private fun toggleRunning() {
        if (ClickPrefs.isRunning(this)) {
            AutoClickAccessibilityService.instance?.stop() ?: ClickPrefs.setRunning(this, false)
            refreshStatus()
            return
        }

        if (ClickPrefs.loadPoints(this).isEmpty()) {
            Toast.makeText(this, "Tap \"Place points\" and add at least one point first", Toast.LENGTH_SHORT).show()
            return
        }

        val seconds = intervalInput.text.toString().toDoubleOrNull()
        if (seconds == null || seconds <= 0) {
            Toast.makeText(this, "Enter a valid interval in seconds", Toast.LENGTH_SHORT).show()
            return
        }
        ClickPrefs.saveIntervalMs(this, (seconds * 1000).toLong())

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Enable Auto Clicker in Accessibility settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        ClickPrefs.setRunning(this, true)
        AutoClickAccessibilityService.instance?.refreshAndStart()
        refreshStatus()
    }

    private fun refreshStatus() {
        val running = ClickPrefs.isRunning(this)
        statusText.text = if (running) "Running" else "Stopped"
        statusText.setTextColor(
            ContextCompat.getColor(this, if (running) R.color.running else R.color.stopped)
        )
        startStopButton.text = if (running) "Stop" else "Start"
        val points = ClickPrefs.loadPoints(this)
        pointsCountText.text = "${points.size} points set"
        rebuildPointsList(points)
    }

    private fun rebuildPointsList(points: List<ClickPoint>) {
        pointsListContainer.removeAllViews()
        val words = ClickPrefs.loadWords(this)
        val labels = listOf("None") + words.map { it.text }

        points.forEachIndexed { index, point ->
            val row = LayoutInflater.from(this).inflate(R.layout.row_point, pointsListContainer, false)
            row.findViewById<TextView>(R.id.pointLabel).text = "Point ${index + 1}"

            val spinner = row.findViewById<Spinner>(R.id.pointWordSpinner)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            val selectedIndex = words.indexOfFirst { it.id == point.wordId } + 1
            spinner.setSelection(selectedIndex.coerceAtLeast(0))

            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val wordId = if (position == 0) null else words[position - 1].id
                    if (wordId != point.wordId) {
                        ClickPrefs.setPointWord(this@MainActivity, index, wordId)
                        AutoClickAccessibilityService.instance?.reloadPoints()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            pointsListContainer.addView(row)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${AutoClickAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            .asSequence().any { it.equals(expected, ignoreCase = true) }
    }

    private fun TextUtils.SimpleStringSplitter.asSequence(): Sequence<String> = sequence {
        while (hasNext()) yield(next())
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
