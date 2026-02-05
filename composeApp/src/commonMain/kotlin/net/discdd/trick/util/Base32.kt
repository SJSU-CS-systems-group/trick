package net.discdd.trick.util

/**
 * RFC 4648 Base32 encoding/decoding utility.
 *
 * Uses the standard alphabet: A-Z (0-25) and 2-7 (26-31)
 * This alphabet is compatible with QR code alphanumeric mode since it
 * contains only uppercase letters and digits 2-7.
 *
 * No padding variant is used for compactness.
 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    // Lookup table for decoding (char code -> value, -1 for invalid)
    private val DECODE_TABLE = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { index, char ->
            this[char.code] = index
        }
    }

    /**
     * Encode a byte array to Base32 string (no padding).
     *
     * Base32 encodes 5 bytes into 8 characters.
     * Each character represents 5 bits.
     *
     * @param data The bytes to encode
     * @return Base32 encoded string (uppercase A-Z, 2-7)
     */
    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""

        val result = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0

        for (byte in data) {
            // Add 8 bits from byte to buffer
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8

            // Extract 5-bit chunks
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                val index = (buffer shr bitsLeft) and 0x1F
                result.append(ALPHABET[index])
            }
        }

        // Handle remaining bits (if any)
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            result.append(ALPHABET[index])
        }

        return result.toString()
    }

    /**
     * Decode a Base32 string to byte array.
     *
     * @param encoded The Base32 string (case-insensitive, no padding expected)
     * @return Decoded byte array
     * @throws IllegalArgumentException if string contains invalid characters
     */
    fun decode(encoded: String): ByteArray {
        if (encoded.isEmpty()) return ByteArray(0)

        // Remove any padding (=) if present
        val input = encoded.trimEnd('=').uppercase()

        // Calculate output size: 5 chars = 25 bits = 3 bytes (with 1 bit padding)
        // More precisely: (inputLength * 5) / 8 rounded down
        val outputSize = (input.length * 5) / 8
        val result = ByteArray(outputSize)

        var buffer = 0
        var bitsLeft = 0
        var outputIndex = 0

        for (char in input) {
            val charCode = char.code
            if (charCode >= 128) {
                throw IllegalArgumentException("Invalid Base32 character: $char")
            }

            val value = DECODE_TABLE[charCode]
            if (value == -1) {
                throw IllegalArgumentException("Invalid Base32 character: $char")
            }

            // Add 5 bits to buffer
            buffer = (buffer shl 5) or value
            bitsLeft += 5

            // Extract 8-bit chunks
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                if (outputIndex < outputSize) {
                    result[outputIndex++] = ((buffer shr bitsLeft) and 0xFF).toByte()
                }
            }
        }

        return result
    }
}
