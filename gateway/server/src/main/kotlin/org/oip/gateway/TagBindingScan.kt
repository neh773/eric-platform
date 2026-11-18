package org.oip.gateway

import org.oip.config.FileConfigStore
import org.oip.tags.TagLink
import org.oip.tags.TagPath
import org.oip.tags.expr.Expressions

/**
 * View bindings that read one tag, for tag diagnostics (TAG-003).
 *
 * Walks designer View documents. A direct tag binding matches on its path. An expression binding
 * matches when a static `{tag}` reference resolves to the tag. This runs when diagnostics are read,
 * not when a tag value is published. Alarm and history subscriptions are counted as subscribers and
 * are not named here.
 */
internal object TagBindingScan {
    fun referencing(store: FileConfigStore, tag: TagPath): List<TagLink> {
        val provider = tag.provider ?: "default"
        val want = try {
            tag.copy(indexes = emptyList(), property = null, relative = null).canonicalKey()
        } catch (_: RuntimeException) {
            return emptyList()
        }
        val out = ArrayList<TagLink>()
        val seen = HashSet<String>()
        val views = store.list(DESIGNER_MODULE, null, VIEW_KIND)
        for (stored in views) {
            val project = stored.id.scope.qualifier ?: stored.id.scope.path
            val viewPath = stored.resource.spec["path"] as? String ?: stored.id.name
            try {
                walk(stored.resource.spec["document"], "$project/$viewPath", want, provider, out, seen)
            } catch (_: RuntimeException) {
                // One unreadable document does not hide the other views.
            }
        }
        out.sortBy { it.path }
        return out
    }

    private fun walk(
        node: Any?,
        place: String,
        want: String,
        provider: String,
        out: MutableList<TagLink>,
        seen: MutableSet<String>,
    ) {
        when (node) {
            is Map<*, *> -> {
                val id = node["id"] as? String
                val here = if (!id.isNullOrBlank()) "$place/$id" else place
                val bindings = node["bindings"]
                if (bindings is Map<*, *>) {
                    for ((prop, binding) in bindings) {
                        if (binding is Map<*, *>) note(binding, "$here.$prop", want, provider, out, seen)
                    }
                }
                for ((key, value) in node) {
                    if (key == "bindings") continue
                    walk(value, here, want, provider, out, seen)
                }
            }
            is List<*> -> for (child in node) walk(child, place, want, provider, out, seen)
        }
    }

    private fun note(
        binding: Map<*, *>,
        place: String,
        want: String,
        provider: String,
        out: MutableList<TagLink>,
        seen: MutableSet<String>,
    ) {
        if (place in seen) return
        val kind = (binding["kind"] as? String)?.lowercase()
        val hit = when (kind) {
            "expression" -> expressionReads(binding["expression"] as? String, want, provider)
            else -> {
                val path = (binding["path"] ?: binding["tagPath"]) as? String
                path != null && sameTag(path, want, provider)
            }
        }
        if (!hit) return
        seen.add(place)
        out.add(TagLink(place, "binding"))
    }

    private fun expressionReads(text: String?, want: String, provider: String): Boolean {
        if (text.isNullOrBlank()) return false
        val refs = try {
            Expressions.staticRefs(Expressions.parse(text))
        } catch (_: RuntimeException) {
            return false
        }
        return refs.any { sameTag(it, want, provider) }
    }

    private fun sameTag(raw: String, want: String, provider: String): Boolean {
        val parsed = try {
            TagPath.parse(raw.trim(), provider).resolve(null)
        } catch (_: RuntimeException) {
            return false
        }
        return parsed.copy(indexes = emptyList(), property = null).canonicalKey() == want
    }
}
