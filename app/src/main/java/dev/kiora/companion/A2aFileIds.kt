package dev.kiora.companion

/** Resolves model-provided file references against attachments selected for this turn only. */
internal object A2aFileIds {
    private val galleryUri = Regex("^content://media/[^/?]+/images/media/([0-9]+)(?:\\?.*)?$")

    data class SelectedAliases(
        val unique: Map<String, Int>,
        val ambiguous: Set<String>,
    )

    fun selectedAliases(selectedUris: List<String>, selectedNames: List<String>): SelectedAliases {
        require(selectedUris.size == selectedNames.size)
        val matches = mutableMapOf<String, MutableSet<Int>>()
        fun add(alias: String, index: Int) {
            matches.getOrPut(alias) { mutableSetOf() }.add(index)
        }
        selectedUris.forEachIndexed { index, uri ->
            galleryUri.matchEntire(uri)?.groupValues?.get(1)?.let { add("gallery_$it", index) }
            selectedNames[index].takeIf { name ->
                name.isNotBlank() && name.none { it == '/' || it == '\\' || it == ':' } &&
                    !name.all(Char::isDigit)
            }?.let { add(it, index) }
        }
        return SelectedAliases(
            unique = matches.mapNotNull { (alias, indices) ->
                indices.singleOrNull()?.let { alias to it }
            }.toMap(),
            ambiguous = matches.filterValues { it.size > 1 }.keys,
        )
    }

    fun normalize(id: String, selectedCount: Int, aliases: SelectedAliases = SelectedAliases(emptyMap(), emptySet())): String {
        if (id.startsWith("selected:") || id.startsWith("workspace:") || id.startsWith("authorized:")) return id
        require(id !in aliases.ambiguous) { "本轮有同名附件，请使用 selected:<index> 指定具体文件" }
        aliases.unique[id]?.let { index ->
            if (index in 0 until selectedCount) return "selected:$index"
        }
        val index = id.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
        return if (index != null && index < selectedCount) "selected:$index" else id
    }

    fun boundIds(selectedCount: Int, selectedPhotoIds: Set<String>?): List<String> {
        val selectedIds = (0 until selectedCount).map { "selected:$it" }
        require(selectedPhotoIds == null || selectedIds.containsAll(selectedPhotoIds)) {
            "待发送照片已不在本轮附件中"
        }
        return if (selectedPhotoIds == null) selectedIds
            else selectedIds.filter { it in selectedPhotoIds }
    }

    fun requireCompleteAlbumTransfer(ids: List<String>, selectedPhotoIds: Set<String>?) {
        if (selectedPhotoIds == null) return
        require(ids.toSet() == selectedPhotoIds) {
            "本轮需同时发送已选的 ${selectedPhotoIds.size} 张照片：${selectedPhotoIds.sorted().joinToString()}"
        }
    }
}
