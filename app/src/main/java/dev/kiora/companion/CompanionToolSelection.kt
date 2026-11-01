package dev.kiora.companion

/** Host policy based on configured capabilities, independent of the current scene. */
internal data class CompanionToolSelection(
    val shellEnabled: Boolean,
    val hasFolders: Boolean,
    val hasGallery: Boolean,
    val hasAlbumSearch: Boolean = false,
    val remoteTools: Set<String>,
    val remoteTargetSelected: Boolean = false,
) {
    fun select(available: Set<String>): Set<String> = buildSet {
        addAll(setOf("read", "write", "edit", "get_local_time"))
        if (shellEnabled && !remoteTargetSelected) add("shell")
        if (hasFolders || hasGallery) add(PhoneResourceTools.BROWSE)
        if (hasAlbumSearch) add(AlbumSearchTool.NAME)
        if (hasFolders) add(PhoneResourceTools.MANAGE)
        addAll(remoteTools)
    }.intersect(available)
}
