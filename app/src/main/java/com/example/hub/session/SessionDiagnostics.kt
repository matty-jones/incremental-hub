package com.example.hub.session

/**
 * How a session event should surface.
 * Only [FATAL] may interrupt play. [WARNING] and [INFO] stay in the report.
 */
enum class DiagnosticSeverity {
    FATAL,
    WARNING,
    INFO,
}

data class DiagnosticEvent(
    val atMillis: Long,
    val severity: DiagnosticSeverity,
    val category: String,
    val message: String,
)

/**
 * Classifies a WebView resource failure.
 *
 * A missing favicon is informational. Other subresources stay off the play
 * surface as well. A main-frame failure is fatal.
 */
fun classifyResourceFailure(url: String, statusCode: Int?, mainFrame: Boolean): DiagnosticSeverity {
    if (mainFrame) return DiagnosticSeverity.FATAL
    val path = url.substringBefore('?').substringBefore('#')
    if (path.endsWith("favicon.ico")) return DiagnosticSeverity.INFO
    if (path.endsWith(".js") || path.endsWith(".mjs") || path.endsWith(".css")) {
        return DiagnosticSeverity.WARNING
    }
    if (statusCode != null && statusCode >= 500) return DiagnosticSeverity.WARNING
    return DiagnosticSeverity.INFO
}

fun isFaviconRequest(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#')
    return path.endsWith("favicon.ico")
}
