package com.tortugapower.browserdrop.internal

import com.tortugapower.browserdrop.Address
import com.tortugapower.browserdrop.BrowserDropServer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The `config.json` assertions of SwiftBrowserDrop's `ServerTests`, plus the address URL test. */
class PageConfigTest {
    @Test
    fun encodesEverySettingWithSortedKeys() {
        val configuration = BrowserDropServer.Configuration(
            allowedExtensions = setOf("mp3", "m4b"),
            maximumFileSize = 1_234,
            pageSettings = BrowserDropServer.PageSettings(
                title = "My App",
                language = "es",
                accentColor = 0xFFFF8800.toInt(),
                strings = mapOf("title" to "Subir")
            )
        )
        assertEquals(
            """{"accentColor":"#FF8800","allowedExtensions":["m4b","mp3"],"language":"es",""" +
                """"maximumConcurrentUploads":3,"maximumFileSize":1234,"protocolVersion":1,""" +
                """"strings":{"title":"Subir"},"title":"My App"}""",
            PageConfig.json(configuration)
        )
    }

    @Test
    fun leavesUnsetKeysOut() {
        assertEquals(
            """{"maximumConcurrentUploads":1,"maximumFileSize":8589934592,"protocolVersion":1,"strings":{}}""",
            PageConfig.json(BrowserDropServer.Configuration(maximumConcurrentUploads = 0))
        )
    }

    @Test
    fun dropsAlphaFromTheAccentColor() {
        assertEquals("#0A0B0C", PageConfig.hexColor(0x800A0B0C.toInt()))
    }

    @Test
    fun escapesStrings() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\\u003c\\u2028\"", Json.quote("a\"b\\c\nd\u0001<\u2028"))
        assertEquals("\"Caf\u00E9 日本\"", Json.quote("Caf\u00E9 日本"))
    }

    @Test
    fun leavesPort80OutOfTheUrl() {
        assertEquals("http://192.168.1.5/", Address(Address.Kind.WIFI, "wlan0", "192.168.1.5", 80).url)
        assertEquals("http://192.168.1.5:8080/", Address(Address.Kind.WIFI, "wlan0", "192.168.1.5", 8080).url)
    }
}
