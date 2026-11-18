dependencies {
    "api"(project(":gateway:config"))
    // TagCaller is built from security Subject/SecurityZone; user tag writes go to its AuditLog (TAG-013).
    "api"(project(":gateway:security"))
    // Memory-tag persistence (TAG-016) in the ADR 0008 runtime database.
    "implementation"("org.xerial:sqlite-jdbc:3.53.4.0")
    // Security brings bcprov 1.86, which would upgrade only bcprov under GraalPy's 1.78.1 bcpkix/bcutil
    // in consumers such as gateway/scripting and break its hashlib init. Keep the BouncyCastle jars aligned
    // on 1.86, as gateway/server already does.
    constraints {
        "implementation"("org.bouncycastle:bcpkix-jdk18on:1.86")
        "implementation"("org.bouncycastle:bcutil-jdk18on:1.86")
    }
}

tasks.withType<Test> {
    maxHeapSize = "1536m"
    testLogging { showStandardStreams = true }
}

tasks.named<Test>("test") {
    filter {
        excludeTestsMatching("org.oip.tags.TagScaleTest")
    }
}

// Allocation ceiling (512 bytes per sample-handle update), measured in a JVM that has not run the rest of the suite.
tasks.register<Test>("tagScaleTest") {
    description = "TagScaleTest allocation ceiling, in a fresh JVM."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    forkEvery = 1L
    filter { includeTestsMatching("org.oip.tags.TagScaleTest") }
    useJUnitPlatform()
}

tasks.named("check") { dependsOn("tagScaleTest") }
