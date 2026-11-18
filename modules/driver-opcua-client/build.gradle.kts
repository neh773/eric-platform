dependencies {
    "implementation"(project(":gateway:module-sdk"))
    // Eclipse Milo 1.1.8 is EPL-2.0 (SPDX EPL-2.0). Verified from
    // https://github.com/eclipse-milo/milo/blob/v1.1.8/LICENSE.md — the 1.x line is no longer
    // dual-licensed Apache-2.0. EPL-2.0 is not GPL/AGPL; this repo already accepts EPL-2.0 (JUnit).
    "implementation"("org.eclipse.milo:milo-sdk-client:1.1.8")
    "testImplementation"(project(":gateway:module-host"))
    "testImplementation"("org.eclipse.milo:milo-sdk-server:1.1.8")
    "testRuntimeOnly"("org.slf4j:slf4j-simple:2.0.17")
}

tasks.withType<Test> {
    testLogging { showStandardStreams = true }
}
