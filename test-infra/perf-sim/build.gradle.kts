import java.time.Duration

dependencies {
    testImplementation(project(":modules:driver-modbus"))
    testImplementation(project(":modules:driver-opcua-client"))
    testImplementation(project(":modules:driver-mqtt"))
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

// Docker acceptance stays out of `check` / `build`. The Simulator stack smoke job runs perfSimTest.
val perfSim = sourceSets.create("perfSim") {
    compileClasspath += sourceSets["test"].compileClasspath + sourceSets["test"].output
    runtimeClasspath += sourceSets["test"].runtimeClasspath + sourceSets["test"].output + output
}

tasks.register<Test>("perfSimTest") {
    description = "PRF-012: CI-sized high-point simulator on a Docker bridge, using the project drivers."
    group = "verification"
    testClassesDirs = perfSim.output.classesDirs
    classpath = perfSim.runtimeClasspath
    useJUnitPlatform()
    timeout.set(Duration.ofMinutes(15))
    outputs.upToDateWhen { false }
    systemProperty("perfSim.root", rootProject.projectDir.absolutePath)
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
