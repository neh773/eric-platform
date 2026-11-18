dependencies {
    implementation(project(":gateway:tags"))
    implementation("org.apache.kafka:kafka-clients:3.9.1") {
        // lz4-java 1.8.0 has an out-of-bounds read advisory (CVE-2025-12183). The maintained fork keeps
        // the same package and API; the exclude travels with this dependency to every consumer.
        exclude(group = "org.lz4", module = "lz4-java")
    }
    implementation("at.yawk.lz4:lz4-java:1.10.4")
}
