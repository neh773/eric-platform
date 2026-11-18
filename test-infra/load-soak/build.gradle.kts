import java.time.Duration

dependencies {
    testImplementation(project(":gateway:server"))
    testImplementation(project(":gateway:config"))
    testImplementation(project(":gateway:tags"))
    testImplementation(project(":gateway:historian"))
    testImplementation(project(":gateway:alarm"))
    testImplementation(project(":gateway:db"))
    testImplementation(project(":gateway:security"))
    testImplementation(project(":test-infra:support"))
    testImplementation("org.postgresql:postgresql:42.7.7")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

// The container soaks are a separate source set so `./gradlew build` (the CI build job) does not
// start a container. The Load bench job runs `historianSoakTest` (PRF-008) and `fullLoadTest` (PRF-003)
// at the CI size from test-infra/perf/prf-008.json and prf-003.json. `-PloadSoakSize=full` runs the
// floor size. See test-infra/load-soak/README.md.
val loadSoak = sourceSets.create("loadSoak") {
    compileClasspath += sourceSets["test"].compileClasspath
    runtimeClasspath += sourceSets["test"].runtimeClasspath + output
}

fun Test.soak(scenario: String, testClass: String, minutes: Long) {
    group = "verification"
    testClassesDirs = loadSoak.output.classesDirs
    classpath = loadSoak.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching(testClass) }
    outputs.upToDateWhen { false }
    maxHeapSize = (project.findProperty("loadSoakHeap") as String?) ?: "4g"
    val size = (project.findProperty("loadSoakSize") as String?) ?: System.getenv("LOAD_SOAK_SIZE") ?: "ci"
    // A full-size soak runs for hours; the CI size has a short ceiling so a hang fails the job.
    timeout.set(if (size == "full") Duration.ofHours(6) else Duration.ofMinutes(minutes))
    systemProperty("loadSoak.size", size)
    systemProperty("loadSoak.root", rootDir.absolutePath)
    systemProperty("loadSoak.scenario", scenario)
    systemProperty(
        "prf.simImage",
        (project.findProperty("prfSimImage") as String?) ?: System.getenv("PRF_SIM_IMAGE") ?: "oip-bench-points:ci",
    )
    (project.findProperty("loadSoakReport") as String?)?.let { systemProperty("loadSoak.report", it) }
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.register<Test>("historianSoakTest") {
    description = "PRF-008 historian soak with a database network cut, on a Docker bridge."
    soak("PRF-008", "org.oip.loadsoak.HistorianSoakTest", 12)
}

tasks.register<Test>("fullLoadTest") {
    description = "PRF-003 full load: drivers, historian, alarms, and sessions against the bench simulator."
    soak("PRF-003", "org.oip.loadsoak.FullLoadTest", 12)
}
