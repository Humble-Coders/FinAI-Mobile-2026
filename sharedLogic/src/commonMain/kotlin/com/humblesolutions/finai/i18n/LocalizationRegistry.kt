package com.humblesolutions.finai.i18n

/**
 * Resolves a [Strings] key to text.
 *
 * One registry, so Android and iOS can never disagree about a label. Platforms
 * wrap this in their own helper — `strings(key)` in Compose, `loc.t(key)` in
 * Swift.
 */
object LocalizationRegistry {

    private val languages: Map<String, Map<String, String>> = mapOf(
        "en" to EnglishStrings,
    )

    private const val DEFAULT_LANGUAGE = "en"

    /** Returns the localized value, falling back to English, then to the key itself. */
    fun get(key: String, language: String = DEFAULT_LANGUAGE): String =
        languages[language]?.get(key)
            ?: languages.getValue(DEFAULT_LANGUAGE)[key]
            ?: key

    /**
     * [get], with `{0}`-style placeholders filled from [args].
     *
     * Substitution happens here rather than per platform so a translator writes
     * one template and both apps render it identically. A placeholder with no
     * argument is left as-is rather than blanked: a visible `{1}` is a bug
     * report, an empty gap is a mystery.
     */
    fun format(key: String, args: List<String>, language: String = DEFAULT_LANGUAGE): String =
        args.foldIndexed(get(key, language)) { index, text, arg -> text.replace("{$index}", arg) }
}
