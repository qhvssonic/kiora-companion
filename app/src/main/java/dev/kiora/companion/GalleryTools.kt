package dev.kiora.companion

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.kiora.core.AgentTool
import dev.kiora.core.ToolDefinition
import dev.kiora.core.ToolImage
import dev.kiora.core.ToolOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Read-only MediaStore tools; listing requires full access, reading also accepts selected photos. */
internal class GalleryTools(
    private val context: Context,
    private val loadImage: suspend (Uri) -> ToolImage,
    private val contextWindowTokens: () -> Int?,
) {
    private val resolver = context.contentResolver
    private val collection: Uri = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun all(): List<AgentTool> = listOf(listTool, readTool)

    private val listTool = object : AgentTool {
        override val definition = ToolDefinition(
            name = "list_gallery_photos",
            description = "Browse the phone's photo library after full Android photo access is granted. " +
                "Returns recent photos or filename matches with photo IDs. Use offset to page through the library. " +
                "Call read with gallery:gallery_<numericId> only when the user asks to analyze image content; filenames alone do not reveal the image.",
            parameters = schema(
                "query" to "Optional filename fragment; omit for recent photos",
                "limit" to "Requested photos per page; constrained by the current model context window",
                "offset" to "Number of preceding photos to skip, starting at 0",
            ),
        )

        override suspend fun execute(arguments: JsonObject): String = withContext(Dispatchers.IO) {
            requireFullAccess()
            val query = arguments["query"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(query.length <= 100) { "Search query is too long" }
            val maxItems = ((contextWindowTokens() ?: 6_000) / 300).coerceIn(4, 100)
            val limit = arguments["limit"]?.jsonPrimitive?.content?.toIntOrNull()
                ?.coerceIn(1, maxItems) ?: minOf(8, maxItems)
            val offset = arguments["offset"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            require(offset <= 10_000) { "Offset is too large" }
            val photos = scan(query, offset, limit + 1)
            buildJsonObject {
                put("items", JsonArray(photos.take(limit).map(Photo::asJson)))
                put("truncated", JsonPrimitive(photos.size > limit))
                put("nextOffset", JsonPrimitive(if (photos.size > limit) offset + limit else -1))
            }.toString()
        }
    }

    private val readTool = object : AgentTool {
        override val definition = ToolDefinition(
            name = "read_gallery_photo",
            description = "Return one gallery photo as image content for the current vision model. " +
                "Requires full or selected-photo Android access. " +
                "Use an ID from browse_phone_files or search_album_photos; only one image is examined per call.",
            parameters = schema(
                "photoId" to "Photo ID returned by list_gallery_photos or search_album_photos",
                required = listOf("photoId"),
            ),
        )

        override suspend fun execute(arguments: JsonObject): String = executeResult(arguments).text

        override suspend fun executeResult(arguments: JsonObject): ToolOutput {
            requirePhotoAccess()
            val id = arguments["photoId"]?.jsonPrimitive?.content.orEmpty()
            val uri = photoUri(id)
            val name = withContext(Dispatchers.IO) { find(id)?.name }
                ?: error("相册中未找到这张照片")
            return ToolOutput("Image from phone gallery: $name ($id)", listOf(loadImage(uri)))
        }
    }

    private data class Photo(
        val id: Long,
        val name: String,
        val mimeType: String,
        val sizeBytes: Long,
        val addedSeconds: Long,
        val width: Int,
        val height: Int,
    ) {
        fun asJson() = buildJsonObject {
            put("photoId", JsonPrimitive("gallery_$id"))
            put("name", JsonPrimitive(name))
            put("mimeType", JsonPrimitive(mimeType))
            put("sizeBytes", JsonPrimitive(sizeBytes))
            put("addedSeconds", JsonPrimitive(addedSeconds))
            put("width", JsonPrimitive(width))
            put("height", JsonPrimitive(height))
        }
    }

    private fun scan(query: String, offset: Int, limit: Int): List<Photo> {
        val columns = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val where = if (query.isBlank()) null else "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val args = if (query.isBlank()) null else arrayOf("%$query%")
        val result = ArrayList<Photo>(limit)
        resolver.query(collection, columns, where, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")?.use { cursor ->
            if (offset > 0 && !cursor.moveToPosition(offset - 1)) return@use
            while (cursor.moveToNext() && result.size < limit) {
                result += Photo(
                    cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                    cursor.getLong(3), cursor.getLong(4), cursor.getInt(5), cursor.getInt(6),
                )
            }
        }
        return result
    }

    private fun find(photoId: String): Photo? {
        val id = parseId(photoId)
        val uri = ContentUris.withAppendedId(collection, id)
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT)
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else Photo(
                cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                cursor.getLong(3), cursor.getLong(4), cursor.getInt(5), cursor.getInt(6),
            )
        }
    }

    private fun photoUri(photoId: String): Uri = ContentUris.withAppendedId(collection, parseId(photoId))

    private fun parseId(photoId: String): Long {
        require(photoId.startsWith("gallery_")) { "Invalid gallery photo ID" }
        return photoId.removePrefix("gallery_").toLongOrNull()?.takeIf { it >= 0 }
            ?: error("Invalid gallery photo ID")
    }

    private fun requireFullAccess() {
        require(GalleryAccess.hasFullAccess(context)) { "请先在连接设置中允许访问完整相册" }
    }

    private fun requirePhotoAccess() {
        require(GalleryAccess.hasFullAccess(context) || GalleryAccess.hasPartialAccess(context)) {
            "请先授权可读取的相册照片"
        }
    }

    private fun schema(vararg properties: Pair<String, String>, required: List<String> = emptyList()) =
        JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(properties.associate { (name, description) ->
                name to JsonObject(mapOf(
                    "type" to JsonPrimitive(if (name == "limit" || name == "offset") "integer" else "string"),
                    "description" to JsonPrimitive(description),
                ))
            }),
            "required" to JsonArray(required.map(::JsonPrimitive)),
        ))
}
