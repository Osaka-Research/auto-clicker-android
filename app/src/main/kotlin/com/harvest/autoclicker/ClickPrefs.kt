package com.harvest.autoclicker

import android.content.Context
import org.json.JSONArray

data class OcrRegion(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class ClickPoint(
    val nx: Float,
    val ny: Float,
    val wordId: Int? = null,
    val ocrWaitWordId: Int? = null,
    val ocrRegion: OcrRegion? = null,
    /** Wall-clock time this point was placed, so playback can reproduce the
     *  same order and gaps you placed things in instead of a flat stagger. */
    val recordedAtMs: Long = 0L
)
data class Word(val id: Int, val text: String)
data class SwipeGesture(
    val startNx: Float,
    val startNy: Float,
    val endNx: Float,
    val endNy: Float,
    val durationMs: Long = 300L,
    val recordedAtMs: Long = 0L,
    /** Full sampled curve (fractions of screen), start to end, for slicing-
     *  style swipes — null means "just a straight line start to end". */
    val path: List<Pair<Float, Float>>? = null
)
data class ClickProfile(
    val id: Int,
    val name: String,
    val pointsJson: String,
    val intervalMs: Long,
    val appPackage: String? = null,
    /** Base64 grayscale thumbnail of the screen this profile was captured
     *  against, used to auto-recognize that screen again later. */
    val referenceThumbnail: String? = null,
    val swipesJson: String = "[]",
    /** Larger base64 JPEG for showing an actual picture of the screen in the
     *  Profiles list, separate from the tiny [referenceThumbnail] used for
     *  matching. */
    val previewImage: String? = null
)
data class DiscoveredScreen(
    val id: Int,
    val matchThumbnail: String,
    val previewImage: String,
    val appPackage: String?,
    val capturedAtMs: Long,
    /** Text OCR'd off the whole screen at capture time, so you can tell
     *  screens apart by what they say without opening each one. */
    val ocrText: String? = null
)

/** Stores click points, the word dictionary, interval and run state so both
 *  the activity and the accessibility service (which can outlive the
 *  activity) read the same state. */
object ClickPrefs {
    private const val PREFS = "auto_clicker_prefs"
    private const val KEY_POINTS = "points"
    private const val KEY_INTERVAL_MS = "interval_ms"
    private const val KEY_RUNNING = "running"
    private const val KEY_WORDS = "words"
    private const val KEY_NEXT_WORD_ID = "next_word_id"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_NEXT_PROFILE_ID = "next_profile_id"
    private const val KEY_SWIPES = "swipes"
    private const val KEY_DISCOVERED = "discovered_screens"
    private const val KEY_NEXT_DISCOVERED_ID = "next_discovered_id"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun encodePointsJson(points: List<ClickPoint>): String {
        val array = JSONArray()
        points.forEach { point ->
            array.put(JSONArray().apply {
                put(point.nx)
                put(point.ny)
                put(point.wordId ?: -1)
                put(point.ocrWaitWordId ?: -1)
                val region = point.ocrRegion
                put(region?.left ?: -1f)
                put(region?.top ?: -1f)
                put(region?.right ?: -1f)
                put(region?.bottom ?: -1f)
                put(point.recordedAtMs)
            })
        }
        return array.toString()
    }

    fun decodePointsJson(encoded: String): List<ClickPoint> {
        if (encoded.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONArray(i) ?: return@mapNotNull null
                val nx = entry.optDouble(0, Double.NaN).toFloat()
                val ny = entry.optDouble(1, Double.NaN).toFloat()
                if (nx.isNaN() || ny.isNaN()) return@mapNotNull null
                val wordId = entry.optInt(2, -1).let { if (it < 0) null else it }
                val ocrWaitWordId = entry.optInt(3, -1).let { if (it < 0) null else it }
                val left = entry.optDouble(4, -1.0).toFloat()
                val region = if (left < 0f) null else OcrRegion(
                    left,
                    entry.optDouble(5, 0.0).toFloat(),
                    entry.optDouble(6, 0.0).toFloat(),
                    entry.optDouble(7, 0.0).toFloat()
                )
                val recordedAtMs = entry.optLong(8, 0L)
                ClickPoint(nx, ny, wordId, ocrWaitWordId, region, recordedAtMs)
            }
        }.getOrElse { emptyList() }
    }

    fun savePoints(context: Context, points: List<ClickPoint>) {
        prefs(context).edit().putString(KEY_POINTS, encodePointsJson(points)).apply()
    }

    fun loadPoints(context: Context): List<ClickPoint> {
        val encoded = prefs(context).getString(KEY_POINTS, null) ?: return emptyList()
        return decodePointsJson(encoded)
    }

    fun setPointWord(context: Context, index: Int, wordId: Int?) {
        val points = loadPoints(context).toMutableList()
        if (index !in points.indices) return
        points[index] = points[index].copy(wordId = wordId)
        savePoints(context, points)
    }

    fun setPointOcrCondition(context: Context, index: Int, waitWordId: Int?, region: OcrRegion?) {
        val points = loadPoints(context).toMutableList()
        if (index !in points.indices) return
        points[index] = points[index].copy(ocrWaitWordId = waitWordId, ocrRegion = region)
        savePoints(context, points)
    }

    fun encodeSwipesJson(swipes: List<SwipeGesture>): String {
        val array = JSONArray()
        swipes.forEach { s ->
            array.put(JSONArray().apply {
                put(s.startNx); put(s.startNy); put(s.endNx); put(s.endNy); put(s.durationMs); put(s.recordedAtMs)
                put(JSONArray().apply {
                    s.path?.forEach { (px, py) -> put(JSONArray().apply { put(px); put(py) }) }
                })
            })
        }
        return array.toString()
    }

    fun decodeSwipesJson(encoded: String): List<SwipeGesture> {
        if (encoded.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONArray(i) ?: return@mapNotNull null
                val path = entry.optJSONArray(6)?.let { pathArray ->
                    (0 until pathArray.length()).mapNotNull mapPoint@{ j ->
                        val point = pathArray.optJSONArray(j) ?: return@mapPoint null
                        val px = point.optDouble(0, Double.NaN).toFloat()
                        val py = point.optDouble(1, Double.NaN).toFloat()
                        if (px.isNaN() || py.isNaN()) null else px to py
                    }
                }?.takeIf { it.size >= 2 }
                SwipeGesture(
                    entry.optDouble(0, Double.NaN).toFloat(),
                    entry.optDouble(1, Double.NaN).toFloat(),
                    entry.optDouble(2, Double.NaN).toFloat(),
                    entry.optDouble(3, Double.NaN).toFloat(),
                    entry.optLong(4, 300L),
                    entry.optLong(5, 0L),
                    path
                ).takeIf { !it.startNx.isNaN() && !it.startNy.isNaN() && !it.endNx.isNaN() && !it.endNy.isNaN() }
            }
        }.getOrElse { emptyList() }
    }

    fun saveSwipes(context: Context, swipes: List<SwipeGesture>) {
        prefs(context).edit().putString(KEY_SWIPES, encodeSwipesJson(swipes)).apply()
    }

    fun loadSwipes(context: Context): List<SwipeGesture> {
        val encoded = prefs(context).getString(KEY_SWIPES, null) ?: return emptyList()
        return decodeSwipesJson(encoded)
    }

    fun addSwipe(context: Context, swipe: SwipeGesture) {
        saveSwipes(context, loadSwipes(context) + swipe)
    }

    fun removeSwipeAt(context: Context, index: Int) {
        val swipes = loadSwipes(context).toMutableList()
        if (index in swipes.indices) {
            swipes.removeAt(index)
            saveSwipes(context, swipes)
        }
    }

    fun saveIntervalMs(context: Context, intervalMs: Long) {
        prefs(context).edit().putLong(KEY_INTERVAL_MS, intervalMs).apply()
    }

    fun loadIntervalMs(context: Context): Long =
        prefs(context).getLong(KEY_INTERVAL_MS, 5000L)

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_RUNNING, running).apply()
    }

    fun isRunning(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RUNNING, false)

    fun loadWords(context: Context): List<Word> {
        val encoded = prefs(context).getString(KEY_WORDS, null) ?: return emptyList()
        if (encoded.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val id = entry.optInt("id", -1)
                val text = entry.optString("text", "")
                if (id < 0 || text.isBlank()) null else Word(id, text)
            }
        }.getOrElse { emptyList() }
    }

    private fun saveWords(context: Context, words: List<Word>) {
        val array = JSONArray()
        words.forEach { word ->
            array.put(org.json.JSONObject().apply {
                put("id", word.id)
                put("text", word.text)
            })
        }
        prefs(context).edit().putString(KEY_WORDS, array.toString()).apply()
    }

    fun addWord(context: Context, text: String): Word {
        val nextId = prefs(context).getInt(KEY_NEXT_WORD_ID, 1)
        val word = Word(nextId, text)
        saveWords(context, loadWords(context) + word)
        prefs(context).edit().putInt(KEY_NEXT_WORD_ID, nextId + 1).apply()
        return word
    }

    fun deleteWord(context: Context, id: Int) {
        saveWords(context, loadWords(context).filterNot { it.id == id })
        val points = loadPoints(context).map { point ->
            point.copy(
                wordId = if (point.wordId == id) null else point.wordId,
                ocrWaitWordId = if (point.ocrWaitWordId == id) null else point.ocrWaitWordId,
                ocrRegion = if (point.ocrWaitWordId == id) null else point.ocrRegion
            )
        }
        savePoints(context, points)
    }

    /** Profiles snapshot points + interval only — words are shared globally
     *  across profiles so the same dictionary works everywhere. */
    fun saveCurrentAsProfile(
        context: Context,
        name: String,
        appPackage: String? = null,
        referenceThumbnail: String? = null,
        previewImage: String? = null
    ): ClickProfile {
        val pointsJson = prefs(context).getString(KEY_POINTS, "[]") ?: "[]"
        val swipesJson = prefs(context).getString(KEY_SWIPES, "[]") ?: "[]"
        return storeNewProfile(
            context, name, pointsJson, swipesJson, loadIntervalMs(context),
            appPackage, referenceThumbnail, previewImage
        )
    }

    /** Builds a profile directly from a points/swipes list without touching
     *  the currently-active setup — used by the discovered-screen editor,
     *  which annotates a captured screenshot rather than the live overlay. */
    fun createProfile(
        context: Context,
        name: String,
        points: List<ClickPoint>,
        swipes: List<SwipeGesture>,
        intervalMs: Long,
        appPackage: String? = null,
        referenceThumbnail: String? = null,
        previewImage: String? = null
    ): ClickProfile = storeNewProfile(
        context, name, encodePointsJson(points), encodeSwipesJson(swipes), intervalMs,
        appPackage, referenceThumbnail, previewImage
    )

    private fun storeNewProfile(
        context: Context,
        name: String,
        pointsJson: String,
        swipesJson: String,
        intervalMs: Long,
        appPackage: String?,
        referenceThumbnail: String?,
        previewImage: String?
    ): ClickProfile {
        val nextId = prefs(context).getInt(KEY_NEXT_PROFILE_ID, 1)
        val profile = ClickProfile(
            nextId, name, pointsJson, intervalMs, appPackage, referenceThumbnail, swipesJson, previewImage
        )
        saveProfiles(context, loadProfiles(context) + profile)
        prefs(context).edit().putInt(KEY_NEXT_PROFILE_ID, nextId + 1).apply()
        return profile
    }

    fun loadProfiles(context: Context): List<ClickProfile> {
        val encoded = prefs(context).getString(KEY_PROFILES, null) ?: return emptyList()
        if (encoded.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val id = entry.optInt("id", -1)
                val name = entry.optString("name", "")
                if (id < 0 || name.isBlank()) return@mapNotNull null
                ClickProfile(
                    id, name, entry.optString("points", "[]"), entry.optLong("intervalMs", 5000L),
                    entry.optString("appPackage", "").ifBlank { null },
                    entry.optString("thumbnail", "").ifBlank { null },
                    entry.optString("swipes", "[]"),
                    entry.optString("preview", "").ifBlank { null }
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun saveProfiles(context: Context, profiles: List<ClickProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(org.json.JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("points", profile.pointsJson)
                put("intervalMs", profile.intervalMs)
                put("appPackage", profile.appPackage ?: "")
                put("thumbnail", profile.referenceThumbnail ?: "")
                put("swipes", profile.swipesJson)
                put("preview", profile.previewImage ?: "")
            })
        }
        prefs(context).edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    /** Replaces the currently active points/interval with this profile's. */
    fun applyProfile(context: Context, id: Int) {
        val profile = loadProfiles(context).firstOrNull { it.id == id } ?: return
        prefs(context).edit()
            .putString(KEY_POINTS, profile.pointsJson)
            .putString(KEY_SWIPES, profile.swipesJson)
            .putLong(KEY_INTERVAL_MS, profile.intervalMs)
            .apply()
    }

    fun deleteProfile(context: Context, id: Int) {
        saveProfiles(context, loadProfiles(context).filterNot { it.id == id })
    }

    /** Screens the background matcher has seen that don't match any saved
     *  profile yet — queued for the user to review and turn into profiles. */
    fun addDiscoveredScreen(
        context: Context,
        matchThumbnail: String,
        previewImage: String,
        appPackage: String?,
        ocrText: String? = null
    ): DiscoveredScreen {
        val nextId = prefs(context).getInt(KEY_NEXT_DISCOVERED_ID, 1)
        val screen = DiscoveredScreen(nextId, matchThumbnail, previewImage, appPackage, System.currentTimeMillis(), ocrText)
        saveDiscoveredScreens(context, loadDiscoveredScreens(context) + screen)
        prefs(context).edit().putInt(KEY_NEXT_DISCOVERED_ID, nextId + 1).apply()
        return screen
    }

    fun loadDiscoveredScreens(context: Context): List<DiscoveredScreen> {
        val encoded = prefs(context).getString(KEY_DISCOVERED, null) ?: return emptyList()
        if (encoded.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val id = entry.optInt("id", -1)
                val thumb = entry.optString("thumbnail", "")
                val preview = entry.optString("preview", "")
                if (id < 0 || thumb.isBlank() || preview.isBlank()) return@mapNotNull null
                DiscoveredScreen(
                    id, thumb, preview,
                    entry.optString("appPackage", "").ifBlank { null },
                    entry.optLong("capturedAtMs", 0L),
                    entry.optString("ocrText", "").ifBlank { null }
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun saveDiscoveredScreens(context: Context, screens: List<DiscoveredScreen>) {
        val array = JSONArray()
        screens.forEach { screen ->
            array.put(org.json.JSONObject().apply {
                put("id", screen.id)
                put("thumbnail", screen.matchThumbnail)
                put("preview", screen.previewImage)
                put("appPackage", screen.appPackage ?: "")
                put("capturedAtMs", screen.capturedAtMs)
                put("ocrText", screen.ocrText ?: "")
            })
        }
        prefs(context).edit().putString(KEY_DISCOVERED, array.toString()).apply()
    }

    fun deleteDiscoveredScreen(context: Context, id: Int) {
        saveDiscoveredScreens(context, loadDiscoveredScreens(context).filterNot { it.id == id })
    }
}
