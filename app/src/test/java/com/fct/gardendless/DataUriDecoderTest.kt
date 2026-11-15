package com.fct.gardendless

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class DataUriDecoderTest {
    @Test
    fun decodesBase64DataUrisToOriginalBytes() {
        val bytes = DataUriDecoder.decode("data:application/json;charset=utf-8;base64,eyJzYXZlIjoidGVzdCJ9")

        assertArrayEquals("""{"save":"test"}""".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun decodesPercentEscapesWithoutChangingPlusCharacters() {
        val bytes = DataUriDecoder.decode("data:text/plain,save%2Bslot%20one")

        assertArrayEquals("save+slot one".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test(expected = IOException::class)
    fun rejectsMalformedDataUris() {
        DataUriDecoder.decode("data:application/octet-stream,%GG")
    }
}
