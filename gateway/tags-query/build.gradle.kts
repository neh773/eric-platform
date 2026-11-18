dependencies {
    implementation(project(":gateway:tags"))
    implementation(project(":gateway:db"))
    // Postgres for the acceptance tests. The driver is already on gateway:db's runtime classpath;
    // Testcontainers is test-only here, same coordinates as gateway:db.
    testImplementation("org.testcontainers:testcontainers:2.0.5")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
