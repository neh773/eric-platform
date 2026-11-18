package org.oip.gateway

import org.oip.config.ProjectService
import org.oip.config.ResourceId
import org.oip.config.Scope

/**
 * Term translations by locale (doc source: FEATURES HMI-005).
 *
 * A project's `Translation` resource (`designer/project/{project}/Translation/messages`) keeps terms for one
 * locale in `messages` (with `locale` naming it) and for any number of locales in `locales`
 * (locale tag to term to text). A lookup tries the exact locale tag, then its language (`es-MX` then `es`),
 * and gives the term itself back when nothing matches, so an untranslated label shows its key.
 */
internal object Translations {
    const val RESOURCE = "messages"

    /** The text for [term] in [locale], or [term] when the resource has none. */
    fun lookup(spec: Map<String, Any?>?, term: String, locale: String): String =
        messages(spec, locale)[term] ?: term

    /**
     * Every term that has a translation for [locale]; language-level terms fill in what a regional tag leaves out.
     * Empty when the locale has none.
     */
    fun messages(spec: Map<String, Any?>?, locale: String): Map<String, String> {
        if (spec == null || locale.isBlank()) return emptyMap()
        val language = locale.substringBefore('-')
        val merged = LinkedHashMap<String, String>()
        for (tag in listOf(language, locale).distinct()) merged.putAll(forTag(spec, tag))
        return merged
    }

    /** The locale tags a resource has terms for. */
    fun locales(spec: Map<String, Any?>?): List<String> {
        if (spec == null) return emptyList()
        val out = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        (spec["locales"] as? Map<*, *>)?.keys?.forEach { (it as? String)?.let(out::add) }
        (spec["locale"] as? String)?.takeIf { it.isNotBlank() && (spec["messages"] as? Map<*, *>)?.isNotEmpty() == true }?.let(out::add)
        return out.toList()
    }

    private fun forTag(spec: Map<String, Any?>, tag: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val own = spec["locale"] as? String
        if (own != null && own.equals(tag, ignoreCase = true)) strings(spec["messages"], out)
        val byLocale = spec["locales"] as? Map<*, *>
        byLocale?.entries?.firstOrNull { (it.key as? String)?.equals(tag, ignoreCase = true) == true }?.let { strings(it.value, out) }
        return out
    }

    private fun strings(raw: Any?, into: MutableMap<String, String>) {
        (raw as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v is String) into[k] = v }
    }

    fun spec(projects: ProjectService, project: String): Map<String, Any?>? =
        projects.resolve(ResourceId(DESIGNER_MODULE, Scope.project(project), TRANSLATION_KIND, RESOURCE))?.resource?.spec

    /**
     * `system.util.translate`: the text for [term] in [locale] from [project], or from the first project (by name)
     * that has one when [project] is null. [locale] defaults to the project's session locale, then `en-US`.
     */
    fun translate(projects: ProjectService, term: String, locale: String?, project: String?): String {
        val names = if (project != null) listOf(project) else projects.list().map { it.name }.sorted()
        for (name in names) {
            val effective = locale?.takeIf { it.isNotBlank() }
                ?: projects.info(name)?.settings?.sessionLocale?.takeIf { it.isNotBlank() } ?: "en-US"
            val found = messages(spec(projects, name), effective)[term]
            if (found != null) return found
        }
        return term
    }
}
