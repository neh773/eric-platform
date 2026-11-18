dependencies {
    "implementation"(project(":gateway:module-sdk"))
    "testImplementation"(project(":gateway:module-host"))
    "testImplementation"(project(":test-infra:sim:bacnet"))
}

tasks.withType<Test> {
    testLogging { showStandardStreams = true }
}
