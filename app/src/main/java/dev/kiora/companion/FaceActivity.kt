package dev.kiora.companion

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import dev.kiora.embedding.EmbeddingVector
import dev.kiora.face.*
import dev.kiora.face.onnx.InsightFacePort
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Manual, local face workflows. No biometric identifiers enter chat history or agent tools. */
class FaceActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store by lazy { FaceStore(this) }
    private val models by lazy { FaceModelFiles(this) }
    private var work: Job? = null
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var modelValue: TextView
    private lateinit var indexValue: TextView
    private lateinit var progress: ProgressBar
    private lateinit var peopleList: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var cancel: TextView
    private val controls = mutableListOf<View>()
    private var action = "identify"
    private var personId: String? = null
    private var personName = ""
    private var matches = emptyList<FaceStore.PhotoMatch>()
    private var shown = 0
    private var resultGeneration = 0
    private var selectionBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        action = savedInstanceState?.getString("action") ?: "identify"
        personId = savedInstanceState?.getString("person_id")
        personName = savedInstanceState?.getString("person_name").orEmpty()
        window.statusBarColor = BG; window.navigationBarColor = BG
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(62)))
        header.addView(label("‹", 35f).apply {
            gravity = Gravity.CENTER; setOnClickListener { finish() }
            contentDescription = "返回"
        }, LinearLayout.LayoutParams(dp(38), -1))
        header.addView(label("本地人脸", 20f, bold = true).apply { setPadding(dp(6), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("⋯", 26f, MUTED).apply {
            gravity = Gravity.CENTER; contentDescription = "人脸数据管理"
            setOnClickListener { dataMenu() }; controls += this
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val scroll = ScrollView(this)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(32)) }
        scroll.addView(body)
        body.addView(label("识别已注册人员，查找相册中的相关照片。", 14f, MUTED))
        summary = label("", 12f, MUTED).apply { setPadding(0, dp(6), 0, dp(20)) }; body.addView(summary)
        val actions = card(body)
        actionRow(actions, "识别人员", "扫描人脸或选图，与已注册人员比较") { action = "identify"; chooseSource() }
        divider(actions)
        actionRow(actions, "查找相册照片", "扫描人脸或选图，查找同一个人的照片") { action = "photos"; chooseSource() }

        val activityRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        body.addView(activityRow, LinearLayout.LayoutParams(-1, -2))
        progress = ProgressBar(this).apply { visibility = View.GONE; indeterminateTintList = android.content.res.ColorStateList.valueOf(INK) }
        activityRow.addView(progress, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(10) })
        status = label("", 13f, MUTED).apply {
            visibility = View.GONE
            setPadding(0, dp(12), 0, dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        activityRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        cancel = label("停止", 13f, bold = true).apply {
            visibility = View.GONE; gravity = Gravity.CENTER; background = rounded(BG, 12, BORDER)
            setOnClickListener { work?.cancel() }
        }
        activityRow.addView(cancel, LinearLayout.LayoutParams(dp(60), dp(44)).apply { leftMargin = dp(10) })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; body.addView(results)

        val peopleHeader = section(body, "已注册人员")
        peopleHeader.addView(label("注册", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = rounded(INK, 17)
            contentDescription = "注册人员"; setOnClickListener { enrollName() }; controls += this
        }, LinearLayout.LayoutParams(dp(68), dp(36)))
        peopleList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; body.addView(peopleList)
        section(body, "模型与相册")
        val setup = card(body)
        modelValue = actionRow(setup, "人脸模型", "") { confirmImport() }
        divider(setup)
        indexValue = actionRow(setup, "相册人脸索引", "") { requestIndex() }
        body.addView(label("首次查找前更新索引，仅处理已授权照片。", 12f, MUTED).apply { setPadding(0, dp(10), 0, dp(16)) })
        body.addView(label("仅注册自己或已获同意的人员。特征保存在本机，可随时删除；照片不会上传。", 12f, MUTED))
        setContentView(root)
        scope.launch { refresh() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("action", action); outState.putString("person_id", personId); outState.putString("person_name", personName)
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if (::summary.isInitialized && work?.isActive != true) {
            clearResults()
            scope.launch {
                if (!hasPhotos()) withContext(Dispatchers.IO) { store.clearIndex() }
                refresh()
            }
        }
    }
    override fun onDestroy() {
        val running = scope.coroutineContext[Job]
        scope.cancel()
        selectionBitmap?.recycle(); selectionBitmap = null
        CoroutineScope(Dispatchers.IO).launch { running?.join(); store.close() }
        super.onDestroy()
    }
    private suspend fun refresh() {
        val model = models.current()
        val data = withContext(Dispatchers.IO) {
            val people = store.people()
            Triple(people, people.associate { it.id to store.templates(it.id, FaceModelFiles.MODEL_ID).size }, store.counts(FaceModelFiles.MODEL_ID))
        }
        summary.text = "本机处理 · ${data.first.size} 位人员 · ${data.third.first} 张照片已索引"
        modelValue.text = if (model == null) "尚未导入 · 点按选择模型目录" else "已准备好 · 离线可用"
        indexValue.text = "${data.third.first} 张照片 · ${data.third.second} 张人脸 · 点按更新"
        peopleList.removeAllViews()
        if (data.first.isEmpty()) peopleList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(PALE, 18)
            setPadding(dp(16), dp(18), dp(16), dp(18))
            addView(label("还没有注册人员", 15f, bold = true))
            addView(label("点按“注册”添加自己，之后可补充不同角度的样本。", 12f, MUTED).apply { setPadding(0, dp(6), 0, 0) })
        }, LinearLayout.LayoutParams(-1, -2))
        for (person in data.first) {
            val personCard = card(peopleList)
            actionRow(personCard, person.name, "${data.second[person.id]} 个样本 · 查看或管理", trackControl = false) {
                if (work?.isActive != true) personMenu(person)
            }
        }
    }
    private fun perform(block: suspend () -> Unit) {
        if (work?.isActive == true) return
        controls.forEach { it.isEnabled = false; it.alpha = .45f }
        cancel.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        work = scope.launch {
            try { operationMutex.withLock { block() } }
            catch (cancelled: CancellationException) { status.text = "已停止，已完成的索引已保存"; throw cancelled }
            catch (error: Exception) { status.text = "未完成：${error.message ?: "请重试"}" }
            finally {
                controls.forEach { it.isEnabled = true; it.alpha = 1f }
                cancel.visibility = View.GONE
                progress.visibility = View.GONE
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (scope.isActive) scope.launch { refresh() }
            }
        }
    }
    private suspend fun <T> withEngine(block: suspend (InsightFacePort) -> T): T {
        val model = models.current() ?: error("请先导入人脸模型")
        val engine = InsightFacePort(model.directory, model.id)
        try { return block(engine) } finally { withContext(NonCancellable) { engine.close() } }
    }
    private fun confirmImport() {
        SettingsSheet.showConfirmation(this, "导入人脸模型",
            "选择解压后的 buffalo_sc 文件夹，包含 det_500m.onnx 和 w600k_mbf.onnx。导入后可离线使用。",
            "选择目录", "本地人脸") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), IMPORT)
        }
    }
    private fun enrollName() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form.addView(label("姓名或昵称", 12f, MUTED))
        val input = EditText(this).apply {
            hint = "输入名称"; setSingleLine(true); textSize = 15f
            setTextColor(INK); setHintTextColor(MUTED)
            setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(Color.WHITE, 14, BORDER)
            filters = arrayOf(android.text.InputFilter.LengthFilter(80))
        }
        form.addView(input, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        form.addView(label("请先征得本人同意。采集一张清晰正面照后，可继续添加不同角度的样本。", 13f, MUTED).apply {
            setPadding(0, dp(12), 0, dp(8))
        })
        SettingsSheet.showForm(this, "注册人员", "本地人脸", form, actionLabel = "下一步") {
            val name = input.text.toString().trim()
            if (name.isEmpty()) { input.error = "请输入名称"; false }
            else {
                personId = null; personName = name; action = "enroll"
                body.post { chooseSource() }
                true
            }
        }
    }
    private fun personMenu(person: RegisteredPerson) {
        SettingsSheet.showOptions(this, person.name, "已注册人员", listOf(
            SettingsSheet.Option("添加人脸样本", "补充不同角度或光线下的照片"),
            SettingsSheet.Option("查找相册照片", "从已授权、已索引的照片中查找"),
            SettingsSheet.Option("删除人员", "删除此人的全部注册特征", destructive = true),
        )) { which -> when(which) {
            0 -> { personId = person.id; personName = person.name; action = "enroll"; chooseSource() }
            1 -> perform {
                val queries = withContext(Dispatchers.IO) { store.templates(person.id, FaceModelFiles.MODEL_ID) }
                check(queries.isNotEmpty()) { "当前模型下没有此人的样本，请重新采集" }
                searchPhotos(queries)
            }
            2 -> confirm("删除「${person.name}」？", "将删除此人的所有注册特征；相册索引可单独清空。", "删除人员") {
                perform { withContext(Dispatchers.IO) { store.delete(person.id) }; clearResults(); status.text = "已删除人员" }
            }
        } }
    }
    private fun chooseSource() {
        if (models.current() == null) { status.text = "请先导入人脸模型"; return }
        val scanning = action != "enroll"
        SettingsSheet.showOptions(this, if (scanning) "选择查询方式" else "采集人脸", "本地人脸", listOf(
            SettingsSheet.Option(if (scanning) "实时扫描人脸" else "打开前置摄像头",
                if (scanning) "无需拍照，对准人脸后自动查询" else "拍摄一张清晰的正面照片"),
            SettingsSheet.Option("选择一张照片", "从手机中选择参考照片"),
        )) { which ->
            if (which == 0) {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
                else requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
            } else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }, PHOTO)
        }
    }
    private fun openCamera() {
        startActivityForResult(cameraIntent(), CAMERA)
    }
    internal fun cameraIntent(): Intent {
        val mode = action
        return Intent(this, FaceCameraActivity::class.java).apply {
            if (mode == "identify" || mode == "photos") putExtra(FaceCameraActivity.SCAN_MODE, mode)
        }
    }
    private fun requestIndex() {
        if (!hasPhotos()) requestPermissions(GalleryAccess.permissionsToRequest(), GALLERY_PERMISSION)
        else indexPhotos()
    }
    private fun hasPhotos() = GalleryAccess.hasFullAccess(this) || GalleryAccess.hasPartialAccess(this)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == CAMERA_PERMISSION) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera() else status.text = "未授权相机，可改为选择照片"
        }
        if (requestCode == GALLERY_PERMISSION) {
            if (hasPhotos()) indexPhotos() else status.text = "需要照片访问权限才能建立相册索引"
        }
    }
    @Deprecated("Uses platform pickers")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == IMPORT) {
            val uri = data?.data ?: return
            perform { status.text = "正在校验并导入模型…"; withContext(Dispatchers.IO) { models.importTree(uri) }; status.text = "模型已准备好，可离线使用" }
        }
        if (requestCode == PHOTO) data?.data?.let { analyze(it, null) }
        if (requestCode == CAMERA) {
            if (data?.hasExtra(FaceCameraActivity.SCAN_FEATURES) == true) {
                perform {
                    clearResults()
                    check(action == "identify" || action == "photos") { "扫描结果仅用于查询" }
                    val modelId = data.getStringExtra(FaceCameraActivity.SCAN_MODEL)
                    check(modelId != null && modelId == models.current()?.id) { "人脸模型已变更，请重新扫描" }
                    val values = data.getFloatArrayExtra(FaceCameraActivity.SCAN_FEATURES)
                    check(values != null && values.size == 512 && values.all { it.isFinite() } && values.any { it != 0f }) {
                        "扫描结果无效，请重新扫描"
                    }
                    queryFace(EmbeddingVector(modelId, values))
                }
                return
            }
            val name = data?.getStringExtra("capture_name")?.takeIf { it.matches(Regex("face-[a-f0-9-]{36}\\.jpg")) } ?: return
            val file = File(cacheDir, "captures/$name")
            analyze(Uri.fromFile(file), file)
        }
    }
    private fun analyze(uri: Uri, temporary: File?) = perform {
        clearResults(); status.text = "正在分析人脸…"
        var bitmap: Bitmap? = null
        try {
            val loaded = withContext(Dispatchers.IO) { FaceImages.decode(this@FaceActivity, uri) }; bitmap = loaded
            val observations = withEngine { engine -> engine.analyze(withContext(Dispatchers.Default) { FaceImages.input(loaded) }) }
            check(observations.isNotEmpty()) { "没有找到人脸，请选择更清晰、正面的照片" }
            if (action == "enroll") {
                check(observations.size == 1) { "注册照片只能包含一张人脸，请重新拍摄或选择单人照片" }
                useFace(observations.single())
            } else if (observations.size == 1) useFace(observations.single())
            else {
                selectionBitmap = loaded; bitmap = null
                status.text = "检测到 ${observations.size} 张人脸，请选择要查找的人"
                observations.forEachIndexed { i, observation ->
                    val row = resultRow()
                    val box = observation.face.box
                    row.addView(ImageView(this@FaceActivity).apply {
                        setImageBitmap(crop(loaded, box)); scaleType = ImageView.ScaleType.FIT_CENTER
                    }, LinearLayout.LayoutParams(dp(72), dp(72)))
                    row.addView(label("选择人脸 ${i+1}", 15f, bold = true).apply { setPadding(dp(14), 0, 0, 0) },
                        LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(label("›", 22f, MUTED))
                    row.setOnClickListener { if (work?.isActive != true) perform { clearResults(); useFace(observation) } }
                    results.addView(row, resultLayout())
                }
            }
        } finally { bitmap?.recycle(); temporary?.delete() }
    }
    private suspend fun useFace(observation: FaceObservation) {
        check(observation.qualityIssues.isEmpty()) { "人脸过小、模糊或角度过大，请靠近镜头并重新采集" }
        when(action) {
            "enroll" -> {
                val possible = withContext(Dispatchers.IO) { FaceRegistry(store, observation.embedding.modelId).search(observation.embedding, POLICY) }
                val other = possible.candidates.firstOrNull()?.takeIf { it.personId != personId && it.similarity >= POLICY.minimumSimilarity }
                if (other != null) {
                    val name = withContext(Dispatchers.IO) { store.people().firstOrNull { it.id == other.personId }?.name }.orEmpty()
                    status.text = "发现相似的已注册人员，请核对"
                    confirm("可能与「$name」重复", "请核对是否为同一个人。仍将此样本保存到「$personName」？", "仍然保存") {
                        perform { saveSample(observation) }
                    }
                } else saveSample(observation)
            }
            else -> queryFace(observation.embedding)
        }
    }
    private suspend fun queryFace(embedding: EmbeddingVector) {
        when(action) {
            "identify" -> {
                val pair = withContext(Dispatchers.IO) {
                    FaceRegistry(store, embedding.modelId).search(embedding, POLICY) to store.people().associate { it.id to it.name }
                }
                status.text = when(pair.first.status) {
                    FaceMatchStatus.MATCH -> "最接近：${pair.second[pair.first.matchedPersonId]}（候选，请核对）"
                    FaceMatchStatus.UNKNOWN -> "未找到达到阈值的注册人员"
                    FaceMatchStatus.AMBIGUOUS -> "多个候选相近，无法确定"
                }
                results.addView(label("相似度不是身份概率；当前阈值尚未经过人群规模校准。", 13f))
                pair.first.candidates.forEach { candidate ->
                    val row = resultRow()
                    row.addView(label(pair.second[candidate.personId].orEmpty(), 15f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(label("相似度 ${score(candidate.similarity)}", 12f, MUTED))
                    results.addView(row, resultLayout())
                }
            }
            "photos" -> searchPhotos(listOf(embedding))
        }
    }
    private suspend fun saveSample(observation: FaceObservation) {
        val person = withContext(Dispatchers.IO) { FaceRegistry(store, observation.embedding.modelId).enroll(personName, listOf(observation), personId) }
        status.text = "已保存「${person.name}」的人脸样本，可在人员列表中添加其他角度"
    }
    private fun indexPhotos() = perform {
        clearResults(); status.text = "正在读取已授权照片…"
        withEngine { engine ->
            val photos = withContext(Dispatchers.IO) { AlbumPhotoCatalog.scan(this@FaceActivity) }
            val versions = withContext(Dispatchers.IO) {
                store.retainCurrent(engine.modelId, photos.associate { it.id to (it.modifiedSeconds to it.sizeBytes) })
                store.versions(engine.modelId)
            }
            val pending = photos.filter { versions[it.id] != (it.modifiedSeconds to it.sizeBytes) }
            var failed = 0; var completed = 0
            for (photo in pending) {
                currentCoroutineContext().ensureActive()
                check(hasPhotos()) { "相册权限已撤销" }
                status.text = "正在索引 ${completed+1}/${pending.size} · 可随时停止"
                try {
                    val bitmap = withContext(Dispatchers.IO) { FaceImages.decode(this@FaceActivity, photoUri(photo.id)) }
                    try {
                        val faces = engine.analyze(withContext(Dispatchers.Default) { FaceImages.input(bitmap) })
                        currentCoroutineContext().ensureActive()
                        withContext(Dispatchers.IO) { store.putPhoto(engine.modelId, photo, faces, bitmap.width, bitmap.height) }
                    } finally { bitmap.recycle() }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed++ }
                completed++
            }
            // Permissions/photos may change during a long run. Remove stale results before reporting completion.
            withContext(Dispatchers.IO) {
                val current = AlbumPhotoCatalog.scan(this@FaceActivity)
                store.retainCurrent(engine.modelId, current.associate { it.id to (it.modifiedSeconds to it.sizeBytes) })
            }
            status.text = "索引完成：新增或更新 ${completed-failed} 张，${failed} 张未处理${if (failed > 0) "，下次可重试" else ""}"
        }
    }
    private suspend fun searchPhotos(queries: List<EmbeddingVector>) {
        check(hasPhotos()) { "请先授权照片，并更新相册人脸索引" }
        clearResults(); status.text = "正在查找已索引照片…"
        matches = withContext(Dispatchers.IO) {
            val visible = AlbumPhotoCatalog.scan(this@FaceActivity)
            store.retainCurrent(FaceModelFiles.MODEL_ID, visible.associate { it.id to (it.modifiedSeconds to it.sizeBytes) })
            val context = currentCoroutineContext()
            store.searchPhotos(FaceModelFiles.MODEL_ID, queries, POLICY.minimumSimilarity) { context.ensureActive() }
        }
        status.text = if (matches.isEmpty()) "没有找到相似照片；可先更新索引或更换清晰的参考照片"
            else "找到 ${matches.size} 张候选照片，请核对框选人脸"
        showMore()
    }
    private fun showMore() {
        val generation = resultGeneration
        val batch = matches.drop(shown).take(20)
        for (match in batch) {
            val row = resultRow()
            val image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "候选照片中的人脸框" }
            row.addView(image, LinearLayout.LayoutParams(dp(104), dp(96)))
            row.addView(label("${match.name}\n相似度 ${score(match.similarity)}", 14f).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener {
                if (hasPhotos()) startActivity(Intent(this, ImagePreviewActivity::class.java).setData(photoUri(match.id)))
                else { clearResults(); status.text = "照片权限已撤销" }
            }
            results.addView(row, resultLayout())
            scope.launch {
                val thumbnail = withContext(Dispatchers.IO) {
                    runCatching {
                        val source = FaceImages.decode(this@FaceActivity, photoUri(match.id), 320)
                        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true); source.recycle()
                        Canvas(bitmap).drawRect(match.box.left*bitmap.width, match.box.top*bitmap.height,
                            match.box.right*bitmap.width, match.box.bottom*bitmap.height,
                            Paint().apply { color = 0xFF238353.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f })
                        bitmap
                    }.getOrNull()
                }
                if (generation == resultGeneration) image.setImageBitmap(thumbnail) else thumbnail?.recycle()
            }
        }
        shown += batch.size
        if (shown < matches.size) results.addView(label("显示更多（$shown/${matches.size}）", 16f).apply {
            gravity = Gravity.CENTER; minimumHeight = dp(48); background = rounded(PALE, 14)
            setOnClickListener { results.removeView(this); showMore() }
        })
    }
    private fun clearResults() {
        resultGeneration++; results.removeAllViews(); matches = emptyList(); shown = 0
        selectionBitmap?.recycle(); selectionBitmap = null
    }
    private fun crop(bitmap: Bitmap, box: FaceBox): Bitmap {
        val x = box.left.toInt().coerceIn(0, bitmap.width-1); val y = box.top.toInt().coerceIn(0, bitmap.height-1)
        val cut = Bitmap.createBitmap(bitmap, x, y, box.width.toInt().coerceIn(1, bitmap.width-x), box.height.toInt().coerceIn(1, bitmap.height-y))
        val resized = Bitmap.createScaledBitmap(cut, 144, 144, true)
        if (cut !== bitmap && cut !== resized) cut.recycle()
        return resized
    }
    private fun photoUri(id: Long): Uri = ContentUris.withAppendedId(if(Build.VERSION.SDK_INT >= 29)
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
    private fun dataMenu() {
        SettingsSheet.showOptions(this, "数据管理", "本地人脸", listOf(
            SettingsSheet.Option("清空相册人脸索引", "保留已注册人员和手机原照片", destructive = true),
            SettingsSheet.Option("删除全部本地人脸数据", "删除人员特征和相册人脸索引", destructive = true),
        )) { which ->
            if (which == 0) confirm("清空相册人脸索引？", "保留已注册人员和手机原照片，以后可重新建立索引。", "清空索引") {
                perform { withContext(Dispatchers.IO) { store.clearIndex() }; clearResults(); status.text = "已清空相册人脸索引" }
            } else confirm("删除全部人脸数据？", "删除所有人员特征和相册人脸索引，无法撤销。手机原照片不受影响。", "全部删除") {
                perform { withContext(Dispatchers.IO) { store.clearAll() }; clearResults(); status.text = "已删除本地人脸数据" }
            }
        }
    }
    private fun section(parent: LinearLayout, title: String): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16); bottomMargin = dp(8) })
        row.addView(label(title, 18f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }
    private fun card(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(Color.WHITE, 18, BORDER)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    private fun actionRow(parent: LinearLayout, title: String, description: String,
                          trackControl: Boolean = true, onClick: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(72)
            setPadding(dp(16), dp(14), dp(14), dp(14))
            setOnClickListener { onClick() }; if (trackControl) controls += this
        }
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        names.addView(label(title, 15f, bold = true))
        val detail = label(description, 12f, MUTED).apply { setPadding(0, dp(5), 0, 0) }
        names.addView(detail)
        row.addView(label("›", 22f, MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(28), dp(32)))
        return detail
    }
    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(BORDER) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { leftMargin = dp(16); rightMargin = dp(16) })
    }
    private fun resultRow() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56)
        background = rounded(Color.WHITE, 15, BORDER); setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private fun resultLayout() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); stroke?.let { setStroke(dp(1), it) }
    }
    private fun label(title: String, size: Float, color: Int = INK, bold: Boolean = false) = TextView(this).apply {
        text = title; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun confirm(title: String, message: String, actionLabel: String, block: () -> Unit) {
        SettingsSheet.showConfirmation(this, title, message, actionLabel, "本地人脸", block)
    }
    private fun score(value: Double) = String.format(Locale.ROOT, "%.3f", value)
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()
    companion object {
        private val operationMutex = Mutex()
        private const val BG = 0xFFFAFAF8.toInt()
        private const val INK = 0xFF20201F.toInt()
        private const val MUTED = 0xFF777773.toInt()
        private const val PALE = 0xFFF1F3F0.toInt()
        private const val BORDER = 0xFFE5E7E3.toInt()
        private const val IMPORT = 8101; private const val PHOTO = 8102; private const val CAMERA = 8103
        private const val CAMERA_PERMISSION = 8104; private const val GALLERY_PERMISSION = 8105
        // Provisional demo settings, not a calibrated authentication threshold.
        internal val POLICY = FaceMatchPolicy(.5, .08)
    }
}
