package net.discdd.trick.util

/**
 * URL encode a string (platform-specific).
 * 
 * @param s The string to encode
 * @param encoding The character encoding (typically "UTF-8")
 * @return The URL-encoded string
 */
expect fun urlEncode(s: String, encoding: String = "UTF-8"): String

/**
 * URL decode a string (platform-specific).
 * 
 * @param s The string to decode
 * @param encoding The character encoding (typically "UTF-8")
 * @return The URL-decoded string
 */
expect fun urlDecode(s: String, encoding: String = "UTF-8"): String


