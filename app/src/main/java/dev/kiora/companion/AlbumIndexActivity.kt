package dev.kiora.companion

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Size
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import dev.kiora.embedding.mediapipe.EmbeddingGemma2Port
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings screen for importing the album embedding model and indexing granted photos. */
class AlbumIndexActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { getSharedPreferences("album_search", MODE_PRIVATE) }
    private val index by lazy { AlbumIndexStore(this) }
    private val collection by lazy {
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    private var port: EmbeddingGemma2Port? = null
    private var work: Job? = null
    private var activityNote = ""
    private lateinit var modelStatus: TextView
    private lateinit var permissionStatus: TextView
    private lateinit var indexStatus: TextView
    private lateinit var modelButton: TextView
    private lateinit var indexButton: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchButton: TextView
    private lateinit var searchStatus: TextView
    private lateinit var searchResults: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        buildUi()
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (::permissionStatus.isInitialized) refreshState()
    }

    override fun onDestroy() {
        val running = work
        running?.cancel()
        scope.cancel()
        val active = port
        port = null
        CoroutineScope(Dispatchers.IO).launch {
            running?.join()
            active?.close()
            index.close()
        }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        setContentView(root)
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(20), 0)
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(64)))
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(INK)
            gravity = Gravity.CENTER
            contentDescription = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(38), -1))
        header.addView(TextView(this).apply {
            text = "相册模型与索引"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
            setPadding(dp(8), 0, 0, 0)
        })

        val scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(40))
        }
        scroll.addView(body)
        body.addView(label("导入端侧模型并为已授权照片建立索引。照片与索引都留在本机。",
            14f, MUTED).apply { setPadding(0, 0, 0, dp(22)) })

        modelStatus = label("", 16f, INK).apply { setPadding(0, dp(7), 0, dp(10)) }
        modelButton = button("导入 EmbeddingGemma 2 模型") { chooseModel() }
        permissionStatus = label("", 15f, INK)
        indexStatus = label("", 15f, INK).apply { setPadding(0, dp(7), 0, dp(10)) }
        indexButton = button("索引最近 100 张待处理照片") { indexNextBatch() }

        val setup = card(body)
        setup.addView(label("01  准备模型", 13f, MUTED))
        setup.addView(modelStatus)
        setup.addView(modelButton, LinearLayout.LayoutParams(-1, dp(46)))
        val indexing = card(body)
        indexing.addView(label("02  建立本地索引", 13f, MUTED))
        indexing.addView(permissionStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        indexing.addView(label("照片范围可在上一级设置的“相册访问”中调整。", 12f, MUTED))
        indexing.addView(indexStatus)
        indexing.addView(indexButton, LinearLayout.LayoutParams(-1, dp(46)))
        indexing.addView(label("每次处理最多 100 张；照片变化或授权范围变化后可再次更新。",
            12f, MUTED).apply { setPadding(0, dp(10), 0, 0) })

        val testing = card(body)
        testing.addView(label("03  测试本地搜索", 13f, MUTED))
        testing.addView(label("输入照片描述，查看全部已索引照片的相关度排序。",
            12f, MUTED).apply { setPadding(0, dp(7), 0, dp(10)) })
        searchInput = EditText(this).apply {
            hint = "例如：桌上的黑色杯子"
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(BG, 13, BORDER)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    searchForTesting()
                    true
                } else false
            }
        }
        testing.addView(searchInput, LinearLayout.LayoutParams(-1, dp(48)))
        searchButton = button("搜索相册") { searchForTesting() }
        testing.addView(searchButton, LinearLayout.LayoutParams(-1, dp(46)).apply {
            topMargin = dp(10)
        })
        searchStatus = label("搜索仅使用本机索引，不会上传照片。", 12f, MUTED).apply {
            setPadding(0, dp(10), 0, dp(5))
        }
        testing.addView(searchStatus)
        searchResults = ListView(this).apply {
            visibility = View.GONE
            divider = null
            isVerticalScrollBarEnabled = false
            isNestedScrollingEnabled = true
            setOnTouchListener { view, event ->
                view.parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP &&
                    event.actionMasked != MotionEvent.ACTION_CANCEL)
                false
            }
        }
        testing.addView(searchResults, LinearLayout.LayoutParams(-1, dp(384)))
    }

    private fun refreshState() {
        val model = modelFile()
        modelStatus.text = if (model == null) "尚未导入 440M 文字＋视觉模型"
            else "已导入 · ${model.length() / 1_000_000} MB"
        val allowed = hasPhotoAccess()
        permissionStatus.text = when {
            GalleryAccess.hasFullAccess(this) -> "可访问完整相册"
            GalleryAccess.hasPartialAccess(this) -> "仅检索系统已授权的照片"
            else -> "尚未授权照片"
        }
        modelButton.isEnabled = work == null
        indexButton.isEnabled = model != null && allowed && work == null
        val modelId = modelId()
        searchButton.isEnabled = modelId != null && allowed && index.count(modelId) > 0 && work == null
        searchInput.isEnabled = work == null
        indexStatus.text = if (activityNote.isNotBlank()) activityNote else
            if (modelId == null) "导入模型后可以开始索引"
            else "已索引 ${index.count(modelId)} 张照片"
    }

    private fun chooseModel() {
        if (work != null) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, PICK_MODEL)
    }

    @Deprecated("Activity result uses the system document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()
        if (!name.endsWith(".litertlm", true)) {
            modelStatus.text = "请选择 .litertlm 模型文件"
            return
        }
        launchWork("正在导入模型…") {
            val imported = withContext(Dispatchers.IO) { importModel(uri) }
            withContext(NonCancellable + Dispatchers.IO) { port?.close(); port = null }
            val nextModelId = "embeddinggemma2-440m:${imported.second}:photo-v1"
            withContext(Dispatchers.IO) { index.deleteOtherModels(nextModelId) }
            prefs.edit().putString("path", imported.first.absolutePath)
                .putString("sha256", imported.second).apply()
            searchResults.adapter = null
            searchResults.visibility = View.GONE
            searchStatus.text = "模型已导入；建立索引后可测试搜索。"
            activityNote = "模型已导入；请建立照片索引"
        }
    }

    private fun importModel(uri: Uri): Pair<File, String> {
        val folder = File(filesDir, "embedding-models")
        check(folder.isDirectory || folder.mkdirs()) { "无法创建模型目录" }
        val pending = File.createTempFile("embedding-import-", ".pending", folder)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            contentResolver.openInputStream(uri)?.use { source ->
                pending.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("无法读取模型")
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            require(sha == EXPECTED_SHA256) { "模型与 440M 文字＋视觉版本不符，请下载指定文件" }
            val destination = File(folder, "$sha.litertlm")
            if (!destination.isFile) check(pending.renameTo(destination)) { "无法保存模型" }
            return destination to sha
        } finally {
            pending.delete()
        }
    }

    private fun indexNextBatch() = launchWork("正在读取可访问照片…") {
        searchResults.adapter = null
        searchResults.visibility = View.GONE
        searchStatus.text = "索引更新后可重新测试搜索。"
        val modelId = requireNotNull(modelId())
        val visible = withContext(Dispatchers.IO) { AlbumPhotoCatalog.scan(this@AlbumIndexActivity) }
        val versions = withContext(Dispatchers.IO) { index.versions(modelId) }
        val changed = visible.filter { versions[it.id] != (it.modifiedSeconds to it.sizeBytes) }
            .take(100)
        val engine = if (changed.isEmpty()) null else requirePort(modelId)
        var added = 0
        var updated = 0
        var failed = 0
        try {
            val cleanup = withContext(Dispatchers.IO) { index.retainCurrent(modelId, visible.associate { it.id to
                (it.modifiedSeconds to it.sizeBytes) }) }
            engine?.prepare()
            changed.forEachIndexed { position, photo ->
                currentCoroutineContext().ensureActive()
                try {
                    val bitmap = withContext(Dispatchers.IO) { loadThumbnail(photo.id) }
                    try {
                        val vector = requireNotNull(engine).embedBitmap(bitmap)
                        withContext(Dispatchers.IO) { index.put(modelId, photo, vector) }
                        if (photo.id in versions) updated++ else added++
                    } finally { bitmap.recycle() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { failed++ }
                if (position % 5 == 0) {
                    activityNote = "已处理 ${position + 1}/${changed.size} 张…"
                    indexStatus.text = activityNote
                }
            }
            val indexedTotal = withContext(Dispatchers.IO) { index.count(modelId) }
            activityNote = "已索引 $indexedTotal / 可访问 ${visible.size} 张；" +
                "本次新增 $added、更新 $updated、清理已删除或失去授权 ${cleanup.missing} 张" +
                (if (failed > 0) "、失败 $failed 张" else "") +
                (if (visible.size > indexedTotal) "；仍有 ${(visible.size - indexedTotal).coerceAtLeast(0)} 张待处理" else "")
        } finally {
            refreshState()
        }
    }

    private fun searchForTesting() {
        if (work != null) return
        val query = searchInput.text.toString().trim()
        if (query.isBlank() || query.length > 200) {
            searchStatus.text = "请输入不超过 200 字的照片描述"
            return
        }
        if (!AlbumSearchService.isReady(this) || modelId()?.let(index::count) == 0L) {
            searchStatus.text = "请先导入模型、授权照片并建立索引"
            return
        }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
        searchResults.adapter = null
        searchResults.visibility = View.GONE
        searchStatus.text = "正在搜索本机索引…"
        work = scope.launch {
            try {
                val matches = AlbumSearchService(this@AlbumIndexActivity)
                    .search(query, Int.MAX_VALUE).matches
                showSearchResults(matches)
                activityNote = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                searchStatus.text = "搜索失败：${error.message ?: "请检查相册授权和模型"}"
            } finally {
                work = null
                refreshState()
            }
        }
        refreshState()
    }

    private fun showSearchResults(matches: List<AlbumSearchService.Match>) {
        searchStatus.text = if (matches.isEmpty()) "当前索引没有可显示的候选照片，请先更新索引。"
            else "共 ${matches.size} 张候选照片，按相似度从高到低排列；分数不是识别置信度。"
        searchResults.adapter = if (matches.isEmpty()) null else SearchResultsAdapter(matches)
        searchResults.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
        searchResults.layoutParams = (searchResults.layoutParams as LinearLayout.LayoutParams).apply {
            height = dp(minOf(matches.size, 4) * 96)
        }
    }

    private data class SearchRow(val image: ImageView, val title: TextView, val score: TextView)

    private inner class SearchResultsAdapter(private val matches: List<AlbumSearchService.Match>) : BaseAdapter() {
        private val thumbnails = object : LruCache<Long, Bitmap>(12 * 1024 * 1024) {
            override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
        }
        private val loading = mutableSetOf<Long>()

        override fun getCount() = matches.size
        override fun getItem(position: Int) = matches[position]
        override fun getItemId(position: Int) = matches[position].photo.id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@AlbumIndexActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                val image = ImageView(this@AlbumIndexActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(BG, 11)
                    clipToOutline = true
                }
                addView(image, LinearLayout.LayoutParams(dp(76), dp(76)))
                val texts = LinearLayout(this@AlbumIndexActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                }
                addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
                val title = label("", 14f, INK).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val score = label("", 12f, MUTED)
                texts.addView(title)
                texts.addView(score, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
                tag = SearchRow(image, title, score)
            }
            val holder = row.tag as SearchRow
            val match = matches[position]
            val id = match.photo.id
            holder.title.text = "${position + 1}. ${match.photo.name}"
            holder.score.text = "相似度 ${String.format(Locale.ROOT, "%.3f", match.score)}"
            holder.image.setImageBitmap(thumbnails.get(id))
            if (thumbnails.get(id) == null && loading.add(id)) scope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { loadThumbnail(id, 128) }.getOrNull()
                }
                loading.remove(id)
                if (bitmap != null) {
                    thumbnails.put(id, bitmap)
                    notifyDataSetChanged()
                }
            }
            val uri = ContentUris.withAppendedId(collection, id)
            row.contentDescription = "查看第 ${position + 1} 张候选照片：${match.photo.name}，${holder.score.text}"
            row.setOnClickListener { startActivity(Intent(this@AlbumIndexActivity,
                ImagePreviewActivity::class.java).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            return row
        }
    }

    private fun loadThumbnail(id: Long, maxEdge: Int = 512): Bitmap {
        val uri = ContentUris.withAppendedId(collection, id)
        if (Build.VERSION.SDK_INT >= 29) return contentResolver.loadThumbnail(uri, Size(maxEdge, maxEdge), null)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val source = contentResolver.openInputStream(uri) ?: error("无法读取照片")
        source.use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法识别照片尺寸" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("无法生成缩略图")
    }

    private fun requirePort(modelId: String): EmbeddingGemma2Port {
        port?.let { return it }
        val file = requireNotNull(modelFile()) { "请先导入模型" }
        return EmbeddingGemma2Port(this, file, modelId).also { port = it }
    }

    private fun modelFile(): File? = prefs.getString("path", null)?.let(::File)?.takeIf(File::isFile)

    private fun modelId(): String? = if (modelFile() == null) null else
        prefs.getString("sha256", null)?.let { "embeddinggemma2-440m:$it:photo-v1" }

    private fun hasPhotoAccess() = GalleryAccess.hasFullAccess(this) || GalleryAccess.hasPartialAccess(this)

    private fun launchWork(message: String, block: suspend () -> Unit) {
        if (work != null) return
        activityNote = message
        indexStatus.text = message
        work = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showMessage(error.message ?: "操作失败") }
            finally { work = null; refreshState() }
        }
        refreshState()
    }

    private fun showMessage(message: String) {
        activityNote = message
        indexStatus.text = message
    }

    private fun card(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(16))
        background = rounded(Color.WHITE, 20, BORDER)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun label(textValue: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(title: String, click: () -> Unit) = TextView(this).apply {
        text = title
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = rounded(INK, 14)
        setOnClickListener { click() }
    }

    private fun rounded(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        private const val PICK_MODEL = 3101
        private const val EXPECTED_SHA256 = "92dcbea108899e5d6e30d919b0744f90d9967e80c67a4ab5503ac16d54f62eb0"
        private const val BG = 0xFFFAFAF8.toInt()
        private const val INK = 0xFF20201F.toInt()
        private const val MUTED = 0xFF777773.toInt()
        private const val BORDER = 0xFFE5E7E3.toInt()
    }
}
