package dev.kiora.companion

/** Select tools for the destination named by the user, without deciding whether to call one. */
internal object RemoteTaskRouting {
    private val remoteTarget = Regex("(?i)(DSH|A2A|电脑端|电脑上|远程|桌面|desktop|remote)")
    private val phoneShell = Regex("(?i)(手机命令|手机上运行|手机端执行|Android\\s*shell|本机\\s*shell)")

    fun requestsRemoteAgent(instruction: String): Boolean = remoteTarget.containsMatchIn(instruction)

    fun requestsPhoneShell(instruction: String): Boolean = phoneShell.containsMatchIn(instruction)

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
