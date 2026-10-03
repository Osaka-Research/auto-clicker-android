package com.harvest.autoclicker

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ProfileListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var bindAppCheckbox: CheckBox
    private var lastForegroundPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_list)

        container = findViewById(R.id.profileListContainer)
        nameInput = findViewById(R.id.newProfileNameInput)
        bindAppCheckbox = findViewById(R.id.bindAppCheckbox)

        lastForegroundPackage = AutoClickAccessibilityService.instance?.lastForegroundPackage
        if (lastForegroundPackage != null) {
            bindAppCheckbox.text = "Auto-load this profile when ${appLabel(lastForegroundPackage!!)} opens"
            bindAppCheckbox.isChecked = true
        } else {
            bindAppCheckbox.text = "Auto-load (open the target app at least once first)"
            bindAppCheckbox.isEnabled = false
        }

        findViewById<Button>(R.id.saveProfileButton).setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Enter a name first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val boundApp = if (bindAppCheckbox.isChecked) lastForegroundPackage else null
            val thumbnail = AutoClickAccessibilityService.instance?.consumePendingReferenceThumbnail()
            val preview = AutoClickAccessibilityService.instance?.consumePendingPreviewImage()
            ClickPrefs.saveCurrentAsProfile(this, name, boundApp, thumbnail, preview)
            nameInput.text.clear()
            refreshList()
        }

        refreshList()
    }

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)

    private fun refreshList() {
        container.removeAllViews()
        ClickPrefs.loadProfiles(this).forEach { profile ->
            val row = LayoutInflater.from(this).inflate(R.layout.row_profile, container, false)
            row.findViewById<TextView>(R.id.profileName).text = profile.name
            val subtitle = buildList {
                profile.appPackage?.let { add("opens with ${appLabel(it)}") }
                if (profile.referenceThumbnail != null) add("recognizes screen")
            }.joinToString(" · ")
            row.findViewById<TextView>(R.id.profileApp).text = subtitle

            val previewView = row.findViewById<ImageView>(R.id.profilePreview)
            if (profile.previewImage != null) {
                val bytes = Base64.decode(profile.previewImage, Base64.NO_WRAP)
                previewView.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                previewView.visibility = View.VISIBLE
            }

            row.findViewById<Button>(R.id.loadProfileButton).setOnClickListener {
                ClickPrefs.applyProfile(this, profile.id)
                AutoClickAccessibilityService.instance?.reloadPoints()
                Toast.makeText(this, "Loaded “${profile.name}”", Toast.LENGTH_SHORT).show()
                finish()
            }
            row.findViewById<Button>(R.id.deleteProfileButton).setOnClickListener {
                ClickPrefs.deleteProfile(this, profile.id)
                refreshList()
            }

            container.addView(row)
        }
    }
}
