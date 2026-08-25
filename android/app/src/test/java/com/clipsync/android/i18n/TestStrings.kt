package com.clipsync.android.i18n

import com.clipsync.android.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Test-side [UiText] resolution against the default (zh-Hans) strings.xml.
 *
 * Pure-JVM unit tests have no Android Context, so this mirrors what
 * `Context.getString` does — same resource file, same positional formatting —
 * letting tests keep asserting on the real human-readable words (P1#16).
 */
object TestStrings {
    private val idToName: Map<Int, String> =
        R.string::class.java.fields.associate { field -> field.getInt(null) to field.name }

    private val values: Map<String, String> = loadDefaultStrings()

    fun resolve(text: UiText): String =
        when (text) {
            is UiText.Raw -> text.value
            is UiText.Res -> {
                val template = values.getValue(idToName.getValue(text.id))
                if (text.args.isEmpty()) {
                    template
                } else {
                    val resolved =
                        text.args
                            .map { argument ->
                                if (argument is UiText) resolve(argument) else argument
                            }.toTypedArray()
                    String.format(template, *resolved)
                }
            }
        }

    private fun loadDefaultStrings(): Map<String, String> {
        // Gradle runs unit tests from the module directory; walking up also
        // covers IDE runs started from the repository root.
        val file =
            generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
                .flatMap {
                    sequenceOf(
                        File(it, "src/main/res/values/strings.xml"),
                        File(it, "app/src/main/res/values/strings.xml"),
                        File(it, "android/app/src/main/res/values/strings.xml"),
                    )
                }.firstOrNull(File::exists)
                ?: error("strings.xml not found from ${System.getProperty("user.dir")}")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                put(node.attributes.getNamedItem("name").nodeValue, node.textContent)
            }
        }
    }
}

/** Renders like the UI would under the default (zh-Hans) resources. */
fun UiText.testString(): String = TestStrings.resolve(this)
