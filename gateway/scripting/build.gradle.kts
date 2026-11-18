dependencies {
    implementation(project(":gateway:tags"))
    implementation(project(":gateway:db"))
    implementation(project(":gateway:sfc"))
    implementation(project(":gateway:events"))
    implementation("org.graalvm.polyglot:polyglot:24.2.2")
    implementation("org.graalvm.polyglot:python-community:24.2.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testRuntimeOnly("com.zaxxer:HikariCP:6.2.1")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
}

tasks.withType<Test> {
    maxHeapSize = "2g"
    testLogging { showStandardStreams = true }
}
