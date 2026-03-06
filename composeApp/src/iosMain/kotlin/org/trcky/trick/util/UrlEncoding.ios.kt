package org.trcky.trick.util

import platform.Foundation.*

/**
 * iOS implementation of URL encoding using Foundation APIs.
 */
actual fun urlEncode(s: String, encoding: String): String {
    // Use URLQueryAllowedCharacterSet for URL encoding (similar to java.net.URLEncoder behavior)
    val nsString = s as NSString
    // URLQueryAllowedCharacterSet is a static property on NSCharacterSet
    val allowedCharacters = NSCharacterSet.URLQueryAllowedCharacterSet
    return nsString.stringByAddingPercentEncodingWithAllowedCharacters(allowedCharacters)
        ?: s // Fallback to original string if encoding fails
}

/**
 * iOS implementation of URL decoding using Foundation APIs.
 */
actual fun urlDecode(s: String, encoding: String): String {
    val nsString = s as NSString
    return nsString.stringByRemovingPercentEncoding ?: s // Fallback to original string if decoding fails
}

