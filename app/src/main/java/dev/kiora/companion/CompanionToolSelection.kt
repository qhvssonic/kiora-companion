package dev.kiora.companion

/** Host policy based on configured capabilities, independent of the current scene. */
internal data class CompanionToolSelection(
    val shellEnabled: Boolean,
    val hasFolders: Boolean,
    val hasGallery: Boolean,
    val remoteTools: Set<String>,
    val imageOnlyQuestion: Boolean = false,
    val remoteAgentRequested: Boolean = false,
    val phoneShellRequested: Boolean = false,
) {
    fun select(available: Set<String>): Set<String> = if (imageOnlyQuestion) emptySet() else buildSet {
        addAll(setOf("read", "write", "edit", "get_local_time"))
        if (shellEnabled && (!remoteAgentRequested || phoneShellRequested)) add("shell")
        if (hasFolders || hasGallery) add(PhoneResourceTools.BROWSE)
        if (hasFolders) add(PhoneResourceTools.MANAGE)
        addAll(remoteTools)
    }.intersect(available)
}
