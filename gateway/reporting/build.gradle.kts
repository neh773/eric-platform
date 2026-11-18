dependencies {
    "api"(project(":gateway:config"))
    "api"(project(":gateway:db"))
    "api"(project(":gateway:historian"))
    "implementation"("org.xerial:sqlite-jdbc:3.53.4.0")
}
