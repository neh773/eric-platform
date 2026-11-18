dependencies {
    "api"(project(":gateway:tags"))
    "implementation"("org.xerial:sqlite-jdbc:3.53.4.0")
    "implementation"("org.postgresql:postgresql:42.7.7")
    // Review-3 F10: PostgresHistoryProviderTest starts TimescaleDB in the build job.
    "testImplementation"("org.testcontainers:testcontainers:2.0.5")
}
