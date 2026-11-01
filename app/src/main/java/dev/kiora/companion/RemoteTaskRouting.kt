package dev.kiora.companion

/** Select tools for the destination named by the user, without deciding whether to call one. */
internal object RemoteTaskRouting {
    fun suggestAlias(name: String): String = name.trim().substringBefore(' ')
        .takeIf { it.matches(Regex("[A-Za-z][A-Za-z0-9_-]{1,31}")) }
        ?.lowercase().orEmpty()

    fun matchingConnections(instruction: String, connections: List<A2aConnectionConfig>):
        List<A2aConnectionConfig> = connections.filter { profile ->
        profile.alias.isNotBlank() && aliasPattern(profile.alias).containsMatchIn(instruction)
    }

    fun containsRoutePhrase(instruction: String, alias: String): Boolean =
        routePhrase(alias).containsMatchIn(instruction)

    private fun aliasPattern(alias: String): Regex =
        Regex("(?i)(?<![A-Za-z0-9_-])${Regex.escape(alias)}(?![A-Za-z0-9_-])")

    private fun routePhrase(alias: String): Regex =
        Regex("(?i)(通过|经由|使用|用|交给|让|问(?:一下)?|咨询)\\s*${Regex.escape(alias)}(?:\\s*来)?")
}
