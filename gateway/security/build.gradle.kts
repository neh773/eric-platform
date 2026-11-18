import java.time.Duration

dependencies {
    "api"(project(":gateway:config"))
    "implementation"("org.bouncycastle:bcprov-jdk18on:1.86")
    "implementation"("org.xerial:sqlite-jdbc:3.53.4.0")
    // SEC-007: an in-memory LDAP server (LDAPS and StartTLS listeners) for the directory user source tests, and
    // test certificates for it. Test scope only.
    "testImplementation"("com.unboundid:unboundid-ldapsdk:7.0.3")
    "testImplementation"("org.bouncycastle:bcpkix-jdk18on:1.86")
}

// Wall-clock ceilings carry @Tag("benchmark"). They stay out of the default suite, where a loaded runner can
// fail them with no code change (review-1 F14), and run in securityBenchmarkTest, which `check` (so
// `./gradlew build` in CI) runs, like the PRF floors in :gateway:server.
val benchmarkTag = "benchmark"

tasks.named<Test>("test") {
    maxHeapSize = "1g"
    useJUnitPlatform { excludeTags(benchmarkTag) }
    systemProperty("oip.test.excludedTags", benchmarkTag)
}

tasks.register<Test>("securityBenchmarkTest") {
    description = "Security hot-path timing ceilings (audit append, zone match, API-key auth, Argon2id, auth guard)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    maxHeapSize = "1g"
    // A fresh JVM per class, so the auth-guard scale test's heap is not collected during the audit clock.
    forkEvery = 1
    maxParallelForks = 1
    useJUnitPlatform { includeTags(benchmarkTag) }
    timeout.set(Duration.ofMinutes(15))
    testLogging {
        events("passed", "failed", "skipped", "standardOut")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.named("check") { dependsOn("securityBenchmarkTest") }

// The 5 ms audit-append ceiling is unchanged. This task waits until other projects' compiles and tests
// have finished, so their CPU is not on that clock. On CI a parallel scripting compile put the median
// batch at 5.349 ms while a quiet batch in the same run was 1.599 ms.
gradle.projectsEvaluated {
    val bench = tasks.named("securityBenchmarkTest").get()
    rootProject.subprojects.forEach { p ->
        if (p == project) return@forEach
        p.tasks.forEach { task ->
            if (task is Test || task.name.startsWith("compile")) bench.mustRunAfter(task)
        }
    }
    bench.mustRunAfter(tasks.named("test").get())
}
