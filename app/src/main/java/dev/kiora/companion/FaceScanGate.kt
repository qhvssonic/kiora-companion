package dev.kiora.companion

import dev.kiora.face.FaceBox
import dev.kiora.face.FaceObservation
import kotlin.math.max
import kotlin.math.min

/** Temporal capture selection only; this does not establish identity or prove liveness. */
internal class FaceScanGate {
    data class State(val message: String, val progress: Float = 0f, val ready: FaceObservation? = null)
    private var first: FaceObservation? = null
    private var previous: FaceBox? = null
    private var started = 0L
    private var lastTime = 0L
    private var count = 0

    fun update(faces: List<FaceObservation>, width: Int, height: Int, timeMs: Long): State {
        require(width > 0 && height > 0)
        if (faces.isEmpty()) return reset("请将人脸放入框内")
        if (faces.size != 1) return reset("请保持画面中只有一张人脸")
        val face = faces.single()
        if (face.qualityIssues.isNotEmpty()) return reset("请靠近一些，保持光线充足并正对镜头")
        val box = face.face.box
        val x = (box.left + box.right) / (2f * width)
        val y = (box.top + box.bottom) / (2f * height)
        if (x !in .25f.. .75f || y !in .25f.. .75f || box.width / width < .22f)
            return reset("请靠近并将人脸移到框中央")
        val reference = first
        val continuous = reference != null && timeMs > lastTime && timeMs-lastTime <= 1500 &&
            reference.embedding.modelId == face.embedding.modelId &&
            reference.embedding.dimension == face.embedding.dimension &&
            reference.embedding.cosineSimilarity(face.embedding) >= .65 &&
            previous?.let { overlap(it, box) >= .65f } == true
        if (!continuous) { first = face; started = timeMs; count = 0 }
        previous = box; lastTime = timeMs; count++
        val elapsed = timeMs-started
        if (count >= 3 && elapsed >= 700) return State("扫描完成，正在查询…", 1f, face)
        return State("已找到人脸，请保持不动", min(count/3f, elapsed/700f).coerceIn(0f, 1f))
    }

    private fun reset(message: String): State {
        first = null; previous = null; count = 0
        return State(message)
    }
    private fun overlap(a: FaceBox, b: FaceBox): Float {
        val intersection = max(0f, min(a.right,b.right)-max(a.left,b.left)) *
            max(0f, min(a.bottom,b.bottom)-max(a.top,b.top))
        return intersection/(a.width*a.height+b.width*b.height-intersection)
    }
}
