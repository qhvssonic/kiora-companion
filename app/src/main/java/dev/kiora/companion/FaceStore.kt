package dev.kiora.companion

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.kiora.embedding.EmbeddingVector
import dev.kiora.face.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** App-private biometric templates, without original enrollment photos or external transmissions. */
internal class FaceStore(context: Context) : SQLiteOpenHelper(context, "faces.db", null, 1), FaceRegistryStore {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE people (id TEXT PRIMARY KEY, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE templates (person_id TEXT NOT NULL REFERENCES people(id) ON DELETE CASCADE, model_id TEXT NOT NULL, vector BLOB NOT NULL)")
        db.execSQL("CREATE INDEX template_person ON templates(person_id,model_id)")
        db.execSQL("CREATE TABLE photos (id INTEGER PRIMARY KEY, model_id TEXT NOT NULL, modified INTEGER NOT NULL, size INTEGER NOT NULL, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE faces (photo_id INTEGER NOT NULL REFERENCES photos(id) ON DELETE CASCADE, vector BLOB NOT NULL, left_x REAL, top_y REAL, right_x REAL, bottom_y REAL)")
        db.execSQL("CREATE INDEX face_photo ON faces(photo_id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    override fun people(): List<RegisteredPerson> = readableDatabase.rawQuery("SELECT id,name FROM people ORDER BY name,id", null).use { c ->
        buildList { while (c.moveToNext()) add(RegisteredPerson(c.getString(0), c.getString(1))) }
    }
    override fun templates(personId: String, modelId: String): List<EmbeddingVector> = readableDatabase.rawQuery(
        "SELECT vector FROM templates WHERE person_id=? AND model_id=?", arrayOf(personId, modelId)).use { c ->
        buildList { while(c.moveToNext()) add(decode(modelId, c.getBlob(0))) }
    }
    override fun replace(person: RegisteredPerson, modelId: String, templates: List<EmbeddingVector>) {
        require(templates.size in 1..12)
        templates.forEach { require(it.modelId == modelId && it.dimension == 512) }
        transaction { db ->
            db.insertWithOnConflict("people", null, ContentValues().apply {
                put("id", person.id); put("name", person.name)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.update("people", ContentValues().apply { put("name", person.name) }, "id=?", arrayOf(person.id))
            db.delete("templates", "person_id=? AND model_id=?", arrayOf(person.id, modelId))
            templates.forEach { vector -> db.insertOrThrow("templates", null, ContentValues().apply {
                put("person_id", person.id); put("model_id", modelId); put("vector", encode(vector))
            }) }
        }
    }
    override fun delete(personId: String) { writableDatabase.delete("people", "id=?", arrayOf(personId)) }

    fun versions(modelId: String): Map<Long, Pair<Long, Long>> = readableDatabase.rawQuery(
        "SELECT id,modified,size FROM photos WHERE model_id=?", arrayOf(modelId)).use { c ->
        buildMap { while(c.moveToNext()) put(c.getLong(0), c.getLong(1) to c.getLong(2)) }
    }
    fun retainCurrent(modelId: String, visible: Map<Long, Pair<Long, Long>>) {
        transaction { db ->
            db.delete("photos", "model_id<>?", arrayOf(modelId))
            versions(modelId).forEach { (id, version) ->
                if (visible[id] != version) db.delete("photos", "id=?", arrayOf(id.toString()))
            }
        }
    }
    fun putPhoto(modelId: String, photo: AlbumIndexStore.Photo, observations: List<FaceObservation>, width: Int, height: Int) {
        observations.forEach { require(it.embedding.modelId == modelId) }
        transaction { db ->
            db.delete("photos", "id=?", arrayOf(photo.id.toString()))
            db.insertOrThrow("photos", null, ContentValues().apply {
                put("id", photo.id); put("model_id", modelId); put("modified", photo.modifiedSeconds)
                put("size", photo.sizeBytes); put("name", photo.name)
            })
            observations.filter { it.qualityIssues.isEmpty() }.forEach { observation ->
                val box = observation.face.box
                db.insertOrThrow("faces", null, ContentValues().apply {
                    put("photo_id", photo.id); put("vector", encode(observation.embedding))
                    put("left_x", box.left/width); put("top_y", box.top/height)
                    put("right_x", box.right/width); put("bottom_y", box.bottom/height)
                })
            }
        }
    }
    data class PhotoMatch(val id: Long, val name: String, val similarity: Double, val box: FaceBox)

    /** Streams DB rows instead of loading the entire face gallery into memory. */
    fun searchPhotos(modelId: String, queries: List<EmbeddingVector>, threshold: Double,
                     checkCancelled: () -> Unit = {}): List<PhotoMatch> {
        require(queries.isNotEmpty() && queries.all { it.modelId == modelId })
        val best = mutableMapOf<Long, PhotoMatch>()
        readableDatabase.rawQuery("SELECT p.id,p.name,f.vector,f.left_x,f.top_y,f.right_x,f.bottom_y FROM faces f JOIN photos p ON p.id=f.photo_id WHERE p.model_id=?",
            arrayOf(modelId)).use { c ->
            while(c.moveToNext()) {
                checkCancelled()
                val vector = decode(modelId, c.getBlob(2))
                val score = queries.maxOf { it.cosineSimilarity(vector) }
                val id = c.getLong(0)
                if (score >= threshold && score > (best[id]?.similarity ?: -1.0))
                    best[id] = PhotoMatch(id, c.getString(1), score, FaceBox(c.getFloat(3), c.getFloat(4), c.getFloat(5), c.getFloat(6)))
            }
        }
        return best.values.sortedByDescending { it.similarity }
    }
    fun counts(modelId: String): Pair<Long, Long> =
        DatabaseUtils.longForQuery(readableDatabase, "SELECT COUNT(*) FROM photos WHERE model_id=?", arrayOf(modelId)) to
        DatabaseUtils.longForQuery(readableDatabase, "SELECT COUNT(*) FROM faces f JOIN photos p ON p.id=f.photo_id WHERE p.model_id=?", arrayOf(modelId))
    fun clearIndex() { writableDatabase.delete("photos", null, null) }
    fun clearAll() { transaction { it.delete("people", null, null); it.delete("photos", null, null) } }
    private fun transaction(block: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase; db.beginTransaction()
        try { block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    private fun encode(vector: EmbeddingVector): ByteArray {
        require(vector.dimension == 512)
        return ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN).apply { vector.values().forEach { putFloat(it) } }.array()
    }
    private fun decode(modelId: String, bytes: ByteArray): EmbeddingVector {
        check(bytes.size == 2048) { "人脸索引损坏，请清空后重建" }
        val floats = FloatArray(512)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
        return EmbeddingVector(modelId, floats)
    }
}
