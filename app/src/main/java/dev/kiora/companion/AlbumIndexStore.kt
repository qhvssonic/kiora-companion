package dev.kiora.companion

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.kiora.embedding.EmbeddingVector
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Private, rebuildable semantic index. The photo bytes stay in MediaStore. */
internal class AlbumIndexStore(context: Context) : SQLiteOpenHelper(context,
    "album-embeddings.db", null, 1) {

    data class Photo(
        val id: Long,
        val name: String,
        val modifiedSeconds: Long,
        val sizeBytes: Long,
    )

    data class Entry(val photo: Photo, val vector: EmbeddingVector)
    data class Cleanup(val missing: Int, val changed: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE photo_embeddings (
              model_id TEXT NOT NULL,
              photo_id INTEGER NOT NULL,
              name TEXT NOT NULL,
              modified_seconds INTEGER NOT NULL,
              size_bytes INTEGER NOT NULL,
              dimensions INTEGER NOT NULL,
              vector BLOB NOT NULL,
              PRIMARY KEY (model_id, photo_id)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun count(modelId: String): Long = DatabaseUtils.longForQuery(readableDatabase,
        "SELECT COUNT(*) FROM photo_embeddings WHERE model_id=?", arrayOf(modelId))

    fun versions(modelId: String): Map<Long, Pair<Long, Long>> {
        val result = mutableMapOf<Long, Pair<Long, Long>>()
        readableDatabase.query("photo_embeddings",
            arrayOf("photo_id", "modified_seconds", "size_bytes"),
            "model_id=?", arrayOf(modelId), null, null, null).use { cursor ->
            while (cursor.moveToNext()) result[cursor.getLong(0)] =
                cursor.getLong(1) to cursor.getLong(2)
        }
        return result
    }

    fun entries(modelId: String): List<Entry> {
        val result = mutableListOf<Entry>()
        readableDatabase.query("photo_embeddings", null, "model_id=?", arrayOf(modelId),
            null, null, null).use { cursor ->
            val id = cursor.getColumnIndexOrThrow("photo_id")
            val name = cursor.getColumnIndexOrThrow("name")
            val modified = cursor.getColumnIndexOrThrow("modified_seconds")
            val size = cursor.getColumnIndexOrThrow("size_bytes")
            val dimensions = cursor.getColumnIndexOrThrow("dimensions")
            val vector = cursor.getColumnIndexOrThrow("vector")
            while (cursor.moveToNext()) {
                val count = cursor.getInt(dimensions)
                val bytes = cursor.getBlob(vector)
                if (count != 768 || bytes.size != count * Float.SIZE_BYTES) continue
                val floats = FloatArray(count)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                result += Entry(Photo(cursor.getLong(id), cursor.getString(name),
                    cursor.getLong(modified), cursor.getLong(size)), EmbeddingVector(modelId, floats))
            }
        }
        return result
    }

    fun put(modelId: String, photo: Photo, vector: EmbeddingVector) {
        require(vector.modelId == modelId && vector.dimension == 768)
        val bytes = ByteBuffer.allocate(vector.dimension * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        vector.values().forEach(bytes::putFloat)
        val values = android.content.ContentValues().apply {
            put("model_id", modelId)
            put("photo_id", photo.id)
            put("name", photo.name)
            put("modified_seconds", photo.modifiedSeconds)
            put("size_bytes", photo.sizeBytes)
            put("dimensions", vector.dimension)
            put("vector", bytes.array())
        }
        writableDatabase.insertWithOnConflict("photo_embeddings", null, values,
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Drop inaccessible photos and vectors made from an older version of a photo. */
    fun retainCurrent(modelId: String, visible: Map<Long, Pair<Long, Long>>): Cleanup {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val missing = mutableListOf<Long>()
            val changed = mutableListOf<Long>()
            db.query("photo_embeddings", arrayOf("photo_id", "modified_seconds", "size_bytes"),
                "model_id=?", arrayOf(modelId), null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val current = visible[id]
                    if (current == null) missing += id
                    else if (current != (cursor.getLong(1) to cursor.getLong(2))) changed += id
                }
            }
            (missing + changed).forEach { id -> db.delete("photo_embeddings", "model_id=? AND photo_id=?",
                arrayOf(modelId, id.toString())) }
            db.setTransactionSuccessful()
            return Cleanup(missing.size, changed.size)
        } finally {
            db.endTransaction()
        }
    }

    fun deleteOtherModels(modelId: String): Int = writableDatabase.delete(
        "photo_embeddings", "model_id<>?", arrayOf(modelId))

    fun clear() { writableDatabase.delete("photo_embeddings", null, null) }
}
