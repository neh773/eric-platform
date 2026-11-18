package org.oip.gateway

import io.javalin.Javalin
import io.javalin.http.Context

/**
 * Browser security headers on the gateway's own pages and API (review-4 F8).
 *
 * - `/hmi`, `/studio`, `/admin` and their `/assets`: a Content-Security-Policy that runs only the gateway's own
 *   scripts (no inline script, no `eval`, no `javascript:` URL), connects only to this origin (the REST API and the
 *   session socket), and may be framed only by this origin (`frame-ancestors 'self'`, and `X-Frame-Options:
 *   SAMEORIGIN` for older browsers), so another site cannot frame an HMI button for a clickjack. Styles may be inline
 *   (components set style attributes), and images and media may come from any http(s) URL, as an image component
 *   names them. Frames may load this origin or an http(s) URL, for the PDF viewer component, which shows a document in
 *   the browser's own viewer; `object`/`embed` stay off and no other scheme (`data:`, `blob:`, `javascript:`) frames.
 * - `/api`: JSON, never a document: `default-src 'none'` and `frame-ancestors 'none'`.
 * - Both: `X-Content-Type-Options: nosniff` and `Referrer-Policy: same-origin`.
 *
 * Paths a project serves through the web development module are left alone; that content is the project's.
 */
object SecurityHeaders {
    const val APP_POLICY = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data: blob: http: https:; media-src 'self' data: blob: http: https:; font-src 'self' data:; " +
        "frame-src 'self' http: https:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'self'"
    const val API_POLICY = "default-src 'none'; frame-ancestors 'none'"

    private val APP_PREFIXES = listOf("/hmi", "/studio", "/admin", "/assets")

    fun register(app: Javalin) {
        app.after { ctx -> apply(ctx) }
    }

    internal fun apply(ctx: Context) {
        val path = ctx.path()
        val policy = when {
            path == "/api" || path.startsWith("/api/") -> API_POLICY
            APP_PREFIXES.any { path == it || path.startsWith("$it/") } -> APP_POLICY
            else -> return
        }
        ctx.header("Content-Security-Policy", policy)
        ctx.header("X-Content-Type-Options", "nosniff")
        ctx.header("Referrer-Policy", "same-origin")
        ctx.header("X-Frame-Options", if (policy === API_POLICY) "DENY" else "SAMEORIGIN")
    }
}
