package org.oip.gateway

/**
 * The HMI style subset (HMP-093, P4-07), checked when a view or a style class set is saved. It mirrors the web
 * `hmi-runtime/src/styleSubset.ts`: a style is an object of known properties, each with a typed value. Colors are hex,
 * a color keyword, `rgb()`/`rgba()`/`hsl()`/`hsla()` with numbers only, or `var(--name)`; lengths are pixel numbers.
 * A property outside the subset, or a value that could load a resource (`url()`), run code (`expression()`) or break
 * out of a declaration, is refused with 422 so it never reaches a session.
 *
 * Doc source: FEATURES HMP-093.
 */
internal object HmiStyleRules {
    private sealed interface Kind
    private object Color : Kind
    private object Font : Kind
    private data class Length(val min: Double? = null) : Kind
    private data class Bounded(val min: Double, val max: Double) : Kind
    private data class Keyword(val values: List<String>) : Kind

    private val properties: Map<String, Pair<String, Kind>> = linkedMapOf(
        "color" to ("color" to Color),
        "background" to ("background-color" to Color),
        "fontSize" to ("font-size" to Length(1.0)),
        "fontWeight" to ("font-weight" to Bounded(1.0, 1000.0)),
        "fontStyle" to ("font-style" to Keyword(listOf("normal", "italic"))),
        "fontFamily" to ("font-family" to Font),
        "textAlign" to ("text-align" to Keyword(listOf("left", "center", "right", "justify"))),
        "textDecoration" to ("text-decoration" to Keyword(listOf("none", "underline", "line-through", "overline"))),
        "textTransform" to ("text-transform" to Keyword(listOf("none", "uppercase", "lowercase", "capitalize"))),
        "lineHeight" to ("line-height" to Bounded(0.0, 10.0)),
        "letterSpacing" to ("letter-spacing" to Length()),
        "padding" to ("padding" to Length(0.0)),
        "margin" to ("margin" to Length()),
        "borderWidth" to ("border-width" to Length(0.0)),
        "borderStyle" to ("border-style" to Keyword(listOf("none", "solid", "dashed", "dotted", "double"))),
        "borderColor" to ("border-color" to Color),
        "borderRadius" to ("border-radius" to Length(0.0)),
        "opacity" to ("opacity" to Bounded(0.0, 1.0)),
        "overflow" to ("overflow" to Keyword(listOf("visible", "hidden", "auto", "scroll"))),
        "cursor" to ("cursor" to Keyword(listOf("auto", "default", "pointer", "not-allowed", "text", "crosshair", "move"))),
    )

    private val hex = Regex("^#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    private val keyword = Regex("^[A-Za-z]{3,30}$")
    private val functionColor = Regex("^(?:rgba?|hsla?)\\(\\s*[0-9.,%\\s/+-]*(?:deg[0-9.,%\\s/+-]*)?\\)$", RegexOption.IGNORE_CASE)
    private val themeVar = Regex("^var\\(\\s*--[A-Za-z0-9_-]{1,64}\\s*\\)$")
    private val font = Regex("^[A-Za-z0-9 ,'\"-]{1,200}$")
    private val forbidden = Regex("[;{}<>\\\\@]|/\\*|!important|url\\s*\\(|expression\\s*\\(|image-set\\s*\\(|javascript:", RegexOption.IGNORE_CASE)

    fun isColor(value: String): Boolean {
        val text = value.trim()
        if (forbidden.containsMatchIn(text)) return false
        return hex.matches(text) || keyword.matches(text) || functionColor.matches(text) || themeVar.matches(text)
    }

    /** Problems with one style object, each prefixed with [where]; empty when it is in the subset. */
    fun errors(style: Any?, where: String): List<String> {
        if (style == null) return emptyList()
        if (style !is Map<*, *>) return listOf("$where must be an object")
        val out = ArrayList<String>()
        for ((rawKey, value) in style) {
            val key = rawKey.toString()
            val (css, kind) = properties[key] ?: run {
                out += "$where.$key is not a supported style property"
                null
            } ?: continue
            val problem = when (kind) {
                Color -> if (value is String && isColor(value)) null else "$css must be a color (hex, a color name, rgb(), hsl() or var(--theme-variable))"
                Font -> if (value is String && !forbidden.containsMatchIn(value) && (font.matches(value.trim()) || themeVar.matches(value.trim()))) null
                    else "$css must be font names or var(--theme-variable)"
                is Keyword -> if (value is String && value.trim() in kind.values) null else "$css must be one of ${kind.values.joinToString(", ")}"
                is Length -> {
                    val number = (value as? Number)?.toDouble()
                    when {
                        number == null || !number.isFinite() -> "$css must be a number"
                        kind.min != null && number < kind.min -> "$css must be at least ${kind.min.toInt()}px"
                        else -> null
                    }
                }
                is Bounded -> {
                    val number = (value as? Number)?.toDouble()
                    if (number == null || !number.isFinite() || number < kind.min || number > kind.max) "$css must be a number from ${fmt(kind.min)} to ${fmt(kind.max)}" else null
                }
            }
            if (problem != null) out += "$where.$key: $problem"
        }
        return out
    }

    private fun fmt(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else value.toString()

    /** Every component or container `style` in a view document. */
    fun viewErrors(document: Any?, where: String): List<String> {
        val out = ArrayList<String>()
        fun walk(node: Any?, at: String, depth: Int) {
            if (depth > 200) return
            when (node) {
                is Map<*, *> -> {
                    val kind = node["kind"]
                    if ((kind == "component" || kind == "container") && node.containsKey("style")) out += errors(node["style"], "$at.style")
                    for ((key, value) in node) if (key != "style") walk(value, "$at.$key", depth + 1)
                }
                is List<*> -> node.forEachIndexed { index, value -> walk(value, "$at[$index]", depth + 1) }
            }
        }
        walk(document, where, 0)
        return out
    }

    private val states = listOf("hover", "active", "focus", "disabled")
    private val themeExtends = setOf("daylight", "nightwatch")
    private val radius = Regex("^\\d+(?:\\.\\d+)?px$")

    /** Every class of a style class set (`spec.classes`), plus project themes on the same resource. */
    fun styleClassErrors(spec: Map<String, Any?>): List<String> {
        val classes = spec["classes"] as? Map<*, *> ?: return themeErrors(spec)
        val out = classes.flatMap { (name, definition) ->
            val body = definition as? Map<*, *>
            val style = body?.get("style")
            val found = errors(style, "spec.classes.$name.style").toMutableList()
            val rawStates = body?.get("states") as? Map<*, *>
            if (rawStates != null) {
                for ((state, value) in rawStates) {
                    if (state !in states) found += "spec.classes.$name.states.$state is not a style state"
                    else found += errors(value, "spec.classes.$name.states.$state")
                }
            }
            found
        }
        return out + themeErrors(spec)
    }

    private fun themeErrors(spec: Map<String, Any?>): List<String> {
        val themes = spec["themes"] as? Map<*, *> ?: return emptyList()
        if (themes.size > 32) return listOf("spec.themes has at most 32 themes")
        val out = ArrayList<String>()
        for ((name, raw) in themes) {
            if (name == "daylight" || name == "nightwatch") out += "spec.themes.$name cannot replace a built-in theme"
            val body = raw as? Map<*, *>
            val base = body?.get("extends")
            if (base !in themeExtends) out += "spec.themes.$name.extends must be daylight or nightwatch"
            val tokens = body?.get("tokens") as? Map<*, *>
            if (tokens == null) {
                out += "spec.themes.$name.tokens must be an object"
                continue
            }
            for ((key, value) in tokens) {
                val text = value as? String
                val ok = text != null && when {
                    key.toString().startsWith("color-") -> isColor(text)
                    key.toString().startsWith("font-") -> font.matches(text) && !forbidden.containsMatchIn(text)
                    key == "radius" -> radius.matches(text)
                    else -> false
                }
                if (!ok) out += "spec.themes.$name.tokens.$key is not a theme token"
            }
        }
        return out
    }
}
