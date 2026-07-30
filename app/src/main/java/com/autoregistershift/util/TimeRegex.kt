package com.autoregistershift.util

object TimeRegex {
    val pattern = Regex("""\b([01]\d|2[0-3]):[0-5]\d\b""")

    fun findAll(text: String): List<String> =
        pattern.findAll(text).map { it.value }.toList()
}
