package net.discdd.trick.util

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Android implementation of URL encoding using java.net.URLEncoder.
 */
actual fun urlEncode(s: String, encoding: String): String {
    return URLEncoder.encode(s, encoding)
}

/**
 * Android implementation of URL decoding using java.net.URLDecoder.
 */
actual fun urlDecode(s: String, encoding: String): String {
    return URLDecoder.decode(s, encoding)
}


