package com.tortugapower.audiobookplayer.ui.theme

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * R8 guard for the one Gson-crossing class owned by :app. BookPlayerThemeSpec is parsed from
 * `assets/Themes.json` by ThemeManager AND the widget provider; a field R8 renamed would silently
 * null that color in release builds. Two layers:
 *  1. every field carries @SerializedName (a missing one is invisible in unminified builds);
 *  2. the real bundled asset decodes with every field populated (catches a typo'd annotation
 *     value, which WOULD break unminified builds — against the actual shipped JSON).
 */
class ThemeSpecSerializedNameTest {

    @Test
    fun `every BookPlayerThemeSpec field is annotated with SerializedName`() {
        val missing = BookPlayerThemeSpec::class.java.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) && !it.isSynthetic }
            .filter { it.getAnnotation(SerializedName::class.java) == null }
            .map { it.name }
        assertTrue("Fields missing @SerializedName: $missing", missing.isEmpty())
    }

    @Test
    fun `bundled Themes json decodes with every color populated`() {
        // Unit tests run with the module directory as the working dir; fall back one level for
        // safety if the runner ever uses the project root.
        val asset = listOf("src/main/assets/Themes.json", "app/src/main/assets/Themes.json")
            .map(::File).firstOrNull(File::exists)
            ?: error("Themes.json asset not found from ${File(".").absolutePath}")

        val type = object : TypeToken<List<BookPlayerThemeSpec>>() {}.type
        val specs: List<BookPlayerThemeSpec> = Gson().fromJson(asset.readText(), type)
        assertTrue("Themes.json parsed to an empty list", specs.isNotEmpty())

        for (spec in specs) {
            for (field in BookPlayerThemeSpec::class.java.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) continue
                field.isAccessible = true
                // Gson bypasses Kotlin null-safety: a mismatched key leaves the String field null.
                assertNotNull("${spec.title}: field '${field.name}' is null after parsing", field.get(spec))
            }
        }
    }
}
