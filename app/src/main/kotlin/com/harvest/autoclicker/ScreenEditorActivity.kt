package com.harvest.autoclicker

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/** Lets the user place points/swipes directly on a screenshot captured by
 *  the background discovery loop, instead of needing to be on the real app's
 *  screen live. Tap positions on the image map 1:1 to screen fractions since
 *  the screenshot spans the whole display. Never touches the currently
 *  active points/interval — always creates a brand new profile on save. */
class ScreenEditorActivity : AppCompatActivity() {

    private var points = listOf<ClickPoint>()
    private val swipes = mutableListOf<SwipeGesture>()
    private lateinit var canvas: ClickCanvasView
    private lateinit var screen: DiscoveredScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_screen_editor)

        val screenId = intent.getIntExtra(DiscoveredScreensActivity.EXTRA_SCREEN_ID, -1)
        screen = ClickPrefs.loadDiscoveredScreens(this).firstOrNull { it.id == screenId } ?: run {
            finish()
            return
        }

        val bytes = Base64.decode(screen.previewImage, Base64.NO_WRAP)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        findViewById<ImageView>(R.id.screenshotImage).setImageBitmap(bitmap)

        canvas = ClickCanvasView(this, null).apply {
            editing = true
            drawTint = false
            onPointsChanged = { updated -> points = updated }
            onPointTapped = { index, _, _ ->
                points = points.toMutableList().apply { removeAt(index) }
                setPoints(points)
            }
            onSwipeDrawn = { swipe ->
                swipes.add(swipe)
                setSwipes(swipes)
            }
            onSwipeTapped = { index ->
                if (index in swipes.indices) swipes.removeAt(index)
                setSwipes(swipes)
            }
        }
        findViewById<FrameLayout>(R.id.canvasContainer).addView(canvas)

        findViewById<Button>(R.id.cancelButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.saveProfileButton).setOnClickListener { saveProfile() }
    }

    private fun saveProfile() {
        if (points.isEmpty() && swipes.isEmpty()) {
            Toast.makeText(this, "Add at least one point or swipe first", Toast.LENGTH_SHORT).show()
            return
        }
        val name = findViewById<EditText>(R.id.profileNameInput).text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a profile name first", Toast.LENGTH_SHORT).show()
            return
        }
        val seconds = findViewById<EditText>(R.id.intervalInput).text.toString().toDoubleOrNull()
        if (seconds == null || seconds <= 0) {
            Toast.makeText(this, "Enter a valid interval in seconds", Toast.LENGTH_SHORT).show()
            return
        }

        ClickPrefs.createProfile(
            context = this,
            name = name,
            points = points,
            swipes = swipes,
            intervalMs = (seconds * 1000).toLong(),
            appPackage = screen.appPackage,
            referenceThumbnail = screen.matchThumbnail,
            previewImage = screen.previewImage
        )
        ClickPrefs.deleteDiscoveredScreen(this, screen.id)
        Toast.makeText(this, "Saved “$name”", Toast.LENGTH_SHORT).show()
        finish()
    }
}
