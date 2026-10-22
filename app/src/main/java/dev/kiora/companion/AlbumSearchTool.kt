package dev.kiora.companion

import android.content.Context
import dev.kiora.core.AgentTool
import dev.kiora.core.ToolDefinition
import dev.kiora.embedding.EmbeddingVector
import dev.kiora.embedding.mediapipe.EmbeddingGemma2Port
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Model-facing, read-only semantic search over photos already indexed by the user. */
internal class AlbumSearchTool(private val context: Context) : AgentTool {
    override val definition = ToolDefinition(
        name = NAME,
        description = "Search photos that the user has already indexed in Kiora's local album using a " +
            "natural-language description. Return at most three ranked candidate photos. This does not " +
            "scan or upload photos. The client displays returned photos directly in the chat; for a " +
            "search-only request, briefly refer to the displayed photos and do not call read. " +
            "The user can refer to a displayed photo as first, second or third; use the returned " +
            "photoId yourself rather than asking the user to copy an ID. " +
            "Only if the user explicitly asks to analyze a photo's contents, call read with a path " +
            "such as gallery:gallery_123 using the returned photoId. Ranking is not verified recognition. " +
            "Works only within the photos currently granted to this Android app.",
        parameters = JsonObject(mapOf(
            "type" to JsonPrimitive("object"),
            "properties" to JsonObject(mapOf(
                "query" to JsonObject(mapOf(
                    "type" to JsonPrimitive("string"),
                    "description" to JsonPrimitive("A short description of the photo to find"),
                )),
                "limit" to JsonObject(mapOf(
                    "type" to JsonPrimitive("integer"),
                    "description" to JsonPrimitive("Number of candidate photos to display, from 1 to 3; default 3"),
                )),
            )),
            "required" to JsonArray(listOf(JsonPrimitive("query"))),
        )),
    )

    override suspend fun execute(arguments: JsonObject): String {
        val query = arguments["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(query.isNotBlank() && query.length <= 200) { "请用不超过 200 字描述要找的照片" }
        val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: 3).coerceIn(1, 3)
        val result = AlbumSearchService(context).search(query, limit)
        return buildJsonObject {
            put("query", query)
            put("candidateCount", result.matches.size)
            put("items", JsonArray(result.matches.map { match -> buildJsonObject {
                put("photoId", "gallery_${match.photo.id}")
                put("name", match.photo.name)
            } }))
            put("note", "这些是排序候选照片，已在聊天中直接显示；仅当用户要求分析照片内容时才调用 read，路径格式为 gallery:gallery_<数字ID>。不要把候选数说成全部匹配数。")
        }.toString()
    }

    companion object { const val NAME = "search_album_photos" }
}

/** Shared album search logic; indexing is configured in settings and the agent owns no photo grant. */
internal class AlbumSearchService(private val context: Context) {
    data class Match(val photo: AlbumIndexStore.Photo, val score: Double)
    data class Result(val matches: List<Match>)

    suspend fun search(query: String, limit: Int): Result {
        require(isReady(context)) { "请先在设置中导入相册模型并授权照片" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modelFile = File(requireNotNull(prefs.getString("path", null)))
        val modelId = "embeddinggemma2-440m:${requireNotNull(prefs.getString("sha256", null))}:photo-v1"
        val index = AlbumIndexStore(context)
        try {
            val visible = withContext(Dispatchers.IO) {
                AlbumPhotoCatalog.scan(context).associate { it.id to
                    (it.modifiedSeconds to it.sizeBytes) }
            }
            val entries = withContext(Dispatchers.IO) {
                index.retainCurrent(modelId, visible)
                index.entries(modelId)
            }
            if (entries.isEmpty()) return Result(emptyList())
            val port = EmbeddingGemma2Port(context, modelFile, modelId)
            val queryVector = try {
                port.embedQuery(query)
            } finally {
                port.close()
            }
            return Result(rank(queryVector, entries, limit))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { index.close() }
        }
    }

    private fun rank(query: EmbeddingVector, entries: List<AlbumIndexStore.Entry>, limit: Int) =
        entries.asSequence().map { Match(it.photo, query.cosineSimilarity(it.vector)) }
            .sortedByDescending(Match::score).take(limit).toList()

    companion object {
        private const val PREFS = "album_search"

        fun isReady(context: Context): Boolean {
            if (!GalleryAccess.hasFullAccess(context) && !GalleryAccess.hasPartialAccess(context)) return false
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val path = prefs.getString("path", null) ?: return false
            if (prefs.getString("sha256", null).isNullOrBlank()) return false
            return File(path).isFile
        }
    }
}
