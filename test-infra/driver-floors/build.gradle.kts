import java.time.Duration

dependencies {
    testImplementation(project(":modules:driver-modbus"))
    testImplementation(project(":modules:driver-opcua-client"))
}

// The docker floor test is a separate source set so `./gradlew build` (the CI build job)
// does not start a container. The Load bench job runs `driverFloorTest`.
val driverFloor = sourceSets.create("driverFloor") {
    compileClasspath += sourceSets["test"].compileClasspath
    runtimeClasspath += sourceSets["test"].runtimeClasspath + output
}

tasks.register<Test>("driverFloorTest") {
    description = "PRF-009 and PRF-010 against the bench simulator on a Docker bridge."
    group = "verification"
    testClassesDirs = driverFloor.output.classesDirs
    classpath = driverFloor.runtimeClasspath
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(15))
    outputs.upToDateWhen { false }
    systemProperty(
        "prf009.seconds",
        (project.findProperty("prf009Seconds") as String?) ?: System.getenv("PRF009_SECONDS") ?: "4",
    )
    systemProperty(
        "prf010.holdSeconds",
        (project.findProperty("prf010HoldSeconds") as String?) ?: System.getenv("PRF010_HOLD_SECONDS") ?: "2",
    )
    systemProperty(
        "prf.simImage",
        (project.findProperty("prfSimImage") as String?) ?: System.getenv("PRF_SIM_IMAGE") ?: "oip-bench-points:ci",
    )
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
