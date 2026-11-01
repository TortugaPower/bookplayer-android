package com.tortugapower.browserdrop.internal

import com.tortugapower.browserdrop.BrowserDropServer
import java.util.Locale

/**
 * The body of `GET /config.json`: protocol version 1, keys sorted, optional keys left out when unset.
 * Hand-encoded ([Json]) so the module needs no JSON library (`org.json` is a stub in JVM unit tests).
 */
internal object PageConfig {
    const val PROTOCOL_VERSION = 1

    fun json(configuration: BrowserDropServer.Configuration): String {
        val settings = configuration.pageSettings
        val fields = sortedMapOf<String, String>()
        settings.accentColor?.let { fields["accentColor"] = Json.quote(hexColor(it)) }
        configuration.allowedExtensions?.let { extensions ->
            fields["allowedExtensions"] = extensions.sorted().joinToString(",", "[", "]") { Json.quote(it) }
        }
        settings.language?.let { fields["language"] = Json.quote(it) }
        fields["maximumConcurrentUploads"] = maxOf(1, configuration.maximumConcurrentUploads).toString()
        fields["maximumFileSize"] = configuration.maximumFileSize.toString()
        fields["protocolVersion"] = PROTOCOL_VERSION.toString()
        fields["strings"] = settings.strings.toSortedMap()
            .entries.joinToString(",", "{", "}") { (key, value) -> "${Json.quote(key)}:${Json.quote(value)}" }
        settings.title?.let { fields["title"] = Json.quote(it) }
        return fields.entries.joinToString(",", "{", "}") { (key, value) -> "${Json.quote(key)}:$value" }
    }

    /** `#RRGGBB` from an ARGB color; the format can't carry anything into the page's CSS. */
    fun hexColor(argb: Int): String = "#%06X".format(Locale.ROOT, argb and 0xFFFFFF)
}
