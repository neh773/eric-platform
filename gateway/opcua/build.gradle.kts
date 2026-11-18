dependencies {
    "api"(project(":gateway:tags"))
    // Eclipse Milo 1.1.8 is EPL-2.0 (ADR 0009). The server SDK is the same line the OPC UA
    // client driver already links. It is not dual-licensed Apache-2.0.
    "implementation"("org.eclipse.milo:milo-sdk-server:1.1.8")
    "runtimeOnly"("org.slf4j:slf4j-simple:2.0.17")
}
