package com.humblesolutions.finai.util

/**
 * The flag for an ISO 3166 region code, as an emoji.
 *
 * Each letter becomes its regional indicator symbol, which every platform draws
 * as a flag — so no flag images ship with the app, and a region we have never
 * heard of still renders something sensible. Anything that is not two letters
 * comes back empty, and the caller shows the code alone.
 */
fun flagEmoji(region: String): String {
    if (region.length != 2) return ""
    val upper = region.uppercase()
    if (upper.any { it !in 'A'..'Z' }) return ""
    return buildString {
        for (letter in upper) {
            // Regional indicators live above the basic plane, so each one is
            // written as a surrogate pair.
            val codePoint = 0x1F1E6 + (letter - 'A')
            append((((codePoint - 0x10000) shr 10) + 0xD800).toChar())
            append((((codePoint - 0x10000) and 0x3FF) + 0xDC00).toChar())
        }
    }
}
