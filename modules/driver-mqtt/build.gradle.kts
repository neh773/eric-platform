dependencies {
    "implementation"(project(":gateway:module-sdk"))
    "testImplementation"(project(":gateway:module-host"))
    // Broker container matches test-infra (eclipse-mosquitto 2.0.22). The driver speaks MQTT itself.
    "testImplementation"("org.testcontainers:testcontainers:2.0.5")
    // Independent check of the hand-written Sparkplug payload codec against the protobuf runtime.
    "testImplementation"("com.google.protobuf:protobuf-java:3.25.5")
    "testRuntimeOnly"("org.slf4j:slf4j-simple:2.0.17")
}

tasks.withType<Test> {
    testLogging { showStandardStreams = true }
    systemProperty("oip.repoRoot", rootProject.projectDir.absolutePath)
}
