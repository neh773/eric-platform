package org.oip.opcua.server

/**
 * Node ids for the tag address space. The namespace URI is [NAMESPACE_URI].
 *
 * `Tags` is the folder organized under Objects. `Tags/<provider>` is a tag provider.
 * `Tags/<provider>/<path>` is a folder or a tag; `<path>` uses the tag path's `/` segments.
 * the reference platform does not publish its tag NodeId text in the user manual, so this layout is ours.
 */
object TagNodeIds {
    const val NAMESPACE_URI = "urn:oip:tags"
    const val ROOT = "Tags"

    fun provider(name: String): String = "$ROOT/$name"

    fun tag(provider: String, path: String): String =
        if (path.isEmpty()) provider(provider) else "$ROOT/$provider/$path"

    fun parse(identifier: String): Loc? {
        if (identifier == ROOT) return Loc.Root
        if (!identifier.startsWith("$ROOT/")) return null
        val rest = identifier.removePrefix("$ROOT/")
        if (rest.isEmpty() || rest.endsWith("/") || rest.contains("//")) return null
        val slash = rest.indexOf('/')
        val provider = if (slash < 0) rest else rest.substring(0, slash)
        if (provider.isEmpty()) return null
        val path = if (slash < 0) "" else rest.substring(slash + 1)
        return if (path.isEmpty()) Loc.Provider(provider) else Loc.Tag(provider, path)
    }
}

sealed class Loc {
    data object Root : Loc()
    data class Provider(val name: String) : Loc()
    data class Tag(val provider: String, val path: String) : Loc() {
        val parentPath: String get() = if ('/' !in path) "" else path.substringBeforeLast('/')
        val name: String get() = path.substringAfterLast('/')
        fun address(): String = "[$provider]$path"
    }
}
