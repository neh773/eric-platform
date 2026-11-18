package org.oip.gateway

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature

object Json {
    val mapper: ObjectMapper = ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .registerModules(ObjectMapper.findModules())
    fun write(v: Any?): String = mapper.writeValueAsString(v)
    @Suppress("UNCHECKED_CAST")
    fun parse(s: String): Map<String, Any?> = mapper.readValue(s, Map::class.java) as Map<String, Any?>
}
