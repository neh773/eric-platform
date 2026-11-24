plugins { kotlin("jvm") version "2.0.21" apply false }
// A release build takes its version from the tag (ERIC_BUILD_TAG=v0.1.1-beta, set by the release workflow) or
// -Peric.version; any other build is a SNAPSHOT. The gateway reports it on /api/v1/health from the jar manifest.
val ericVersion: String = providers.gradleProperty("eric.version").orNull
    ?: providers.environmentVariable("ERIC_BUILD_TAG").orNull?.takeIf { Regex("v[0-9].*").matches(it) }?.removePrefix("v")
    ?: "0.1.1-SNAPSHOT"
subprojects {
    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    group = "org.oip"; version = ericVersion
    tasks.withType<Jar>().configureEach { manifest { attributes("Implementation-Version" to ericVersion) } }
    repositories { mavenCentral() }
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> { jvmToolchain(21) }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.junit.jupiter:junit-jupiter-params")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging { events("passed", "failed", "skipped") }
        // The installed gateway already listens on the production defaults (4840 and 8060).
        // Tests bind an ephemeral port instead. Production defaults stay unchanged.
        if (System.getProperty("os.name").lowercase().contains("windows")) {
            environment("ERIC_TEST_EPHEMERAL_LISTENERS", "true")
        }
    }
}

// Prints group:artifact:version of every resolved runtime + test dependency (used by scripts/check_licenses.py).
tasks.register("listDependencies") {
    doLast {
        val seen = sortedSetOf<String>()
        subprojects.forEach { p ->
            listOf("runtimeClasspath" to "runtime", "testRuntimeClasspath" to "test").forEach { (c, scope) ->
                p.configurations.findByName(c)?.resolvedConfiguration?.resolvedArtifacts?.forEach { a ->
                    val id = a.moduleVersion.id
                    if (id.group != "org.oip") seen += "$scope ${id.group}:${id.name}:${id.version}"
                }
            }
        }
        seen.forEach { println("DEP $it") }
    }
}
