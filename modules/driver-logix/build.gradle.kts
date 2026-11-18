dependencies {
    "implementation"(project(":gateway:module-sdk"))
    "testImplementation"(project(":gateway:module-host"))
    "testImplementation"(project(":test-infra:sim:logix"))
}

tasks.withType<Test> {
    testLogging { showStandardStreams = true }
}
