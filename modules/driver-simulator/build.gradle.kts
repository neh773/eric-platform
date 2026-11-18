dependencies {
    "implementation"(project(":gateway:module-sdk"))
    "testImplementation"(project(":gateway:module-host"))
    "testRuntimeOnly"("org.slf4j:slf4j-simple:2.0.17")
}
