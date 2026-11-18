dependencies {
    "implementation"(project(":gateway:module-sdk"))
    "testImplementation"(project(":gateway:module-host"))
    "testImplementation"(project(":test-infra:sim:dnp3"))
}

tasks.withType<Test> {
    testLogging { showStandardStreams = true }
}
