package dev.kiora.companion

import dev.kiora.embedding.EmbeddingVector
import dev.kiora.face.*
import org.junit.Assert.*
import org.junit.Test

class FaceScanTest {
    private fun face(identity: Int = 0, box: FaceBox = FaceBox(30f,30f,70f,70f),
                     quality: Set<FaceQualityIssue> = emptySet()) = FaceObservation(
        DetectedFace(box, List(5) { FacePoint(50f,50f) }, .99f),
        EmbeddingVector("fixture", FloatArray(512).apply { this[identity] = 1f }), quality)
    private fun update(gate: FaceScanGate, time: Long, vararg faces: FaceObservation) =
        gate.update(faces.toList(),100,100,time)

    @Test fun requiresThreeConsistentFramesAndMinimumDuration() {
        val gate = FaceScanGate(); val f = face()
        assertNull(update(gate,0,f).ready)
        assertNull(update(gate,100,f).ready)
        assertNull(update(gate,200,f).ready)
        assertSame(f,update(gate,700,f).ready)
    }
    @Test fun differentPersonRestartsEvenAtSamePosition() {
        val gate = FaceScanGate()
        update(gate,0,face()); update(gate,350,face())
        assertNull(update(gate,700,face(1)).ready)
        assertNull(update(gate,1050,face(1)).ready)
        assertNotNull(update(gate,1400,face(1)).ready)
    }
    @Test fun missingMultipleOrPoorFacesResetProgress() {
        val interruptions = listOf(emptyArray(), arrayOf(face(),face(1)),
            arrayOf(face(quality=setOf(FaceQualityIssue.BLURRED))),
            arrayOf(face(box=FaceBox(1f,1f,25f,25f))),
            arrayOf(face(box=FaceBox(45f,45f,55f,55f))))
        for (interruption in interruptions) {
            val gate = FaceScanGate()
            update(gate,0,face()); update(gate,350,face())
            assertNull(update(gate,700,*interruption).ready)
            assertNull(update(gate,1050,face()).ready)
            assertNull(update(gate,1400,face()).ready)
            assertNotNull(update(gate,1750,face()).ready)
        }
    }
    @Test fun staleFramesAndLargeMovementRestart() {
        val gate = FaceScanGate()
        update(gate,0,face()); update(gate,350,face())
        assertNull(update(gate,2000,face()).ready)
        val moved = face(box=FaceBox(40f,30f,80f,70f))
        assertNull(update(gate,2350,moved).ready)
        assertNull(update(gate,2700,moved).ready)
        assertNotNull(update(gate,3050,moved).ready)
    }
    @Test fun nv21UsesVuOrderAndLimitedRange() {
        val red = byteArrayOf(82,82,82,82,240.toByte(),90)
        val rgb = FacePreviewFrame.decodeNv21(red,2,2,0).pixels()[0]
        assertTrue(((rgb shr 16) and 255) >= 250)
        assertTrue(((rgb shr 8) and 255) <= 3)
        assertTrue((rgb and 255) <= 3)
        val white = ByteArray(6) { if (it < 4) 235.toByte() else 128.toByte() }
        assertEquals(-1,FacePreviewFrame.decodeNv21(white,2,2,0).pixels()[0])
    }
    @Test fun nv21RotationsPreservePixelPositionsWithoutMirroring() {
        val bytes = byteArrayOf(16,40,70,100,130.toByte(),160.toByte(),190.toByte(),220.toByte(),
            128.toByte(),128.toByte(),128.toByte(),128.toByte())
        val original = FacePreviewFrame.decodeNv21(bytes,4,2,0).pixels()
        val expected = mapOf(90 to intArrayOf(4,0,5,1,6,2,7,3),
            180 to intArrayOf(7,6,5,4,3,2,1,0),270 to intArrayOf(3,7,2,6,1,5,0,4))
        for ((rotation,order) in expected) {
            val rotated = FacePreviewFrame.decodeNv21(bytes,4,2,rotation)
            assertEquals(if (rotation == 180) 4 else 2,rotated.width)
            assertEquals(if (rotation == 180) 2 else 4,rotated.height)
            assertArrayEquals(order.map { original[it] }.toIntArray(),rotated.pixels())
        }
    }
    @Test fun rejectsTruncatedFrames() {
        assertThrows(IllegalArgumentException::class.java) { FacePreviewFrame.decodeNv21(ByteArray(5),2,2,0) }
        assertThrows(IllegalArgumentException::class.java) { FacePreviewFrame.decodeNv21(ByteArray(6),2,2,45) }
    }
}
