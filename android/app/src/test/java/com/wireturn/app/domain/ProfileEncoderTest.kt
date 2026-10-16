package com.wireturn.app.domain

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Assert.*
import org.junit.Test

class ProfileEncoderTest {
    @Test fun cannotExportALinkThatTheSameApplicationRefusesToDecode() {
        val oversized = "я".repeat(ProfileEncoder.MAX_DECODED_BYTES / 2 + 1)
        assertThrows(IllegalArgumentException::class.java) { ProfileEncoder.encode(oversized) }
        assertNull(ProfileEncoder.encodeForSharing(oversized))
    }

    @Test fun largeInlinePayloadUsesFileSharingWhileNormalLinksRemainCompatible() {
        val random = java.util.Random(30L)
        val large = CharArray(300000) { ('!'.code + random.nextInt(90)).toChar() }.concatToString()
        assertEquals(large, ProfileEncoder.decode(ProfileEncoder.encode(large)))
        assertNull(ProfileEncoder.encodeForSharing(large))
        assertEquals(text, ProfileEncoder.decode(ProfileEncoder.encodeForSharing(text)!!))
    }

    private fun compress(bytes: ByteArray, dictionary: ByteArray? = null): ByteArray {
        val deflater = Deflater()
        return try {
            if (dictionary != null) deflater.setDictionary(dictionary)
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                check(count > 0)
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } finally { deflater.end() }
    }

    private fun transport(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private val text = """{"name":"Москва обход бс","uri":"vless://synthetic@example.invalid:443"}"""

    @Test fun roundTripAndLegacyWhitespaceRemainCompatible() {
        assertEquals(text, ProfileEncoder.decode(ProfileEncoder.encode(text)))
        val zipped = compress(text.toByteArray())
        val padded = Base64.getUrlEncoder().encodeToString(zipped)
        assertEquals(text, ProfileEncoder.decode(padded))
        assertEquals(text, ProfileEncoder.decode(padded.chunked(16).joinToString("\r\n")))
        assertEquals("https://example.invalid/subscription", ProfileEncoder.decode(
            ProfileEncoder.encode("https://example.invalid/subscription")))
    }

    @Test fun missingChecksumDoesNotReturnCompleteLookingJson() {
        val zipped = compress(text.toByteArray())
        assertNull(ProfileEncoder.decode(transport(zipped.copyOf(zipped.size - 4))))
    }

    @Test fun rejectsTruncatedBodyAndCorruptedChecksum() {
        val zipped = compress(text.toByteArray())
        assertNull(ProfileEncoder.decode(transport(zipped.copyOf(zipped.size / 2))))
        zipped[zipped.lastIndex] = (zipped.last().toInt() xor 1).toByte()
        assertNull(ProfileEncoder.decode(transport(zipped)))
    }

    @Test fun rejectsExpansionBeyondFourMiBButAcceptsExactBoundary() {
        val boundary = "x".repeat(4 * 1024 * 1024)
        assertEquals(boundary, ProfileEncoder.decode(transport(compress(boundary.toByteArray()))))
        assertTrue("Oversized expansion must be rejected", ProfileEncoder.decode(transport(compress((boundary + "x").toByteArray()))) == null)
    }

    @Test fun rejectsTrailingBytesAndConcatenatedZlibStreams() {
        val zipped = compress(text.toByteArray())
        assertNull(ProfileEncoder.decode(transport(zipped + byteArrayOf(1, 2, 3))))
        assertNull(ProfileEncoder.decode(transport(zipped + zipped)))
    }

    @Test fun rejectsInvalidUtf8AndRequiredDictionary() {
        assertNull(ProfileEncoder.decode(transport(compress(byteArrayOf(0xC3.toByte(), 0x28)))))
        assertNull(ProfileEncoder.decode(transport(compress(text.toByteArray(), "dictionary".toByteArray()))))
    }

    @Test fun rejectsOversizedEncodedInputEmptyAndInvalidAlphabet() {
        assertNull(ProfileEncoder.decode("A".repeat(6 * 1024 * 1024 + 1)))
        assertNull(ProfileEncoder.decode(""))
        assertNull(ProfileEncoder.decode("%%%not-base64%%%"))
    }
}
