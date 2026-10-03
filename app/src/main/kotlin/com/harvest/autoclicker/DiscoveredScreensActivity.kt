package com.harvest.autoclicker

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date

class DiscoveredScreensActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCREEN_ID = "screen_id"
    }

    private lateinit var container: LinearLayout
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_discovered_screens)

        container = findViewById(R.id.screenListContainer)
        emptyText = findViewById(R.id.emptyText)
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun appLabel(packageName: String?): String {
        if (packageName == null) return "unknown app"
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }

    private fun refreshList() {
        container.removeAllViews()
        val screens = ClickPrefs.loadDiscoveredScreens(this).sortedByDescending { it.capturedAtMs }
        emptyText.visibility = if (screens.isEmpty()) View.VISIBLE else View.GONE

        screens.forEach { screen ->
            val row = LayoutInflater.from(this).inflate(R.layout.row_discovered_screen, container, false)

            val bytes = Base64.decode(screen.previewImage, Base64.NO_WRAP)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            row.findViewById<ImageView>(R.id.screenPreview).setImageBitmap(bitmap)

            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(screen.capturedAtMs))
            row.findViewById<TextView>(R.id.screenCaption).text = "${appLabel(screen.appPackage)} · $time"
            row.findViewById<TextView>(R.id.screenOcrText).text = screen.ocrText?.replace("\n", " ") ?: ""

            row.setOnClickListener {
                startActivity(
                    Intent(this, ScreenEditorActivity::class.java).putExtra(EXTRA_SCREEN_ID, screen.id)
                )
            }
            row.findViewById<Button>(R.id.deleteScreenButton).setOnClickListener {
                ClickPrefs.deleteDiscoveredScreen(this, screen.id)
                refreshList()
            }

            container.addView(row)
        }
    }
}
