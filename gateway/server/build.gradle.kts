import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.proto
import java.time.Duration
import org.gradle.api.distribution.Distribution
import org.gradle.api.tasks.application.CreateStartScripts

plugins {
    application
    id("com.google.protobuf") version "0.9.4"
}
dependencies {
    "implementation"("io.grpc:grpc-netty-shaded:1.68.2")
    "implementation"("io.grpc:grpc-protobuf:1.68.2")
    "implementation"("io.grpc:grpc-stub:1.68.2")
    "compileOnly"("org.apache.tomcat:annotations-api:6.0.53")
    "testImplementation"("com.networknt:json-schema-validator:1.5.9")
    // DAT-016: the H2 JAR stands in for a vendor JDBC driver an administrator uploads. Test scope only.
    "testImplementation"("com.h2database:h2:2.3.232")
    // SEC-007: an in-memory LDAP directory for the user source login tests. Test scope only.
    "testImplementation"("com.unboundid:unboundid-ldapsdk:7.0.3")
    "testImplementation"("org.testcontainers:testcontainers:2.0.5")
    "testImplementation"("org.eclipse.milo:milo-sdk-server:1.1.8")
    "implementation"(project(":gateway:module-host"))
    "implementation"(project(":gateway:security"))
    "implementation"(project(":gateway:backup"))
    "implementation"(project(":gateway:tags"))
    "implementation"(project(":gateway:tags-query"))
    "implementation"(project(":gateway:alarm"))
    "implementation"(project(":gateway:opcua"))
    "implementation"(project(":gateway:historian"))
    "implementation"(project(":gateway:db"))
    "implementation"(project(":gateway:scripting"))
    "implementation"(project(":gateway:sfc"))
    "implementation"(project(":gateway:events"))
    "implementation"(project(":gateway:reporting"))
    "implementation"(project(":gateway:report-render"))
    "implementation"(project(":modules:driver-modbus"))
    "implementation"(project(":modules:driver-opcua-client"))
    "implementation"(project(":modules:driver-mqtt"))
    "implementation"(project(":modules:driver-s7"))
    "implementation"(project(":modules:driver-logix"))
    "implementation"(project(":modules:driver-udp-tcp"))
    "implementation"(project(":modules:driver-bacnet"))
    "implementation"(project(":modules:driver-dnp3"))
    "implementation"(project(":modules:driver-simulator"))
    "testImplementation"("org.testcontainers:testcontainers:2.0.5")
    "testImplementation"("org.eclipse.milo:milo-sdk-client:1.1.8")
    "testImplementation"(project(":test-infra:support"))
    "testImplementation"(project(":test-infra:sim:logix"))
    "testImplementation"(project(":test-infra:sim:s7"))
    "testImplementation"(project(":test-infra:sim:bacnet"))
    "testImplementation"(project(":test-infra:sim:dnp3"))
    "implementation"("org.xerial:sqlite-jdbc:3.53.4.0")
    "implementation"("io.javalin:javalin:6.7.0")
    "implementation"("org.bouncycastle:bcprov-jdk18on:1.86")
    "implementation"("org.bouncycastle:bcpkix-jdk18on:1.86")
    "implementation"("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.3")
    "runtimeOnly"("org.slf4j:slf4j-simple:2.0.17")
}
application { mainClass.set("org.oip.gateway.MainKt") }

// The generated Windows script puts every jar on one CLASSPATH line. That line is longer than
// cmd.exe allows, so server.bat exits before the JVM starts. A manifest classpath jar keeps the
// same jar order and leaves the Unix script unchanged.
val serverClasspathJar = tasks.register<Jar>("serverClasspathJar") {
    group = "distribution"
    description = "Manifest classpath for the Windows gateway launcher."
    archiveFileName.set("server-classpath.jar")
    val runtimeClasspath = configurations.named("runtimeClasspath")
    val mainJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    dependsOn(mainJar)
    inputs.files(runtimeClasspath)
    inputs.file(mainJar)
    doFirst {
        val names = (sequenceOf(mainJar.get().asFile) + runtimeClasspath.get().files.asSequence())
            .map { it.name }
            .distinct()
        manifest.attributes["Class-Path"] = names.joinToString(" ")
    }
}

tasks.named<CreateStartScripts>("startScripts") {
    dependsOn(serverClasspathJar)
    doLast {
        val script = windowsScript
        val updated = script.readText().replace(
            Regex("""(?m)^set CLASSPATH=.*$"""),
            Regex.escapeReplacement("set CLASSPATH=%APP_HOME%\\lib\\server-classpath.jar"),
        )
        if (!updated.contains("server-classpath.jar")) throw GradleException("Windows start script has no CLASSPATH line to shorten")
        script.writeText(updated)
    }
}

distributions.named<Distribution>("main") {
    contents {
        into("lib") {
            from(serverClasspathJar)
        }
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    plugins {
        id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.68.2" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins { id("grpc") }
        }
    }
}

sourceSets.named("main") {
    proto {
        srcDir(rootProject.file("proto"))
        include("oip/gateway/network/**/*.proto")
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(tasks.named("generateProto"))
}

tasks.named<Test>("test") {
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    dependsOn(":modules:hello-world:jar")
    val jar = project(":modules:hello-world").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(jar)
    filter {
        excludeTestsMatching("org.oip.gateway.SimulatorCommsLossTest")
        excludeTestsMatching("org.oip.gateway.SimulatorHistoryTest")
        excludeTestsMatching("org.oip.gateway.DeviceCatalogSimulatorTest")
        excludeTestsMatching("org.oip.gateway.SimulatorJournalPostgresTest")
        // Own JVM so RSS is this workload, not the rest of the suite. PRF-006 compares that RSS to 4 GB.
        excludeTestsMatching("org.oip.gateway.TagCapacityFloorTest")
        // Own JVM so the live-heap delta is this workload, not classes left by earlier tests.
        excludeTestsMatching("org.oip.gateway.TagScaleTest")
        // Own JVM with the edge heap; 75 s of 88,500 rows/s (PRF-006 local history).
        excludeTestsMatching("org.oip.gateway.LocalHistoryThroughputTest")
    }
    doFirst { systemProperty("oip.helloJar", jar.get().asFile.absolutePath) }
}

// PRF-004 / PRF-005 / PRF-006. `check` runs it, so `./gradlew build` does too.
tasks.register<Test>("tagCapacityFloorTest") {
    description = "Tag-capacity floors PRF-004, PRF-005, and PRF-006 against the ADR 0010 cutoffs."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "1536m"
    minHeapSize = "64m"
    forkEvery = 1L
    filter { includeTestsMatching("org.oip.gateway.TagCapacityFloorTest") }
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(20))
    systemProperty("oip.perfRequirements", rootProject.file("test-infra/perf/perf-requirements.json").absolutePath)
    systemProperty(
        "oip.capacity.reportDir",
        layout.buildDirectory.dir("reports/tag-capacity").get().asFile.absolutePath,
    )
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Retained-heap and per-update ceilings (1 KB/tag, 64 bytes/update). Its own JVM, same heap as the suite task.
tasks.register<Test>("tagScaleTest") {
    description = "TagScaleTest retained-heap and allocation ceilings, in a fresh JVM."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "2g"
    forkEvery = 1L
    filter { includeTestsMatching("org.oip.gateway.TagScaleTest") }
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(10))
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// PRF-006 local history: 88,500 rows/s through a live gateway's collector and embedded historian, edge heap.
tasks.register<Test>("localHistoryThroughputTest") {
    description = "PRF-006: local history keeps up with 88,500 changes per second on a live edge-profile gateway."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "2g"
    forkEvery = 1L
    filter { includeTestsMatching("org.oip.gateway.LocalHistoryThroughputTest") }
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(10))
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named("check") {
    dependsOn("tagCapacityFloorTest")
    dependsOn("tagScaleTest")
    dependsOn("localHistoryThroughputTest")
}

// Compose simulators. The build job does not start them; the simulators CI job runs this task
// (comms loss on Modbus/OPC UA, the history network cut against TimescaleDB through Toxiproxy, and the
// alarm journal on a PostgreSQL connection, DAT-015).
tasks.register<Test>("simulatorCommsTest") {
    description = "Comms-loss quality, history store-and-forward, and the OPC UA device catalog against the compose simulators."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "2g"
    filter {
        includeTestsMatching("org.oip.gateway.SimulatorCommsLossTest")
        includeTestsMatching("org.oip.gateway.SimulatorHistoryTest")
        includeTestsMatching("org.oip.gateway.DeviceCatalogSimulatorTest")
        includeTestsMatching("org.oip.gateway.SimulatorJournalPostgresTest")
    }
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(20))
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
