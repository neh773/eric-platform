dependencies {
    "implementation"(project(":gateway:module-sdk"))
    "testImplementation"(project(":gateway:module-host"))
}

// Module package (ADR 0006/SPEC §3): zip with module.yaml and the module jar at the root.
val modulePackage by tasks.registering(Zip::class) {
    archiveFileName.set("hello-world.oipmodule.zip")
    destinationDirectory.set(layout.buildDirectory.dir("module-package"))
    from("src/main/resources/module.yaml")
    from(tasks.named("jar"))
}
tasks.named("assemble") { dependsOn(modulePackage) }
