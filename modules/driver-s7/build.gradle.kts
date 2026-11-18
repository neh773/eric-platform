dependencies {
    "implementation"(project(":gateway:module-sdk"))
    "testImplementation"(project(":gateway:module-host"))
    "testImplementation"(project(":test-infra:sim:s7"))
}

tasks.withType<Test> {
    testLogging { showStandardStreams = true }
}
