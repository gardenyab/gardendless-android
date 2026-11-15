package com.fct.gardendless

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64

internal object DataUriDecoder {
    fun decode(dataUri: String): ByteArray {
        if (!dataUri.startsWith("data:", ignoreCase = true)) {
            throw IOException("Download URL is not a data URI")
        }

        val comma = dataUri.indexOf(',')
        if (comma < 0) throw IOException("Data URI is missing its content separator")

        val metadata = dataUri.substring(5, comma)
        val content = dataUri.substring(comma + 1)
        return if (metadata.split(';').any { it.equals("base64", ignoreCase = true) }) {
            try {
                Base64.getDecoder().decode(content)
            } catch (e: IllegalArgumentException) {
                throw IOException("Data URI contains invalid base64 content", e)
            }
        } else {
            decodePercentEncoded(content)
        }
    }

    private fun decodePercentEncoded(content: String): ByteArray {
        val output = ByteArrayOutputStream(content.length)
        var index = 0
        while (index < content.length) {
            if (content[index] == '%') {
                if (index + 2 >= content.length) throw IOException("Data URI contains an invalid escape")
                val high = content[index + 1].digitToIntOrNull(16)
                val low = content[index + 2].digitToIntOrNull(16)
                if (high == null || low == null) throw IOException("Data URI contains an invalid escape")
                output.write((high shl 4) or low)
                index += 3
            } else {
                val codePoint = Character.codePointAt(content, index)
                output.write(String(Character.toChars(codePoint)).toByteArray(StandardCharsets.UTF_8))
                index += Character.charCount(codePoint)
            }
        }
        return output.toByteArray()
    }
}
