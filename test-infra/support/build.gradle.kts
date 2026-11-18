import java.time.Duration

dependencies {
    implementation("org.testcontainers:testcontainers:2.0.5")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    testImplementation("org.eclipse.milo:milo-sdk-client:1.1.4")
    testImplementation("org.postgresql:postgresql:42.7.7")
    testImplementation(project(":gateway:db"))
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("smoke")
    }
}

tasks.register<Test>("smokeTest") {
    description = "Start the simulator compose stack and smoke-test each service."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("smoke")
    }
    timeout.set(Duration.ofMinutes(25))
    maxParallelForks = 1
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
