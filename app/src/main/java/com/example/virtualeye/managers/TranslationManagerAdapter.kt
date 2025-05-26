fun getLanguageName(code: String): String {
    // Map of language codes to their proper display names
    val languageNames = mapOf(
        "en" to "English",
        "te" to "Telugu",
        "hi" to "Hindi",
        "ta" to "Tamil",
        "ml" to "Malayalam",
        "ko" to "Korean",
        "ja" to "Japanese"
    )
    
    // Return the mapped name if available, otherwise use the locale's display name
    return languageNames[code] ?: Locale(code).displayLanguage
} 