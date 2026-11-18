package org.oip.hello

import org.oip.config.SchemaRegistry.Companion.MODULE_CONFIG_KIND
import org.oip.sdk.Module
import org.oip.sdk.ModuleConfig
import org.oip.sdk.ModuleContext
import org.oip.sdk.ModuleLog
import org.oip.sdk.ModuleServices

/**
 * Example module. On install it stores a `ModuleConfig/default` resource whose spec is the defaults of the
 * manifest `configSchema`; on start it reads the effective greeting from the ConfigStore and "greets".
 */
class HelloWorldModule : Module {
    private lateinit var config: ModuleConfig
    private lateinit var services: ModuleServices
    var lastGreeting: String? = null
        private set

    override fun install(context: ModuleContext) {
        config = context.config; services = context.services
        config.put(MODULE_CONFIG_KIND, NAME, config.defaults())
    }

    override fun restore(context: ModuleContext) { config = context.config; services = context.services }

    override fun start() {
        val spec = config.resolve(MODULE_CONFIG_KIND, NAME)?.spec ?: emptyMap()
        val greeting = config.withDefaults(spec)["greeting"] as String
        services.get(ModuleLog::class.java).info(greeting) // requires manifest permission log.write
        lastGreeting = greeting
    }

    override fun stop() { services.get(ModuleLog::class.java).info("goodbye") }
    override fun uninstall() { lastGreeting = null }

    companion object { const val NAME = "default" }
}
