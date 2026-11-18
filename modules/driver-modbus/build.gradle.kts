dependencies {
    "implementation"(project(":gateway:module-sdk"))
    "testImplementation"(project(":gateway:module-host"))
    // In-process slave only. The driver speaks Modbus itself (public spec); digitalpetri is not on the runtime classpath.
    // digitalpetri Modbus 2.1.6 is EPL-2.0 (the 1.x line was Apache-2.0 and has no RTU-over-TCP server).
    "testImplementation"("com.digitalpetri.modbus:modbus-tcp:2.1.6")
    "testRuntimeOnly"("org.slf4j:slf4j-simple:2.0.17")
}

tasks.withType<Test> {
    testLogging { showStandardStreams = true }
}
