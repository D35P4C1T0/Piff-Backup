package com.d35p4c1t0.piffbackup.backup

/** Relative-path rules shared by discovery, adoption, and verification. */
class FileSelectionPolicy(patterns: List<String> = emptyList(), private val includeHidden: Boolean = true) {
    private val rules = patterns.map { pattern ->
        require(pattern.isNotBlank() && pattern.length <= 256 && !pattern.startsWith('/') &&
            pattern.split('/').none { it == ".." || it == "." } &&
            pattern.none { it.isISOControl() || it == '\\' }) { "Invalid exclusion pattern" }
        val text = pattern.trimEnd('/')
        val expression = StringBuilder("^")
        var index = 0
        while (index < text.length) {
            when (val character = text[index]) {
                '*' -> if (index + 1 < text.length && text[index + 1] == '*') {
                    if (index + 2 < text.length && text[index + 2] == '/') {
                        expression.append("(?:.*/)?"); index += 2
                    } else { expression.append(".*"); index++ }
                } else expression.append("[^/]*")
                '?' -> expression.append("[^/]")
                else -> expression.append(Regex.escape(character.toString()))
            }
            index++
        }
        Regex(expression.append("(?:/.*)?$").toString())
    }

    fun includes(relativePath: String): Boolean {
        val components = relativePath.split('/')
        if (components.any { it == ".rsync-partial" || it == ".piffbackup-versions" }) return false
        if (!includeHidden && components.any { it.startsWith('.') }) return false
        return rules.none { it.matches(relativePath) || it.matches(components.last()) }
    }
}
