plugins { application }
dependencies {
    "implementation"(project(":gateway:server"))
    "implementation"(project(":gateway:backup"))
    "testImplementation"(project(":gateway:server"))
    "testImplementation"(project(":gateway:security"))
    "testImplementation"(project(":gateway:config"))
}
application { mainClass.set("org.oip.cli.MainKt") }
tasks.named<Test>("test") {
    dependsOn(":modules:hello-world:jar")
    val jar = project(":modules:hello-world").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.file(jar)
    doFirst { systemProperty("oip.helloJar", jar.get().asFile.absolutePath) }
}
