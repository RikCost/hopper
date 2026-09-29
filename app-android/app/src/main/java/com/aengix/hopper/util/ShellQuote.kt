package com.aengix.hopper.util

object ShellQuote {
    fun bashSingle(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /**
     * Quotes a remote filesystem path for bash. Expands a leading `~/` via `$HOME`
     * so paths like `~/hopper` work when single-quoted tilde would not.
     */
    fun bashRemotePath(path: String): String {
        val trimmed = path.trim()
        return when {
            trimmed == "~" -> "\"\$HOME\""
            trimmed.startsWith("~/") -> "\"\$HOME\"/" + bashSingle(trimmed.removePrefix("~/"))
            else -> bashSingle(trimmed)
        }
    }
}
