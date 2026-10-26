package dev.kiora.companion

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.face.*
import dev.kiora.face.onnx.InsightFacePort
import java.io.File
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.ByteOrder
import dev.kiora.embedding.EmbeddingVector
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in public fixtures. Storage tests use an isolated DB; UI tests read the host registry without enrolling fixtures. */
class FaceIntegrationTest {
    @Test fun previewBufferMatchesPhotoAndCompletesStableScan() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("faceScanRegression") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir,"face-validation")
        val engine = InsightFacePort(File(folder,"buffalo_sc"),FaceModelFiles.MODEL_ID)
        try {
            val bitmap = FaceImages.decode(context,Uri.fromFile(File(folder,"preview-upright.png")))
            val reference = try { engine.analyze(FaceImages.input(bitmap)).single() } finally { bitmap.recycle() }
            val buffer = File(folder,"preview-512x384.nv21").readBytes()
            val gate = FaceScanGate()
            var last: FaceObservation? = null
            for (time in listOf(0L,350L,700L)) {
                val started = System.nanoTime()
                val frame = FacePreviewFrame.decodeNv21(buffer,512,384,90)
                val faces = engine.analyze(frame)
                assertEquals(1,faces.size)
                val face = faces.single()
                assertTrue(face.qualityIssues.isEmpty())
                val cosine = face.embedding.cosineSimilarity(reference.embedding)
                assertTrue("NV21 rotation/color must preserve the face",cosine > .98)
                val state = gate.update(faces,frame.width,frame.height,time)
                if (time < 700) assertNull(state.ready) else assertNotNull(state.ready)
                last = state.ready
                instrumentation.sendStatus(0,Bundle().apply {
                    putString("stream","previewFrameMs=${(System.nanoTime()-started)/1_000_000} cosine=$cosine progress=${state.progress}\n")
                })
            }
            // Exercise the actual activity-result contract without registering or capturing anyone.
            val activity = instrumentation.startActivitySync(Intent(context,FaceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as FaceActivity
            try {
                instrumentation.runOnMainSync {
                    val mode = FaceActivity::class.java.getDeclaredField("action").apply { isAccessible = true }
                    for (value in listOf("identify","photos","enroll")) {
                        mode.set(activity,value)
                        assertEquals(if (value == "enroll") null else value,
                            activity.cameraIntent().getStringExtra(FaceCameraActivity.SCAN_MODE))
                    }
                    mode.set(activity,"identify")
                    FaceActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,Intent::class.java).apply { isAccessible = true }.invoke(activity,
                        8103,Activity.RESULT_OK,Intent().putExtra(FaceCameraActivity.SCAN_MODEL,last!!.embedding.modelId)
                            .putExtra(FaceCameraActivity.SCAN_FEATURES,last!!.embedding.values()))
                }
                val until = System.currentTimeMillis()+8000
                var complete = false
                while (!complete && System.currentTimeMillis() < until) {
                    instrumentation.runOnMainSync {
                        val status = FaceActivity::class.java.getDeclaredField("status").apply { isAccessible = true }.get(activity) as TextView
                        complete = listOf("未找到达到阈值","最接近：","多个候选相近").any { status.text.startsWith(it) }
                    }
                    if (!complete) Thread.sleep(50)
                }
                assertTrue("Scan result must reach the person query",complete)
            } finally { instrumentation.runOnMainSync { activity.finish() } }
        } finally { engine.close() }
    }

    @Test fun selectedGroupPhotoRequiresExplicitFaceChoice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("faceUiRegression") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(FaceModelFiles(context).current() != null) { "Import buffalo_sc through the UI first" }
        val activity = instrumentation.startActivitySync(Intent(context,FaceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as FaceActivity
        fun labels(view: View): List<TextView> = when(view) {
            is TextView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
            else -> emptyList()
        }
        fun waitFor(predicate: (List<TextView>) -> Boolean) {
            val until = System.currentTimeMillis()+8000
            while(System.currentTimeMillis() < until) {
                var ready = false
                instrumentation.runOnMainSync { ready = predicate(labels(activity.window.decorView)) }
                if (ready) return
                Thread.sleep(50)
            }
            fail("Face UI did not reach expected state")
        }
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                FaceActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,Intent::class.java).apply { isAccessible = true }.invoke(activity,
                    8102,Activity.RESULT_OK,Intent().setData(Uri.fromFile(File(context.cacheDir,"face-validation/t1.jpg"))))
            }
            waitFor { list -> list.any { it.text.toString() == "选择人脸 1" } }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val label = labels(activity.window.decorView).first { it.text.toString() == "选择人脸 1" }
                (label.parent as View).performClick()
            }
            waitFor { list -> list.any { it.text.toString().startsWith("未找到达到阈值") ||
                it.text.toString().startsWith("最接近：") || it.text.toString().startsWith("多个候选相近") } }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun realModelAndPrivateRegistry() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("faceRegression") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val inputs = File(context.cacheDir, "face-validation")
        val folder = File(context.cacheDir,"face-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String) = File(folder,name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?) =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath,factory,errorHandler)
        }
        var store = FaceStore(isolated)
        val engine = InsightFacePort(File(inputs,"buffalo_sc"),FaceModelFiles.MODEL_ID)
        fun report(message: String) { instrumentation.sendStatus(0,Bundle().apply { putString("stream", "$message\n") }) }
        try {
            val started = System.nanoTime()
            engine.prepare()
            val loadedMs = (System.nanoTime()-started)/1_000_000
            val bitmap = FaceImages.decode(context, Uri.fromFile(File(inputs,"t1.jpg")))
            val begin = System.nanoTime()
            val faces = try { engine.analyze(FaceImages.input(bitmap)) } finally { bitmap.recycle() }
            report("loadMs=$loadedMs analyzeMs=${(System.nanoTime()-begin)/1_000_000} faces=${faces.size} quality=${faces.map { it.qualityIssues }}")
            assertTrue("Expected multiple faces in public fixture", faces.size >= 2)
            val reference = File(inputs,"reference-vectors.bin")
            if (reference.isFile) {
                val buffer = ByteBuffer.wrap(reference.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val scores = faces.map { observation ->
                    val values = FloatArray(512); buffer.get(values)
                    observation.embedding.cosineSimilarity(EmbeddingVector(engine.modelId,values))
                }
                report("opencvReferenceCosines=$scores")
                assertTrue("Android preprocessing must match reference",scores.all { it > .97 })
            }
            val usable = faces.filter { it.qualityIssues.isEmpty() }
            assertTrue("Expected two usable samples", usable.size >= 2)
            val registry = FaceRegistry(store, engine.modelId)
            val a = registry.enroll("fixture-A",listOf(usable[0]))
            val b = registry.enroll("fixture-B",listOf(usable[1]))
            assertEquals(a.id,registry.search(usable[0].embedding,FaceActivity.POLICY).matchedPersonId)
            report("differentFaceSimilarity=${usable[0].embedding.cosineSimilarity(usable[1].embedding)}")
            store.close(); store = FaceStore(isolated)
            assertEquals(2,store.people().size)
            assertEquals(1,store.templates(a.id,engine.modelId).size)
            val photo = AlbumIndexStore.Photo(1001,"fixture",7,9)
            store.putPhoto(engine.modelId,photo,usable,1600,1600)
            store.putPhoto(engine.modelId,AlbumIndexStore.Photo(1002,"empty",7,9),emptyList(),1600,1600)
            assertEquals(2L,store.counts(engine.modelId).first)
            assertEquals(1,store.searchPhotos(engine.modelId,listOf(usable[0].embedding),.99).size)
            store.retainCurrent(engine.modelId,mapOf(1001L to (8L to 9L)))
            assertEquals(0L,store.counts(engine.modelId).first)
            assertEquals(0L,store.counts(engine.modelId).second)
            store.delete(a.id)
            assertTrue(store.templates(a.id,engine.modelId).isEmpty())
            assertEquals(b.id,store.people().single().id)
            val black = Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            try { assertTrue(engine.analyze(FaceImages.input(black)).isEmpty()) } finally { black.recycle() }
            val faceInput = FaceImages.decode(context,Uri.fromFile(File(inputs,"t1.jpg")))
            try {
                val input = FaceImages.input(faceInput)
                val inference = launch(Dispatchers.Default) { engine.analyze(input) }
                delay(10); inference.cancelAndJoin(); engine.close()
            } finally { faceInput.recycle() }
            try { engine.prepare(); fail("Closed engine must not reopen") } catch (_: IllegalStateException) { }
            report("PASS: inference, empty image, persistent enrollment, matching, multi-face index, revocation cleanup, deletion, cancellation/close")
        } finally { engine.close(); store.close(); folder.deleteRecursively() }
    }
}
