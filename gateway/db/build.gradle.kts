dependencies {
    api(project(":gateway:config"))
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.postgresql:postgresql:42.7.7")
    // Official SQL Server JDBC driver. The published POM names the license MIT; optional Azure libraries are not pulled in.
    implementation("com.microsoft.sqlserver:mssql-jdbc:12.10.2.jre11")
    // MariaDB and MySQL (DAT-003, DAT-019, DAT-022): the MySQL and MariaDB connectors are GPL/LGPL, so the
    // BSD-licensed Drizzle JDBC client speaks the MySQL wire protocol instead. Its POM names "Simplified BSD".
    implementation("org.drizzle.jdbc:drizzle-jdbc:1.4")
    implementation("org.slf4j:slf4j-api:2.0.17")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    // DAT-016, DAT-023, DAT-017: H2 in its Oracle and DB2 compatibility modes stands in for those servers, and its JAR is
    // handed to the driver upload path as if it were a vendor driver. Test scope only; it is never shipped.
    testImplementation("com.h2database:h2:2.3.232")
    // C-17: a throwaway PostgreSQL for the stored-procedure tests when Docker is available (CI).
    testImplementation("org.testcontainers:testcontainers:2.0.5")
}
