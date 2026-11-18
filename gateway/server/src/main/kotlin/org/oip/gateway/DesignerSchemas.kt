package org.oip.gateway

import org.oip.config.SchemaRegistry

/**
 * Designer views are typed config resources (SPEC §4, ADR 0005), owned by module [DESIGNER_MODULE]:
 * `<configRoot>/designer/project/<project>/View/<name>.yaml`. The resource name is the view's slug;
 * `spec.path` carries the view's project-tree path (e.g. `Overview` or `Pumps/Detail`) since a view
 * path may contain characters a resource name cannot (ADR 0005 names are DNS-label style).
 *
 * `spec.title` is the display name shown in the project tree (the web model's `metadata.name`).
 * Saving through the gateway, and conflict resolution at save time, follow
 * - ("The Designer saves all your projects to the
 * Gateway"; conflicting edits are resolved when the project is saved). Concurrency is `metadata.version` (SPEC §4).
 *
 * The view body (`spec.document`) is the `ViewDocument.spec` the web `@oip/hmi-runtime` package
 * already defines and validates on the client (params, custom props, root container tree, bindings).
 * The gateway does not re-validate that nested shape; it is free-form JSON here, same as how
 * `gateway/tags` leaves UDT `parameters`/`members` as nested maps validated by the owning code, not
 * JSON Schema. Re-deriving the full view/component/binding JSON Schema here would double-maintain a
 * shape the TypeScript side already owns and changes independently.
 */
const val DESIGNER_MODULE = "designer"
const val VIEW_KIND = "View"

/**
 * A project's style classes (M4.2) are one resource per project:
 * `<configRoot>/designer/project/<project>/StyleClasses/style-classes.yaml`. `spec.classes` maps a class
 * name to its definition, the same `StyleClassSet` shape `@oip/hmi-runtime` `parseStyleClassSet` accepts.
 * Unlike a view document this shape is small and flat, so the gateway validates it fully here.
 * Saves use the same `metadata.version` optimistic concurrency as views (stale version -> 409).
 */
const val STYLE_CLASSES_KIND = "StyleClasses"
const val STYLE_CLASSES_RESOURCE = "style-classes"

/** Style class names: letters, digits, `_` and `-`, starting with a letter (hmi-runtime `isValidStyleClassName`). */
const val STYLE_CLASS_NAME_PATTERN = "^[A-Za-z][A-Za-z0-9_-]*$"

fun registerDesignerSchemas(registry: SchemaRegistry) {
    registry.register(DESIGNER_MODULE, VIEW_KIND, viewResourceSchema())
    registry.registerSemantic(DESIGNER_MODULE, VIEW_KIND, ::viewSemanticErrors)
    registry.register(DESIGNER_MODULE, STYLE_CLASSES_KIND, styleClassesResourceSchema())
    registry.registerSemantic(DESIGNER_MODULE, STYLE_CLASSES_KIND, HmiStyleRules::styleClassErrors)
    registry.register(DESIGNER_MODULE, SCRIPT_HOOKS_KIND, scriptHooksResourceSchema())
    registry.register(DESIGNER_MODULE, PAGE_CONFIG_KIND, pageConfigResourceSchema())
    registry.register(DESIGNER_MODULE, TRANSLATION_KIND, translationResourceSchema())
    registry.register(DESIGNER_MODULE, HTTP_SOURCE_KIND, HmiHttpFetch.schema())
}

const val TRANSLATION_KIND = "Translation"

private fun translationResourceSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("messages"),
    "properties" to mapOf(
        "locale" to mapOf("type" to "string", "maxLength" to 35),
        // More languages in one resource (HMI-005): locale tag to term to text.
        "locales" to mapOf(
            "type" to "object",
            "maxProperties" to 40,
            "propertyNames" to mapOf("pattern" to "^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$"),
            "additionalProperties" to mapOf(
                "type" to "object",
                "maxProperties" to 500,
                "propertyNames" to mapOf("maxLength" to 200),
                "additionalProperties" to mapOf("type" to "string", "maxLength" to 500),
            ),
        ),
        "messages" to mapOf(
            "type" to "object",
            "maxProperties" to 500,
            "propertyNames" to mapOf("maxLength" to 200),
            "additionalProperties" to mapOf("type" to "string", "maxLength" to 500),
        ),
    ),
)

/** A data:image logo inlined in the theme stays under 256 KiB of text. */
private const val BRAND_IMAGE_MAX_LENGTH = 262_144

/** http(s), a site-relative path (not `//host`), or a data:image source, the same sources the runtime accepts. */
private const val BRAND_IMAGE_PATTERN = "^(?:https?://|/(?!/)|data:image/(?:png|jpeg|gif|webp|svg\\+xml)[;,])"

private fun styleClassesResourceSchema(): Map<String, Any?> {
    val stateStyle = mapOf("type" to "object", "maxProperties" to 100)
    val definition = mapOf(
        "type" to "object",
        "properties" to mapOf(
            // The property values are checked by [HmiStyleRules] (P4-07); unknown properties are refused there too.
            "style" to mapOf("type" to "object", "maxProperties" to 100),
            "states" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "hover" to stateStyle,
                    "active" to stateStyle,
                    "focus" to stateStyle,
                    "disabled" to stateStyle,
                ),
            ),
        ),
    )
    return mapOf(
        "type" to "object",
        "required" to listOf("classes"),
        "properties" to mapOf(
            "classes" to mapOf(
                "type" to "object",
                "maxProperties" to 1000,
                "propertyNames" to mapOf("pattern" to STYLE_CLASS_NAME_PATTERN, "maxLength" to 100),
                "additionalProperties" to definition,
            ),
            "themes" to mapOf(
                "type" to "object",
                "maxProperties" to 32,
                "propertyNames" to mapOf("pattern" to STYLE_CLASS_NAME_PATTERN, "maxLength" to 100),
                "additionalProperties" to mapOf(
                    "type" to "object",
                    "required" to listOf("extends", "tokens"),
                    "properties" to mapOf(
                        "extends" to mapOf("type" to "string", "enum" to listOf("daylight", "nightwatch")),
                        "tokens" to mapOf("type" to "object", "maxProperties" to 32),
                        // HMP-085: the session brand bar. The runtime loads `logo` and `graphic` only from http(s),
                        // site-relative or data:image sources and refuses any other field (theme.ts brandImageSource).
                        "brand" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "name" to mapOf("type" to "string", "maxLength" to 200),
                                "logoAlt" to mapOf("type" to "string", "maxLength" to 200),
                                "logo" to mapOf("type" to "string", "maxLength" to BRAND_IMAGE_MAX_LENGTH, "pattern" to BRAND_IMAGE_PATTERN),
                                "graphic" to mapOf("type" to "string", "maxLength" to BRAND_IMAGE_MAX_LENGTH, "pattern" to BRAND_IMAGE_PATTERN),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
}

private fun viewResourceSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("path", "document"),
    "properties" to mapOf(
        "path" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 255),
        "title" to mapOf("type" to "string", "maxLength" to 255),
        "document" to mapOf("type" to "object"),
    ),
)

/**
 * Review-4 F3: a `navigation` action in `url` mode may only name a page path on this origin (`/pumps`) or an http(s)
 * URL, the same rule as the web `linkTarget` filter the link component, the menus and the runtime apply. A
 * `javascript:`, `data:` or `vbscript:` URL (any other scheme, a protocol-relative `//host`, or a URL with
 * whitespace, control characters or a backslash) is refused at save time with 422, so it never reaches a session.
 * The rest of the document stays free-form (see above); this walks it only for actions.
 */
internal fun viewSemanticErrors(spec: Map<String, Any?>): List<String> {
    val errors = ArrayList<String>()
    fun walk(node: Any?, where: String) {
        when (node) {
            is Map<*, *> -> {
                if (node["kind"] == "navigation" && node["mode"] == "url") {
                    val url = node["url"]
                    if (url !is String || !safeNavigationUrl(url)) errors += "$where.url must be a page path (/...) or an http(s) URL"
                }
                for ((key, value) in node) walk(value, "$where.$key")
            }
            is List<*> -> node.forEachIndexed { index, value -> walk(value, "$where[$index]") }
        }
    }
    walk(spec["document"], "spec.document")
    // P4-07 (HMP-093): component and container styles stay inside the style subset.
    errors += HmiStyleRules.viewErrors(spec["document"], "spec.document")
    return errors
}

/** The web `linkTarget` rule (`hmi-runtime/src/links.ts`): a same-origin page path or an http(s) URL. */
internal fun safeNavigationUrl(value: String): Boolean {
    val url = value.trim()
    if (url.isEmpty()) return false
    if (url.any { it.code <= 0x1f || it.code == 0x7f || it.isWhitespace() || it == '\\' }) return false
    if (url.startsWith("/")) return !url.startsWith("//")
    return Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)
}

/**
 * Named HMI script hooks of a project (D-5a): one resource per project,
 * `<configRoot>/designer/project/<project>/ScriptHooks/script-hooks.yaml`. `spec.hooks` maps a hook name to
 * `{source}`, the Python a script transform or an event script names by `hook`. A script transform that carries its
 * own `source` in the saved view is found there first; see [HmiScriptRunner].
 */
const val SCRIPT_HOOKS_KIND = "ScriptHooks"
const val SCRIPT_HOOKS_RESOURCE = "script-hooks"

/** Hook names: letters, digits, `_`, `-` and `.`, starting with a letter. */
const val SCRIPT_HOOK_NAME_PATTERN = "^[A-Za-z][A-Za-z0-9_.-]*$"

internal fun scriptHooksResourceSchema(): Map<String, Any?> = mapOf(
    "type" to "object",
    "required" to listOf("hooks"),
    "properties" to mapOf(
        "hooks" to mapOf(
            "type" to "object",
            "maxProperties" to 1000,
            "propertyNames" to mapOf("pattern" to SCRIPT_HOOK_NAME_PATTERN, "maxLength" to 100),
            "additionalProperties" to mapOf(
                "type" to "object",
                "required" to listOf("source"),
                "properties" to mapOf(
                    "source" to mapOf("type" to "string", "maxLength" to 100_000),
                    // SEC-017: who may run the hook (see HmiSecurity); an entry that cannot be read is unmet.
                    "security" to mapOf(
                        "type" to "object",
                        "additionalProperties" to false,
                        "properties" to mapOf(
                            "roles" to mapOf("type" to "array", "maxItems" to 100, "items" to mapOf("type" to "string", "maxLength" to 100)),
                            "levels" to mapOf("type" to "array", "maxItems" to 100, "items" to mapOf("type" to "string", "maxLength" to 400)),
                            "requireAll" to mapOf("type" to "boolean"),
                        ),
                    ),
                    // HMP-047: marks the hook as the handler of a file upload component (see HmiUploadApi).
                    "upload" to mapOf(
                        "type" to "object",
                        "additionalProperties" to false,
                        "properties" to mapOf(
                            "maxBytes" to mapOf("type" to "integer", "minimum" to 1),
                            "contentTypes" to mapOf("type" to "array", "maxItems" to 50, "items" to mapOf("type" to "string", "maxLength" to 100)),
                        ),
                    ),
                ),
            ),
        ),
    ),
)

/**
 * A project's page configuration (H-1): one resource per project,
 * `<configRoot>/designer/project/<project>/PageConfig/page-config.yaml`. `spec.pages` lists the session pages (URL,
 * title, primary view and the page's own docks), `spec.sharedDocks` the docks every page shows unless the page
 * replaces that edge, and `spec.cornerPriority` the default corner rule. These are the `PageDocument.spec`, `DockSpec`
 * and `CornerPriority` shapes `@oip/hmi-runtime` validates (`parsePage`); like a view document, a dock stays
 * free-form here. A project without this resource gets one page per view in `/hmi` (see the web `hmiProject.ts`).
 * A page maps a URL (with `:name` segments for path parameters) to the view shown in the middle of the screen, and
 * docks are views fixed to an edge of the page.
 */
const val PAGE_CONFIG_KIND = "PageConfig"
const val PAGE_CONFIG_RESOURCE = "page-config"

internal fun pageConfigResourceSchema(): Map<String, Any?> {
    val text = mapOf("type" to "string", "minLength" to 1, "maxLength" to 255)
    val corner = mapOf("type" to "string", "enum" to listOf("top-bottom", "left-right"))
    val docks = mapOf("type" to "array", "maxItems" to 4, "items" to mapOf("type" to "object"))
    val page = mapOf(
        "type" to "object",
        "required" to listOf("url", "primaryView"),
        "properties" to mapOf(
            "name" to text,
            "url" to mapOf("type" to "string", "pattern" to "^/", "maxLength" to 1000),
            "title" to mapOf("type" to "string", "maxLength" to 255),
            "primaryView" to text,
            "docks" to docks,
            "cornerPriority" to corner,
        ),
    )
    return mapOf(
        "type" to "object",
        "required" to listOf("pages"),
        "properties" to mapOf(
            "pages" to mapOf("type" to "array", "maxItems" to 1000, "items" to page),
            "sharedDocks" to docks,
            "cornerPriority" to corner,
        ),
    )
}
