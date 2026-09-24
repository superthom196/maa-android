package io.github.superthom196.maa.data

/**
 * Asks /maa/info and copies the server's format and processing variant into the config, which is
 * what every new track URL and cache key uses. Throws what [MaApi.maaInfo] throws.
 */
suspend fun MaApi.syncPluginInfo(config: ConfigStore): MaaInfo {
    val info = maaInfo()
    val variant = info.variant.orEmpty()
    config.updateServer {
        if (it.format == info.format && it.variant == variant && it.pluginSeen) it
        else it.copy(format = info.format, variant = variant, pluginSeen = true)
    }
    return info
}
