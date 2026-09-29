import Foundation

enum ShellQuote {
    static func bashSingle(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    /// Quotes a remote filesystem path for bash. Expands a leading `~/` via `$HOME`
    /// so paths like `~/hopper` work when single-quoted tilde would not.
    static func bashRemotePath(_ path: String) -> String {
        let trimmed = path.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed == "~" {
            return "\"$HOME\""
        }
        if trimmed.hasPrefix("~/") {
            let rest = String(trimmed.dropFirst(2))
            return "\"$HOME\"/" + bashSingle(rest)
        }
        return bashSingle(trimmed)
    }
}
