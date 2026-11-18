dependencies { "api"(project(":gateway:module-sdk")) }
// The packaged hello-world jar is loaded through ModuleClassLoader; it is NOT on this project's test classpath (review-0 F5).
tasks.named<Test>("test") {
    dependsOn(":modules:hello-world:jar")
    val jar = project(":modules:hello-world").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(jar)
    doFirst { systemProperty("oip.helloJar", jar.get().asFile.absolutePath) }
}
