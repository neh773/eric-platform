package org.oip.config

/** RFC 7386 JSON Merge Patch. */
object JsonMergePatch {
    fun apply(target: Any?, patch: Any?): Any? {
        if (patch !is Map<*, *>) return patch
        val out = linkedMapOf<String, Any?>()
        (target as? Map<*, *>)?.forEach { (k, v) -> out[k.toString()] = v }
        patch.forEach { (k, v) -> val key = k.toString(); if (v == null) out.remove(key) else out[key] = apply(out[key], v) }
        return out
    }
}
