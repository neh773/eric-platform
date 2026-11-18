dependencies {
    "implementation"(project(":gateway:module-sdk"))
    "testImplementation"(project(":gateway:module-host"))
}

tasks.withType<Test> {
    testLogging { showStandardStreams = true }
}
