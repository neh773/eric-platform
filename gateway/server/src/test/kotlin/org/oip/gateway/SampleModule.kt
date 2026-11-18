package org.oip.gateway

import org.oip.sdk.Module
import org.oip.sdk.ModuleContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Test module. Lifecycle events are appended under the gateway data dir, one file per gateway. */
class SampleModule : Module {
    private lateinit var id: String
    private lateinit var log: Path
    private fun bind(context: ModuleContext) {
        id = context.manifest.id
        log = context.configDir.parent.resolveSibling("module-events.log")
    }
    private fun record(event: String) {
        Files.writeString(log, "$event\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
    override fun install(context: ModuleContext) { bind(context); record("install:$id") }
    override fun restore(context: ModuleContext) { bind(context); record("restore:$id") }
    override fun start() = record("start:$id")
    override fun stop() = record("stop:$id")
    override fun uninstall() = record("uninstall:$id")
}
