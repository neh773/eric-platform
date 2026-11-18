// M5.3 report rendering: report definition `layout` + C-9a run result -> PDF bytes.
// Apache PDFBox (Apache-2.0); charts are drawn as PDF vector paths, no AWT on the render path.
dependencies {
    // C-9a contract classes (ReportDefinition, ReportResult) and the scheduler's ReportResultSink hook.
    "api"(project(":gateway:reporting"))
    "implementation"("org.apache.pdfbox:pdfbox:3.0.8")
    "implementation"("com.fasterxml.jackson.core:jackson-databind:2.18.3")
}

tasks.named<Test>("test") {
    // Golden comparison rasterises pages with PDFBox's PDFRenderer (Java2D); keep it headless.
    systemProperty("java.awt.headless", "true")
    // -Poip.updateGoldens=true rewrites src/test/resources/golden/ instead of comparing.
    systemProperty("oip.updateGoldens", (findProperty("oip.updateGoldens") ?: "false").toString())
    systemProperty("oip.goldenDir", file("src/test/resources/golden").absolutePath)
    systemProperty("oip.goldenOutDir", layout.buildDirectory.dir("golden-actual").get().asFile.absolutePath)
}
