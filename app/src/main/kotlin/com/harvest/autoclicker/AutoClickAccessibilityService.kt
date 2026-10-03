package com.harvest.autoclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.os.Bundle
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs

/** Runs while enabled in system Accessibility settings, independent of the
 *  activity's lifecycle, so tapping (and the overlay below) keep working
 *  while another app is in the foreground. State (points/interval/running)
 *  is read from ClickPrefs.
 *
 *  Two overlay windows, both TYPE_ACCESSIBILITY_OVERLAY (no SYSTEM_ALERT_WINDOW
 *  permission needed since the accessibility grant already covers it):
 *   - a full-screen [ClickCanvasView] that is touchable only while in edit
 *     mode (drag points onto the real app underneath); otherwise it's marked
 *     not-touchable so it never blocks the view or input of the app behind it.
 *   - a small draggable bubble: tap toggles start/stop, long-press toggles
 *     edit mode. */
class AutoClickAccessibilityService : AccessibilityService() {

    companion object {
        private const val ACTION_STOP = "com.harvest.autoclicker.ACTION_STOP"
        private const val NOTIFICATION_CHANNEL_ID = "auto_clicker_running"
        private const val NOTIFICATION_ID = 1
        private const val LONG_PRESS_MS = 450L
        private const val THUMBNAIL_SIZE = 24
        private const val PREVIEW_WIDTH = 240
        private const val IMAGE_MATCH_CHECK_MS = 2500L
        private const val IMAGE_MATCH_THRESHOLD = 18.0
        private const val DISCOVERY_COOLDOWN_MS = 8000L
        private const val MAX_DISCOVERED_SCREENS = 30
        private const val DWELL_DELAY_MS = 3000L
        private const val TEST_RUN_STEP_MS = 3000L

        @Volatile
        var instance: AutoClickAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var tickToken = 0

    /** Separate from [handler] on purpose: stopping the tap loop calls
     *  handler.removeCallbacksAndMessages(null), which must not also kill
     *  the independent, always-on image-match polling below. */
    private val imageMatchHandler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private var overlayCanvas: ClickCanvasView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var bubbleView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var footnotesView: TextView? = null
    private var footnotesParams: WindowManager.LayoutParams? = null

    /** Running tally of every word OCR has seen on screen, scanned only on
     *  ticks where the bubble has actually moved since the last scan (see
     *  [lastWordScanX]/[lastWordScanY]) — piggybacks the existing screenshot
     *  tick in [scheduleImageMatchCheck] rather than requesting its own.
     *  Rendered as a top-10 footnote list next to the bubble. */
    private val wordCounts = HashMap<String, Int>()
    private val wordScanRegex = Regex("[A-Za-z0-9]{2,}")
    private var lastWordScanX = Int.MIN_VALUE
    private var lastWordScanY = Int.MIN_VALUE
    private var editMode = false
    private var pickerView: View? = null
    private val dwellHandler = Handler(Looper.getMainLooper())
    private var dwellPending: Runnable? = null
    private var pendingOcrWaitWordId: Int? = null
    private val textRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Grayscale thumbnail of the screen as it looked right when edit mode
     *  was last turned off — offered to Profiles as this profile's reference
     *  image, since that's the moment the points were finalized against it. */
    var pendingReferenceThumbnail: String? = null
        private set
    var pendingPreviewImage: String? = null
        private set

    fun consumePendingReferenceThumbnail(): String? {
        val value = pendingReferenceThumbnail
        pendingReferenceThumbnail = null
        return value
    }

    fun consumePendingPreviewImage(): String? {
        val value = pendingPreviewImage
        pendingPreviewImage = null
        return value
    }

    private var lastAutoMatchedProfileId: Int? = null
    private var lastDiscoveryAtMs = 0L

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = stop()
    }

    override fun onServiceConnected() {
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        registerStopReceiver()
        addOverlayCanvas()
        addBubble()
        addFootnotes()
        if (ClickPrefs.isRunning(this)) startLoop()
        scheduleImageMatchCheck()
    }

    override fun onDestroy() {
        instance = null
        stopLoop()
        imageMatchHandler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(stopReceiver) }
        overlayCanvas?.let { runCatching { windowManager.removeView(it) } }
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        footnotesView?.let { runCatching { windowManager.removeView(it) } }
        pickerView?.let { runCatching { windowManager.removeView(it) } }
        dwellHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Last foreground app seen (excluding this app itself) — used both to
     *  offer "bind to this app" when saving a profile, and to auto-switch
     *  profiles when a bound app comes back to the front. */
    var lastForegroundPackage: String? = null
        private set

    /** True while this app's own UI (Profiles, Words, MainActivity…) is in
     *  front — the image-match/discovery loop must not screenshot itself. */
    private var selfIsForeground = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) {
            selfIsForeground = true
            return
        }
        selfIsForeground = false
        if (pkg == lastForegroundPackage) return
        lastForegroundPackage = pkg

        val profile = ClickPrefs.loadProfiles(this).firstOrNull { it.appPackage == pkg } ?: return
        ClickPrefs.applyProfile(this, profile.id)
        reloadPoints()
        Toast.makeText(this, "Auto-loaded “${profile.name}”", Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() {}

    /** Called by MainActivity when it changes points/interval/running state. */
    fun reloadPoints() {
        overlayCanvas?.setPoints(ClickPrefs.loadPoints(this))
        overlayCanvas?.setSwipes(ClickPrefs.loadSwipes(this))
    }

    fun refreshAndStart() {
        stopLoop()
        startLoop()
    }

    fun setEditMode(enabled: Boolean) {
        editMode = enabled
        overlayCanvas?.editing = enabled
        dwellHandler.removeCallbacksAndMessages(null)
        dwellPending = null
        bubbleView?.text = ""
        if (!enabled) {
            hideWordPicker()
            captureScreenBitmap { bitmap ->
                pendingReferenceThumbnail = bitmap?.let { encodeThumbnail(it) }
                pendingPreviewImage = bitmap?.let { encodePreview(it) }
            }
        }
        val params = overlayParams ?: return
        if (enabled) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(overlayCanvas, params) }
        bubbleView?.setBackgroundResource(
            when {
                enabled -> R.drawable.bubble_edit
                ClickPrefs.isRunning(this) -> R.drawable.bubble_running
                else -> R.drawable.bubble_idle
            }
        )
    }

    fun toggleRunning() {
        if (ClickPrefs.isRunning(this)) {
            stop()
            return
        }
        if (ClickPrefs.loadPoints(this).isEmpty()) {
            Toast.makeText(this, "Long-press the bubble to place points first", Toast.LENGTH_SHORT).show()
            return
        }
        ClickPrefs.setRunning(this, true)
        startLoop()
    }

    fun stop() {
        ClickPrefs.setRunning(this, false)
        stopLoop()
        dwellHandler.removeCallbacksAndMessages(null)
        dwellPending = null
        bubbleView?.text = ""
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        if (!editMode) bubbleView?.setBackgroundResource(R.drawable.bubble_idle)
    }

    private fun startLoop() {
        if (!ClickPrefs.isRunning(this)) return
        showRunningNotification()
        if (!editMode) bubbleView?.setBackgroundResource(R.drawable.bubble_running)
        tickToken++
        scheduleRound(tickToken)
    }

    private fun stopLoop() {
        tickToken++
        handler.removeCallbacksAndMessages(null)
    }

    /** Either a tap or a swipe, ordered by when it was originally placed so
     *  playback reproduces the same sequence and — as closely as the min/max
     *  clamp allows — the same pacing you placed them with. */
    private sealed class TimedAction(val recordedAtMs: Long) {
        class Tap(val point: ClickPoint) : TimedAction(point.recordedAtMs)
        class Swipe(val swipe: SwipeGesture) : TimedAction(swipe.recordedAtMs)
    }

    private fun scheduleRound(token: Int) {
        if (token != tickToken || !ClickPrefs.isRunning(this)) return

        val points = ClickPrefs.loadPoints(this)
        val swipes = ClickPrefs.loadSwipes(this)
        val intervalMs = ClickPrefs.loadIntervalMs(this)
        val defaultGapMs = 150L
        val minGapMs = 30L
        val maxGapMs = 3000L

        val actions = (points.map { TimedAction.Tap(it) } + swipes.map { TimedAction.Swipe(it) })
            .sortedBy { it.recordedAtMs }

        var cumulativeDelay = 0L
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                val previous = actions[index - 1]
                val rawGap = action.recordedAtMs - previous.recordedAtMs
                cumulativeDelay += if (rawGap <= 0) defaultGapMs else rawGap.coerceIn(minGapMs, maxGapMs)
            }
            val delay = cumulativeDelay
            handler.postDelayed({
                if (token == tickToken) when (action) {
                    is TimedAction.Tap -> tap(action.point)
                    is TimedAction.Swipe -> performSwipe(action.swipe)
                }
            }, delay)
        }

        handler.postDelayed({ scheduleRound(token) }, intervalMs)
    }

    /** Plays the current points/swipes through once, spaced out (not your
     *  recorded timing) so you can watch each step land before turning on
     *  the real continuous loop. Does not touch the running/interval state. */
    fun startTestRun() {
        if (ClickPrefs.isRunning(this)) {
            Toast.makeText(this, "Stop the clicker first, then test", Toast.LENGTH_SHORT).show()
            return
        }
        val actions = (ClickPrefs.loadPoints(this).map { TimedAction.Tap(it) } +
            ClickPrefs.loadSwipes(this).map { TimedAction.Swipe(it) })
            .sortedBy { it.recordedAtMs }

        if (actions.isEmpty()) {
            Toast.makeText(this, "Nothing placed yet", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Test run: ${actions.size} step(s), ${TEST_RUN_STEP_MS / 1000}s apart", Toast.LENGTH_SHORT).show()
        actions.forEachIndexed { index, action ->
            handler.postDelayed({
                when (action) {
                    is TimedAction.Tap -> tap(action.point)
                    is TimedAction.Swipe -> performSwipe(action.swipe)
                }
            }, index * TEST_RUN_STEP_MS)
        }
    }

    private fun performSwipe(swipe: SwipeGesture) {
        val metrics = resources.displayMetrics
        fun px(nx: Float) = (nx * metrics.widthPixels).coerceIn(0f, metrics.widthPixels - 1f)
        fun py(ny: Float) = (ny * metrics.heightPixels).coerceIn(0f, metrics.heightPixels - 1f)
        overlayCanvas?.flashTapAt(swipe.startNx, swipe.startNy)

        val path = Path()
        val curve = swipe.path
        if (curve != null && curve.size >= 2) {
            path.moveTo(px(curve[0].first), py(curve[0].second))
            for (i in 1 until curve.size) path.lineTo(px(curve[i].first), py(curve[i].second))
        } else {
            path.moveTo(px(swipe.startNx), py(swipe.startNy))
            path.lineTo(px(swipe.endNx), py(swipe.endNy))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, swipe.durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /** Dispatches the tap immediately, unless the point has an OCR wait
     *  condition — then it screenshots the point's region first and only
     *  taps once the target word's text is found in it. If no match, this
     *  round is skipped silently; the next round tries again. */
    private fun tap(point: ClickPoint) {
        val region = point.ocrRegion
        val waitWordId = point.ocrWaitWordId
        val targetText = waitWordId?.let { id -> ClickPrefs.loadWords(this).firstOrNull { it.id == id }?.text }

        if (region == null || targetText == null) {
            performTap(point)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    result.hardwareBuffer.close()
                    val bitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false) ?: return
                    val cropped = cropToRegion(bitmap, region) ?: return
                    textRecognizer.process(InputImage.fromBitmap(cropped, 0))
                        .addOnSuccessListener { visionText ->
                            if (visionText.text.contains(targetText, ignoreCase = true)) performTap(point)
                        }
                }

                override fun onFailure(errorCode: Int) {}
            }
        )
    }

    private fun cropToRegion(bitmap: Bitmap, region: OcrRegion): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val left = (region.left * w).toInt().coerceIn(0, w - 1)
        val top = (region.top * h).toInt().coerceIn(0, h - 1)
        val right = (region.right * w).toInt().coerceIn(left + 1, w)
        val bottom = (region.bottom * h).toInt().coerceIn(top + 1, h)
        return runCatching { Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top) }.getOrNull()
    }

    /** Screenshots the whole screen. Callers derive whatever encoding they
     *  need from the bitmap (tiny fingerprint for matching, larger preview
     *  for display) so a single screenshot serves both. */
    private fun captureScreenBitmap(onDone: (Bitmap?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    result.hardwareBuffer.close()
                    onDone(hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false))
                }

                override fun onFailure(errorCode: Int) = onDone(null)
            }
        )
    }

    private fun encodePreview(bitmap: Bitmap): String {
        val previewHeight = (bitmap.height * (PREVIEW_WIDTH.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, PREVIEW_WIDTH, previewHeight, true)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 60, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun encodeThumbnail(bitmap: Bitmap): String {
        val scaled = Bitmap.createScaledBitmap(bitmap, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
        val bytes = ByteArray(THUMBNAIL_SIZE * THUMBNAIL_SIZE)
        var i = 0
        for (y in 0 until THUMBNAIL_SIZE) {
            for (x in 0 until THUMBNAIL_SIZE) {
                val pixel = scaled.getPixel(x, y)
                val gray = (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) +
                    android.graphics.Color.blue(pixel)) / 3
                bytes[i++] = gray.toByte()
            }
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** Average per-pixel grayscale difference (0-255, lower = more similar). */
    private fun thumbnailDiff(a: String, b: String): Double {
        val bytesA = Base64.decode(a, Base64.NO_WRAP)
        val bytesB = Base64.decode(b, Base64.NO_WRAP)
        if (bytesA.size != bytesB.size || bytesA.isEmpty()) return Double.MAX_VALUE
        var total = 0
        for (i in bytesA.indices) {
            total += abs((bytesA[i].toInt() and 0xFF) - (bytesB[i].toInt() and 0xFF))
        }
        return total / bytesA.size.toDouble()
    }

    /** Tallies every word OCR finds on the current screen into the running
     *  [wordCounts] map, then re-renders the top-10 footnote list. Shares the
     *  screenshot already taken for [scheduleImageMatchCheck] instead of
     *  requesting a second one (only one screenshot/gesture can be in flight
     *  on an accessibility service at a time). */
    private fun scanWordOccurrences(bitmap: Bitmap) {
        textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                wordScanRegex.findAll(visionText.text).forEach { match ->
                    val word = match.value.lowercase()
                    wordCounts[word] = (wordCounts[word] ?: 0) + 1
                }
                updateFootnotes()
            }
    }

    private fun updateFootnotes() {
        val top = wordCounts.entries.sortedByDescending { it.value }.take(10)
        val view = footnotesView ?: return
        if (top.isEmpty()) {
            view.visibility = View.GONE
            return
        }
        view.text = top.mapIndexed { i, entry -> "${i + 1}. ${entry.key} (${entry.value})" }
            .joinToString("\n")
        view.visibility = View.VISIBLE
        repositionFootnotes()
    }

    /** Every tick: try to recognize the current screen against saved
     *  profiles' reference images; if it matches none of those AND none of
     *  the screens already queued for review, capture it as a new
     *  [DiscoveredScreen] so the user can come back and place points on it
     *  later — this is what builds up the "seen this screen before" library
     *  automatically instead of requiring a manual save each time. */
    private fun scheduleImageMatchCheck() {
        imageMatchHandler.postDelayed({
            val profiles = ClickPrefs.loadProfiles(this).filter { it.referenceThumbnail != null }
            val discovered = ClickPrefs.loadDiscoveredScreens(this)
            // Skip entirely while the tap loop is active: a service can only
            // have one gesture/screenshot operation in flight at a time, so
            // this screenshot would otherwise contend with dispatchGesture()
            // and stall real taps for a beat every tick.
            if (ClickPrefs.isRunning(this) || selfIsForeground ||
                (profiles.isEmpty() && discovered.size >= MAX_DISCOVERED_SCREENS)
            ) {
                scheduleImageMatchCheck()
                return@postDelayed
            }
            captureScreenBitmap { bitmap ->
                if (bitmap == null) {
                    scheduleImageMatchCheck()
                    return@captureScreenBitmap
                }
                val bubble = bubbleParams
                if (bubble != null && (bubble.x != lastWordScanX || bubble.y != lastWordScanY)) {
                    lastWordScanX = bubble.x
                    lastWordScanY = bubble.y
                    scanWordOccurrences(bitmap)
                }
                val currentThumb = encodeThumbnail(bitmap)

                val bestProfile = profiles.minByOrNull { thumbnailDiff(it.referenceThumbnail!!, currentThumb) }
                val profileScore = bestProfile?.let { thumbnailDiff(it.referenceThumbnail!!, currentThumb) }
                    ?: Double.MAX_VALUE

                if (bestProfile != null && profileScore < IMAGE_MATCH_THRESHOLD) {
                    if (bestProfile.id != lastAutoMatchedProfileId) {
                        lastAutoMatchedProfileId = bestProfile.id
                        ClickPrefs.applyProfile(this, bestProfile.id)
                        reloadPoints()
                        Toast.makeText(this, "Recognized screen — loaded “${bestProfile.name}”", Toast.LENGTH_SHORT).show()
                    }
                    scheduleImageMatchCheck()
                    return@captureScreenBitmap
                }
                lastAutoMatchedProfileId = null

                val matchesDiscovered = discovered.any { thumbnailDiff(it.matchThumbnail, currentThumb) < IMAGE_MATCH_THRESHOLD }
                val now = System.currentTimeMillis()
                if (!matchesDiscovered && discovered.size < MAX_DISCOVERED_SCREENS &&
                    now - lastDiscoveryAtMs > DISCOVERY_COOLDOWN_MS
                ) {
                    lastDiscoveryAtMs = now
                    val preview = encodePreview(bitmap)
                    textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { visionText ->
                            val ocrText = visionText.text.trim().take(300).ifBlank { null }
                            ClickPrefs.addDiscoveredScreen(this, currentThumb, preview, lastForegroundPackage, ocrText)
                            Toast.makeText(this, "New screen spotted — check Discovered Screens", Toast.LENGTH_SHORT).show()
                            scheduleImageMatchCheck()
                        }
                        .addOnFailureListener {
                            ClickPrefs.addDiscoveredScreen(this, currentThumb, preview, lastForegroundPackage)
                            Toast.makeText(this, "New screen spotted — check Discovered Screens", Toast.LENGTH_SHORT).show()
                            scheduleImageMatchCheck()
                        }
                } else {
                    scheduleImageMatchCheck()
                }
            }
        }, IMAGE_MATCH_CHECK_MS)
    }

    private fun performTap(point: ClickPoint) {
        val metrics = resources.displayMetrics
        val x = (point.nx * metrics.widthPixels).coerceIn(0f, metrics.widthPixels - 1f)
        val y = (point.ny * metrics.heightPixels).coerceIn(0f, metrics.heightPixels - 1f)
        overlayCanvas?.flashTapAt(point.nx, point.ny)

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val text = point.wordId?.let { id -> ClickPrefs.loadWords(this).firstOrNull { it.id == id }?.text }
        if (text == null) {
            dispatchGesture(gesture, null, null)
            return
        }

        // Wait for the gesture to actually finish (not a guessed delay) before
        // looking for the field it should have focused, then retry the focus
        // lookup for a bit — some apps take a moment to attach input focus
        // after a synthetic tap.
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                typeIntoFocusedFieldWithRetry(text, attemptsLeft = 10)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                typeIntoFocusedFieldWithRetry(text, attemptsLeft = 10)
            }
        }, handler)
    }

    private fun typeIntoFocusedFieldWithRetry(text: String, attemptsLeft: Int) {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            return
        }
        if (attemptsLeft > 0) {
            handler.postDelayed({ typeIntoFocusedFieldWithRetry(text, attemptsLeft - 1) }, 120L)
        }
    }

    private fun addOverlayCanvas() {
        val view = ClickCanvasView(this, null)
        view.setPoints(ClickPrefs.loadPoints(this))
        view.setSwipes(ClickPrefs.loadSwipes(this))
        view.onPointsChanged = { points -> ClickPrefs.savePoints(this, points) }
        view.onPointTapped = { index, x, y -> showWordPicker(index, x, y) }
        view.onOcrBoxDrawn = { index, region ->
            val waitWordId = pendingOcrWaitWordId
            pendingOcrWaitWordId = null
            ClickPrefs.setPointOcrCondition(this, index, waitWordId, region)
            reloadPoints()
        }
        view.onSwipeDrawn = { swipe ->
            ClickPrefs.addSwipe(this, swipe)
            reloadPoints()
        }
        view.onSwipeTapped = { index ->
            ClickPrefs.removeSwipeAt(this, index)
            reloadPoints()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        windowManager.addView(view, params)
        overlayCanvas = view
        overlayParams = params
    }

    private fun showWordPicker(index: Int, screenX: Float, screenY: Float) {
        hideWordPicker()
        val density = resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (10 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.picker_bg)
            setPadding(padH / 2, padH / 2, padH / 2, padH / 2)
        }

        val currentWordId = ClickPrefs.loadPoints(this).getOrNull(index)?.wordId

        fun addOption(label: String, isChecked: Boolean, action: () -> Unit) {
            container.addView(TextView(this).apply {
                text = if (isChecked) "✓ $label" else label
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(padH, padV, padH, padV)
                setOnClickListener {
                    action()
                    hideWordPicker()
                }
            })
        }

        addOption("None", currentWordId == null) {
            ClickPrefs.setPointWord(this, index, null)
            reloadPoints()
        }
        ClickPrefs.loadWords(this).forEach { word ->
            addOption(word.text, currentWordId == word.id) {
                ClickPrefs.setPointWord(this, index, word.id)
                reloadPoints()
            }
        }
        val hasOcrWait = ClickPrefs.loadPoints(this).getOrNull(index)?.ocrWaitWordId != null
        addOption(if (hasOcrWait) "Wait for text… (change)" else "Wait for text before tap…", false) {
            showOcrWordPicker(index, screenX, screenY)
        }
        if (hasOcrWait) {
            addOption("Clear wait condition", false) {
                ClickPrefs.setPointOcrCondition(this, index, null, null)
                reloadPoints()
            }
        }
        addOption("Remove point", false) {
            val points = ClickPrefs.loadPoints(this).toMutableList()
            if (index in points.indices) {
                points.removeAt(index)
                ClickPrefs.savePoints(this, points)
                reloadPoints()
            }
        }
        addOption("Cancel", false) {}

        showPickerAt(container, screenX, screenY)
    }

    /** Second-level picker: choose which saved word this point should wait to
     *  see on screen, then switch the canvas into box-drawing mode so the
     *  user drags out the region to watch. */
    private fun showOcrWordPicker(index: Int, screenX: Float, screenY: Float) {
        hideWordPicker()
        val words = ClickPrefs.loadWords(this)
        val container = pickerContainer()

        if (words.isEmpty()) {
            addPickerOption(container, "No saved words — tap to add one") {
                startActivity(Intent(this, WordListActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } else {
            words.forEach { word ->
                addPickerOption(container, word.text) {
                    pendingOcrWaitWordId = word.id
                    Toast.makeText(this, "Drag on screen to draw the wait-region", Toast.LENGTH_SHORT).show()
                    overlayCanvas?.startDrawingOcrBox(index)
                }
            }
        }
        addPickerOption(container, "Cancel") {}

        showPickerAt(container, screenX, screenY)
    }

    private fun pickerContainer(): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.picker_bg)
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
        }
    }

    private fun addPickerOption(container: LinearLayout, label: String, action: () -> Unit) {
        val density = resources.displayMetrics.density
        container.addView(TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            setOnClickListener {
                action()
                hideWordPicker()
            }
        })
    }

    private fun showPickerAt(container: LinearLayout, screenX: Float, screenY: Float) {
        val density = resources.displayMetrics.density
        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenX.toInt().coerceIn(0, (metrics.widthPixels - 220 * density).toInt().coerceAtLeast(0))
            y = screenY.toInt().coerceIn(0, (metrics.heightPixels - 240 * density).toInt().coerceAtLeast(0))
        }

        windowManager.addView(container, params)
        pickerView = container
    }

    private fun hideWordPicker() {
        pickerView?.let { runCatching { windowManager.removeView(it) } }
        pickerView = null
    }

    /** The one floating control: short tap toggles start/stop, long-press
     *  (held still) toggles edit mode, and dragging it to a new resting spot
     *  arms a dwell-click countdown there — leave it still and it taps that
     *  exact spot every [DWELL_DELAY_MS], showing a live countdown, until you
     *  move it again. So it's the run control AND a precision dwell cursor
     *  in one dot, not two separate floating things. */
    /** Small footnote-style panel of the top-10 OCR'd words, kept parked next
     *  to the bubble so it reads like an annotation on it rather than a
     *  separate floating window. Never touchable — taps must still pass
     *  through to the app underneath. */
    private fun addFootnotes() {
        val view = TextView(this).apply {
            setBackgroundResource(R.drawable.picker_bg)
            setTextColor(Color.WHITE)
            textSize = 11f
            val density = resources.displayMetrics.density
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        windowManager.addView(view, params)
        footnotesView = view
        footnotesParams = params
    }

    /** Parks the footnote panel beside the bubble: to its right if there's
     *  room, otherwise to its left, clamped so it never runs off-screen. */
    private fun repositionFootnotes() {
        val bubble = bubbleParams ?: return
        val params = footnotesParams ?: return
        val view = footnotesView ?: return
        val metrics = resources.displayMetrics
        val density = metrics.density
        val sizePx = (density * 48).toInt()
        val margin = (8 * density).toInt()
        val assumedWidth = (150 * density).toInt()

        val fitsRight = bubble.x + sizePx + margin + assumedWidth <= metrics.widthPixels
        params.x = if (fitsRight) bubble.x + sizePx + margin else (bubble.x - assumedWidth - margin).coerceAtLeast(0)
        params.y = bubble.y.coerceIn(0, (metrics.heightPixels - (40 * density).toInt()).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun addBubble() {
        val view = TextView(this).apply {
            setBackgroundResource(R.drawable.bubble_idle)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val sizePx = (resources.displayMetrics.density * 48).toInt()

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = resources.displayMetrics.heightPixels / 3
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressFired = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var longPressPending = Runnable {}

        fun cancelDwell() {
            dwellPending?.let { dwellHandler.removeCallbacks(it) }
            dwellPending = null
            view.text = ""
        }

        fun scheduleDwell() {
            cancelDwell()
            var secondsLeft = (DWELL_DELAY_MS / 1000L).toInt()
            view.text = secondsLeft.toString()
            val tick = object : Runnable {
                override fun run() {
                    secondsLeft--
                    if (secondsLeft > 0) {
                        view.text = secondsLeft.toString()
                        dwellPending = this
                        dwellHandler.postDelayed(this, 1000L)
                    } else {
                        view.text = ""
                        val x = (params.x + sizePx / 2).toFloat()
                        val y = (params.y + sizePx / 2).toFloat()
                        performTap(ClickPoint(x / resources.displayMetrics.widthPixels, y / resources.displayMetrics.heightPixels))
                        scheduleDwell() // still resting — keep counting down and tapping until moved
                    }
                }
            }
            dwellPending = tick
            dwellHandler.postDelayed(tick, 1000L)
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    cancelDwell()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longPressFired = false
                    longPressPending = Runnable {
                        longPressFired = true
                        setEditMode(!editMode)
                    }
                    handler.postDelayed(longPressPending, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        if (!moved) handler.removeCallbacks(longPressPending)
                        moved = true
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                        repositionFootnotes()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressPending)
                    if (event.action == MotionEvent.ACTION_UP) {
                        if (moved) {
                            scheduleDwell()
                        } else if (!longPressFired) {
                            toggleRunning()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(view, params)
        bubbleView = view
        bubbleParams = params
    }

    private fun registerStopReceiver() {
        val filter = IntentFilter(ACTION_STOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, filter)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Auto Clicker running",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun showRunningNotification() {
        val stopIntent = Intent(ACTION_STOP).setPackage(packageName)
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Auto Clicker running")
            .setContentText("Tapping ${ClickPrefs.loadPoints(this).size} point(s) every " +
                "${ClickPrefs.loadIntervalMs(this) / 1000.0}s")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }
}
