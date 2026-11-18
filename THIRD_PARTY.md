# Third-party dependencies

Every dependency (runtime, test, and build tooling) must be listed here. `scripts/check_licenses.py`
fails CI if a resolved dependency is missing from this table, or if any `runtime` dependency is GPL/AGPL
without a classpath exception. EPL-2.0 and EPL-1.0 libraries, including Eclipse Milo, are combined
under the additional permission in LICENSE-EXCEPTION.md. That permission does not relicense those jars.

The PLC preview does not add a Gradle dependency. `plc/eric-plc` links the crates `libc`, `libloading`, `serde`, `serde_json`, and `toml` (MIT or Apache-2.0). RuSTy v1.0.8 and `iec61131std` are LGPLv3, downloaded at build time, and are not linked into the JVM or into `eric-plc`. The Windows gateway installer ships `plc.exe`, `iec61131std.dll`, and `iec61131std.lib` as separate files, with their license texts and a written source offer. Their source is not in this repository.

| Artifact | Version | License | Scope | Used by |
|---|---|---|---|---|
| org.jetbrains.kotlin:kotlin-stdlib | 2.0.21 | Apache-2.0 | runtime | all JVM modules |
| org.jetbrains:annotations | 13.0 | Apache-2.0 | runtime | transitive via kotlin-stdlib |
| org.yaml:snakeyaml | 2.3 | Apache-2.0 | runtime | gateway/module-sdk (manifest parsing); transitive via jackson-dataformat-yaml |
| com.fasterxml.jackson.core:jackson-databind | 2.18.3 | Apache-2.0 | runtime | gateway/config (resource (de)serialization) |
| com.fasterxml.jackson.core:jackson-core | 2.18.3 | Apache-2.0 | runtime | transitive via jackson-databind |
| com.fasterxml.jackson.core:jackson-annotations | 2.18.3 | Apache-2.0 | runtime | transitive via jackson-databind |
| com.fasterxml.jackson.dataformat:jackson-dataformat-yaml | 2.18.3 | Apache-2.0 | runtime | gateway/config (YAML resources) |
| com.networknt:json-schema-validator | 1.5.9 | Apache-2.0 | runtime | gateway/config (JSON Schema 2020-12 validation) |
| com.ethlo.time:itu | 1.14.0 | Apache-2.0 | runtime | transitive via json-schema-validator |
| org.slf4j:slf4j-api | 2.0.17 | MIT | runtime | transitive via json-schema-validator |
| io.javalin:javalin | 6.7.0 | Apache-2.0 | runtime | gateway/server (HTTP, ADR 0007) |
| org.eclipse.jetty.toolchain:jetty-jakarta-servlet-api | 5.0.2 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin |
| org.eclipse.jetty.websocket:websocket-core-common | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty.websocket:websocket-core-server | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty.websocket:websocket-jetty-api | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty.websocket:websocket-jetty-common | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty.websocket:websocket-jetty-server | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty.websocket:websocket-servlet | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty:jetty-http | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty:jetty-io | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty:jetty-security | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty:jetty-server | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty:jetty-servlet | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty:jetty-util | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty:jetty-webapp | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.eclipse.jetty:jetty-xml | 11.0.25 | EPL-2.0 OR Apache-2.0 | runtime | transitive via javalin (Jetty 11, used under Apache-2.0) |
| org.jetbrains.kotlin:kotlin-stdlib-jdk7 | 1.9.25 | Apache-2.0 | runtime | transitive via javalin |
| org.jetbrains.kotlin:kotlin-stdlib-jdk8 | 1.9.25 | Apache-2.0 | runtime | transitive via javalin |
| org.slf4j:slf4j-simple | 2.0.17 | MIT | runtime | gateway/server (library log binding; gateway structured logs are in-process) |
| org.bouncycastle:bcprov-jdk18on | 1.86 | MIT | runtime | gateway/security (Argon2id password hashing), gateway/backup (passphrase-encrypted backups), and Eclipse Milo (application certificates). Jar LICENSE.md is MIT |
| org.bouncycastle:bcpkix-jdk18on | 1.86 | MIT | runtime | gateway/server (HTTPS certificates) and transitive via Milo |
| org.bouncycastle:bcutil-jdk18on | 1.86 | MIT | runtime | transitive via Milo |
| org.xerial:sqlite-jdbc | 3.53.4.0 | Apache-2.0 | runtime | gateway/backup (embedded runtime DB), gateway/security (audit log and encrypted secrets), gateway/server (sessions and lockout), gateway/alarm (alarm journal), gateway/db (SQLite database connections), gateway/tags (memory-tag values), and gateway/historian (tag history samples). The SQLite engine itself is public domain and is bundled inside this jar (ADR 0008). |
| com.zaxxer:HikariCP | 6.2.1 | Apache-2.0 | runtime | gateway/db JDBC connection pool |
| org.graalvm.polyglot:polyglot | 24.2.2 | UPL-1.0 | runtime | gateway/scripting (GraalPy engine, ADR 0012) |
| org.graalvm.python:python-community | 24.2.2 | UPL-1.0 OR MIT OR PSF-2.0 | runtime | gateway/scripting GraalPy community distribution. POM lists UPL, MIT, and the Python Software Foundation License |
| org.graalvm.python:python-language | 24.2.2 | UPL-1.0 OR MIT OR PSF-2.0 | runtime | transitive via python-community |
| org.graalvm.python:python-resources | 24.2.2 | UPL-1.0 OR MIT OR PSF-2.0 | runtime | transitive via python-community (Python standard library resources) |
| org.graalvm.sdk:collections | 24.2.2 | UPL-1.0 | runtime | transitive via GraalPy |
| org.graalvm.sdk:jniutils | 24.2.2 | UPL-1.0 | runtime | transitive via GraalPy |
| org.graalvm.sdk:nativeimage | 24.2.2 | UPL-1.0 | runtime | transitive via GraalPy |
| org.graalvm.sdk:word | 24.2.2 | UPL-1.0 | runtime | transitive via GraalPy |
| org.graalvm.truffle:truffle-api | 24.2.2 | UPL-1.0 | runtime | transitive via GraalPy |
| org.graalvm.truffle:truffle-compiler | 24.2.2 | UPL-1.0 | runtime | transitive via GraalPy |
| org.graalvm.truffle:truffle-runtime | 24.2.2 | UPL-1.0 | runtime | transitive via GraalPy |
| org.graalvm.truffle:truffle-nfi | 24.2.2 | UPL-1.0 | runtime | transitive via GraalPy. Native access is off in the script context |
| org.graalvm.truffle:truffle-nfi-libffi | 24.2.2 | UPL-1.0 | runtime | transitive via GraalPy. Native access is off in the script context |
| org.graalvm.regex:regex | 24.2.2 | UPL-1.0 | runtime | transitive via GraalPy |
| org.graalvm.llvm:llvm-api | 24.2.2 | BSD-3-Clause | runtime | transitive via GraalPy |
| org.graalvm.shadowed:icu4j | 24.2.2 | Unicode/ICU License | runtime | transitive via GraalPy (shaded ICU) |
| org.graalvm.shadowed:json | 24.2.2 | UPL-1.0 | runtime | transitive via GraalPy |
| org.graalvm.shadowed:xz | 24.2.2 | UPL-1.0 | runtime | transitive via GraalPy |
| org.graalvm.tools:profiler-tool | 24.2.2 | GPL-2-with-classpath-exception | runtime | transitive via GraalPy. Not called by the gateway. POM is GPL version 2 with the classpath exception |
| org.bouncycastle:bcpkix-jdk18on | 1.78.1 | Bouncy Castle Licence | runtime | transitive via GraalPy 24.2.2. POM name is the Bouncy Castle Licence (permissive). Distinct from the 1.86 MIT jars |
| org.bouncycastle:bcprov-jdk18on | 1.78.1 | Bouncy Castle Licence | runtime | transitive via GraalPy 24.2.2 |
| org.bouncycastle:bcutil-jdk18on | 1.78.1 | Bouncy Castle Licence | runtime | transitive via GraalPy 24.2.2 |
| org.testcontainers:testcontainers | 2.0.5 | MIT | runtime | test-infra/support (ComposeContainer) |
| com.github.docker-java:docker-java-api | 3.7.1 | Apache-2.0 | runtime | transitive via testcontainers |
| com.github.docker-java:docker-java-transport | 3.7.1 | Apache-2.0 | runtime | transitive via testcontainers |
| com.github.docker-java:docker-java-transport-zerodep | 3.7.1 | Apache-2.0 | runtime | transitive via testcontainers |
| org.rnorth.duct-tape:duct-tape | 1.0.8 | MIT | runtime | transitive via testcontainers |
| org.slf4j:slf4j-api | 1.7.36 | MIT | runtime | transitive via testcontainers (support module classpath) |
| org.slf4j:slf4j-api | 2.0.18 | MIT | test | transitive via Eclipse Milo 1.1.4 smoke client and digitalpetri modbus test slave |
| org.slf4j:slf4j-api | 2.0.20 | MIT | runtime | transitive via Eclipse Milo |
| org.jetbrains:annotations | 17.0.0 | Apache-2.0 | runtime | transitive via testcontainers |
| com.fasterxml.jackson.core:jackson-annotations | 2.20 | Apache-2.0 | runtime | transitive via testcontainers (support resolves above 2.18.3) |
| commons-codec:commons-codec | 1.19.0 | Apache-2.0 | runtime | transitive via testcontainers |
| commons-io:commons-io | 2.20.0 | Apache-2.0 | runtime | transitive via testcontainers |
| org.apache.commons:commons-compress | 1.28.0 | Apache-2.0 | runtime | transitive via testcontainers |
| org.apache.commons:commons-lang3 | 3.18.0 | Apache-2.0 | runtime | transitive via testcontainers |
| net.java.dev.jna:jna | 5.18.1 | Apache-2.0 OR LGPL-2.1-or-later | runtime | transitive via testcontainers (used under Apache-2.0) |
| org.eclipse.milo:milo-sdk-client | 1.1.4 | EPL-2.0 | test | test-infra/support smoke client |
| org.eclipse.milo:milo-sdk-core | 1.1.4 | EPL-2.0 | test | transitive via milo-sdk-client 1.1.4 |
| org.eclipse.milo:milo-stack-core | 1.1.4 | EPL-2.0 | test | transitive via milo-sdk-client 1.1.4 |
| org.eclipse.milo:milo-transport | 1.1.4 | EPL-2.0 | test | transitive via milo-sdk-client 1.1.4 |
| org.eclipse.milo:milo-sdk-client | 1.1.8 | EPL-2.0 | runtime | modules/driver-opcua-client (OPC UA client). 1.x is EPL-2.0 only, not dual Apache-2.0. Combination with ERIC is permitted by LICENSE-EXCEPTION.md. |
| org.eclipse.milo:milo-sdk-core | 1.1.8 | EPL-2.0 | runtime | transitive via milo-sdk-client |
| org.eclipse.milo:milo-stack-core | 1.1.8 | EPL-2.0 | runtime | transitive via milo-sdk-client |
| org.eclipse.milo:milo-transport | 1.1.8 | EPL-2.0 | runtime | transitive via milo-sdk-client |
| org.eclipse.milo:milo-sdk-server | 1.1.8 | EPL-2.0 | runtime | gateway OPC UA server (`gateway/opcua`) and the in-process OPC UA simulator. EPL-2.0 only. Combination with ERIC is permitted by LICENSE-EXCEPTION.md. |
| org.jspecify:jspecify | 1.0.0 | Apache-2.0 | test | transitive via milo 1.1.4 |
| org.jspecify:jspecify | 1.0.1 | Apache-2.0 | runtime | transitive via Milo 1.1.8 |
| com.digitalpetri.netty:netty-channel-fsm | 1.0.2 | EPL-2.0 | runtime | transitive via Milo transport |
| com.digitalpetri.fsm:strict-machine | 1.0.0 | EPL-2.0 | runtime | transitive via netty-channel-fsm |
| io.netty:netty-buffer | 4.1.133.Final | Apache-2.0 | test | transitive via milo 1.1.4 |
| io.netty:netty-codec | 4.1.133.Final | Apache-2.0 | test | transitive via milo 1.1.4 |
| io.netty:netty-common | 4.1.133.Final | Apache-2.0 | test | transitive via milo 1.1.4 |
| io.netty:netty-handler | 4.1.133.Final | Apache-2.0 | test | transitive via milo 1.1.4 |
| io.netty:netty-resolver | 4.1.133.Final | Apache-2.0 | test | transitive via milo 1.1.4 |
| io.netty:netty-transport | 4.1.133.Final | Apache-2.0 | test | transitive via milo 1.1.4 |
| io.netty:netty-transport-native-unix-common | 4.1.133.Final | Apache-2.0 | test | transitive via milo 1.1.4 |
| io.netty:netty-buffer | 4.1.138.Final | Apache-2.0 | runtime | transitive via Milo 1.1.8 |
| io.netty:netty-codec | 4.1.138.Final | Apache-2.0 | runtime | transitive via Milo 1.1.8 |
| io.netty:netty-common | 4.1.138.Final | Apache-2.0 | runtime | transitive via Milo 1.1.8 |
| io.netty:netty-handler | 4.1.138.Final | Apache-2.0 | runtime | transitive via Milo 1.1.8 |
| io.netty:netty-resolver | 4.1.138.Final | Apache-2.0 | runtime | transitive via Milo 1.1.8 |
| io.netty:netty-transport | 4.1.138.Final | Apache-2.0 | runtime | transitive via Milo 1.1.8 |
| io.netty:netty-transport-native-unix-common | 4.1.138.Final | Apache-2.0 | runtime | transitive via Milo 1.1.8 |
| io.netty:netty-buffer | 4.1.136.Final | Apache-2.0 | test | transitive via digitalpetri modbus 2.1.6 |
| io.netty:netty-codec | 4.1.136.Final | Apache-2.0 | test | transitive via digitalpetri modbus 2.1.6 |
| io.netty:netty-common | 4.1.136.Final | Apache-2.0 | test | transitive via digitalpetri modbus 2.1.6 |
| io.netty:netty-handler | 4.1.136.Final | Apache-2.0 | test | transitive via digitalpetri modbus 2.1.6 |
| io.netty:netty-resolver | 4.1.136.Final | Apache-2.0 | test | transitive via digitalpetri modbus 2.1.6 |
| io.netty:netty-transport | 4.1.136.Final | Apache-2.0 | test | transitive via digitalpetri modbus 2.1.6 |
| io.netty:netty-transport-native-unix-common | 4.1.136.Final | Apache-2.0 | test | transitive via digitalpetri modbus 2.1.6 |
| com.digitalpetri.modbus:modbus | 2.1.6 | EPL-2.0 | test | in-process Modbus slave (not linked into the driver) |
| com.digitalpetri.modbus:modbus-tcp | 2.1.6 | EPL-2.0 | test | in-process Modbus TCP and RTU-over-TCP slave |
| org.bouncycastle:bcpkix-jdk18on | 1.84 | MIT | test | transitive via milo 1.1.4 |
| org.bouncycastle:bcprov-jdk18on | 1.84 | MIT | test | transitive via milo 1.1.4 |
| org.bouncycastle:bcutil-jdk18on | 1.84 | MIT | test | transitive via milo 1.1.4 |
| org.checkerframework:checker-qual | 3.49.3 | MIT | runtime | transitive via the PostgreSQL JDBC driver and milo 1.1.4 |
| org.postgresql:postgresql | 42.7.7 | BSD-2-Clause | runtime | gateway/db Postgres connections, gateway/historian SQL history provider (Postgres/TimescaleDB), and the test-infra/support smoke client |
| com.microsoft.sqlserver:mssql-jdbc | 12.10.2.jre11 | MIT | runtime | gateway/db SQL Server connections. The published POM names the license MIT, and the jar's license file is the MIT grant. Optional libraries named by the POM are not on the runtime classpath. |
| org.drizzle.jdbc:drizzle-jdbc | 1.4 | BSD-2-Clause | runtime | gateway/db MariaDB and MySQL connections over the MySQL wire protocol. The published POM names "Simplified BSD". The MySQL and MariaDB connectors are GPL/LGPL and are not used. |
| com.h2database:h2 | 2.3.232 | MPL-2.0 OR EPL-1.0 | test | gateway/db and gateway/server tests: H2 in Oracle and DB2 compatibility modes stands in for those servers, and its JAR is uploaded through the driver upload path as a stand-in vendor driver (DAT-016). Test scope only, unmodified, never shipped. |
| com.unboundid:unboundid-ldapsdk | 7.0.3 | Apache-2.0 (also offered under GPL-2.0, LGPL-2.1 and its own free-use licence; used under Apache-2.0) | test | gateway/security and gateway/server tests: an in-memory LDAP directory with LDAPS and StartTLS listeners stands in for an LDAP or Active Directory server (SEC-007, SEC-021, SEC-025). Test scope only, unmodified, never shipped. |
| org.apache.pdfbox:pdfbox | 3.0.8 | Apache-2.0 | runtime | gateway/report-render (report PDF output; charts drawn as PDF vector paths) |
| org.apache.pdfbox:pdfbox-io | 3.0.8 | Apache-2.0 | runtime | transitive via pdfbox |
| org.apache.pdfbox:fontbox | 3.0.8 | Apache-2.0 | runtime | transitive via pdfbox (TrueType parsing and subsetting of the bundled DejaVu fonts) |
| commons-logging:commons-logging | 1.4.0 | Apache-2.0 | runtime | transitive via pdfbox |
| org.junit.jupiter:junit-jupiter | 5.11.3 | EPL-2.0 | test | all |
| org.junit.jupiter:junit-jupiter-api | 5.11.3 | EPL-2.0 | test | all |
| org.junit.jupiter:junit-jupiter-engine | 5.11.3 | EPL-2.0 | test | all |
| org.junit.jupiter:junit-jupiter-params | 5.11.3 | EPL-2.0 | test | all |
| org.junit.platform:junit-platform-commons | 1.11.3 | EPL-2.0 | test | all |
| org.junit.platform:junit-platform-engine | 1.11.3 | EPL-2.0 | test | all |
| org.junit.platform:junit-platform-launcher | 1.11.3 | EPL-2.0 | test | all |
| org.opentest4j:opentest4j | 1.3.0 | Apache-2.0 | test | transitive via JUnit |
| org.apiguardian:apiguardian-api | 1.1.2 | Apache-2.0 | test | transitive (compile-only) via JUnit |
| Gradle (wrapper) | 8.10.2 | Apache-2.0 | build | build tool |
| org.jetbrains.kotlin.jvm (Gradle plugin) | 2.0.21 | Apache-2.0 | build | build tool |
| Eclipse Temurin JDK | 21 | GPL-2.0-with-classpath-exception | build | JDK runtime (not linked/redistributed) |
| org.apache.kafka:kafka-clients | 3.9.1 | Apache-2.0 | runtime | gateway/events (event-stream Kafka source and handler) |
| com.github.luben:zstd-jni | 1.5.6-4 | BSD-2-Clause | runtime | transitive via kafka-clients (zstd record compression) |
| at.yawk.lz4:lz4-java | 1.10.4 | Apache-2.0 | runtime | gateway/events: replaces org.lz4:lz4-java 1.8.0 from kafka-clients (lz4 record compression; CVE-2025-12183 fixed) |
| org.xerial.snappy:snappy-java | 1.1.10.5 | Apache-2.0 | runtime | transitive via kafka-clients (snappy record compression) |

## Planned (not yet resolved)
- None. sqlite-jdbc (ADR 0008) is a resolved runtime dependency of `gateway/backup`, `gateway/security`, `gateway/server`, `gateway/alarm`, `gateway/db`, `gateway/tags`, and `gateway/historian`.

## Bundled fonts
- `gateway/report-render/src/main/resources/org/oip/report/render/fonts/DejaVuSans.ttf` and `DejaVuSans-Bold.ttf`: DejaVu Sans 2.37 (https://dejavu-fonts.github.io/), Bitstream Vera Fonts license (permissive, MIT-style; the font may not be sold by itself and modified copies must be renamed) with DejaVu changes in the public domain. Embedded (subset) in every report PDF so output does not depend on system fonts. License text: `LICENSE-DejaVu.txt` next to the fonts. Scope `runtime`.

## Vendored test data
- `gateway/server/src/test/resources/oas-3.1-schema.json`: OpenAPI 3.1 JSON Schema (https://spec.openapis.org/oas/3.1/schema/2022-10-07), Apache-2.0, OpenAPI Initiative. Test-only (validates `/api/docs`).

## Web workspace (pnpm)

<!-- web-third-party:start -->

Direct and transitive packages installed under `web/node_modules`.
Scope `runtime` is pulled in by the HMI bundle; `dev` is build or test tooling.

| Artifact | Version | License | Scope | Used by |
|---|---|---|---|---|
| @adobe/css-tools | 4.5.0 | MIT | dev | web |
| @babel/code-frame | 7.29.7 | MIT | dev | web |
| @babel/compat-data | 7.29.7 | MIT | dev | web |
| @babel/core | 7.29.7 | MIT | dev | web |
| @babel/generator | 7.29.8 | MIT | dev | web |
| @babel/helper-compilation-targets | 7.29.7 | MIT | dev | web |
| @babel/helper-globals | 7.29.7 | MIT | dev | web |
| @babel/helper-module-imports | 7.29.7 | MIT | dev | web |
| @babel/helper-module-transforms | 7.29.7 | MIT | dev | web |
| @babel/helper-plugin-utils | 7.29.7 | MIT | dev | web |
| @babel/helper-string-parser | 7.29.11 | MIT | dev | web |
| @babel/helper-validator-identifier | 7.29.7 | MIT | dev | web |
| @babel/helper-validator-option | 7.29.7 | MIT | dev | web |
| @babel/helpers | 7.29.10 | MIT | dev | web |
| @babel/parser | 7.29.11 | MIT | dev | web |
| @babel/plugin-transform-react-jsx-self | 7.29.7 | MIT | dev | web |
| @babel/plugin-transform-react-jsx-source | 7.29.7 | MIT | dev | web |
| @babel/runtime | 7.29.10 | MIT | dev | web |
| @babel/template | 7.29.7 | MIT | dev | web |
| @babel/traverse | 7.29.10 | MIT | dev | web |
| @babel/types | 7.29.11 | MIT | dev | web |
| @codemirror/commands | 6.11.1 | MIT | runtime | web |
| @codemirror/language | 6.13.1 | MIT | runtime | web |
| @codemirror/lint | 6.9.8 | MIT | runtime | web |
| @codemirror/state | 6.7.6 | MIT | runtime | web |
| @codemirror/streamparser | 6.0.0 | MIT | runtime | web |
| @codemirror/view | 6.43.15 | MIT | runtime | web |
| @esbuild/linux-x64 | 0.25.12 | MIT | dev | web |
| @eslint-community/eslint-utils | 4.10.1 | MIT | dev | web |
| @eslint-community/regexpp | 4.12.2 | MIT | dev | web |
| @eslint/config-array | 0.21.2 | Apache-2.0 | dev | web |
| @eslint/config-helpers | 0.4.2 | Apache-2.0 | dev | web |
| @eslint/core | 0.17.0 | Apache-2.0 | dev | web |
| @eslint/eslintrc | 3.3.7 | MIT | dev | web |
| @eslint/js | 9.39.1 | MIT | dev | web |
| @eslint/object-schema | 2.1.7 | Apache-2.0 | dev | web |
| @eslint/plugin-kit | 0.4.1 | Apache-2.0 | dev | web |
| @humanfs/core | 0.19.2 | Apache-2.0 | dev | web |
| @humanfs/node | 0.16.8 | Apache-2.0 | dev | web |
| @humanfs/types | 0.15.0 | Apache-2.0 | dev | web |
| @humanwhocodes/module-importer | 1.0.1 | Apache-2.0 | dev | web |
| @humanwhocodes/retry | 0.4.3 | Apache-2.0 | dev | web |
| @jridgewell/gen-mapping | 0.3.13 | MIT | dev | web |
| @jridgewell/remapping | 2.3.5 | MIT | dev | web |
| @jridgewell/resolve-uri | 3.1.2 | MIT | dev | web |
| @jridgewell/sourcemap-codec | 1.6.0 | MIT | dev | web |
| @jridgewell/trace-mapping | 0.3.31 | MIT | dev | web |
| @lezer/common | 1.5.5 | MIT | runtime | web |
| @lezer/highlight | 1.2.5 | MIT | runtime | web |
| @lezer/lr | 1.4.12 | MIT | runtime | web |
| @marijn/find-cluster-break | 1.0.4 | MIT | runtime | web |
| @napi-rs/lzma-linux-x64-gnu | 1.5.1 | MIT | dev | web |
| @nodelib/fs.scandir | 2.1.5 | MIT | dev | web |
| @nodelib/fs.stat | 2.0.5 | MIT | dev | web |
| @nodelib/fs.walk | 1.2.8 | MIT | dev | web |
| @playwright/test | 1.56.1 | Apache-2.0 | dev | web |
| @rolldown/pluginutils | 1.0.0-beta.43 | MIT | dev | web |
| @rollup/rollup-linux-x64-gnu | 4.65.0 | MIT | dev | web |
| @testing-library/dom | 10.4.2 | MIT | dev | web |
| @testing-library/jest-dom | 6.9.1 | MIT | dev | web |
| @testing-library/react | 16.3.0 | MIT | dev | web |
| @types/aria-query | 5.0.4 | MIT | dev | web |
| @types/babel__core | 7.20.5 | MIT | dev | web |
| @types/babel__generator | 7.27.0 | MIT | dev | web |
| @types/babel__template | 7.4.4 | MIT | dev | web |
| @types/babel__traverse | 7.28.0 | MIT | dev | web |
| @types/chai | 5.2.3 | MIT | dev | web |
| @types/deep-eql | 4.0.2 | MIT | dev | web |
| @types/estree | 1.0.9 | MIT | dev | web |
| @types/geojson | 7946.0.16 | MIT | dev | web |
| @types/json-schema | 7.0.15 | MIT | dev | web |
| @types/leaflet | 1.9.21 | MIT | dev | web |
| @types/node | 20.19.43 | MIT | dev | web |
| @types/node | 22.19.1 | MIT | dev | web |
| @types/react | 19.2.2 | MIT | dev | web |
| @types/react-dom | 19.2.2 | MIT | dev | web |
| @types/whatwg-mimetype | 3.0.2 | MIT | dev | web |
| @typescript-eslint/eslint-plugin | 8.46.2 | MIT | dev | web |
| @typescript-eslint/parser | 8.46.2 | MIT | dev | web |
| @typescript-eslint/project-service | 8.46.2 | MIT | dev | web |
| @typescript-eslint/scope-manager | 8.46.2 | MIT | dev | web |
| @typescript-eslint/tsconfig-utils | 8.46.2 | MIT | dev | web |
| @typescript-eslint/type-utils | 8.46.2 | MIT | dev | web |
| @typescript-eslint/types | 8.46.2 | MIT | dev | web |
| @typescript-eslint/typescript-estree | 8.46.2 | MIT | dev | web |
| @typescript-eslint/utils | 8.46.2 | MIT | dev | web |
| @typescript-eslint/visitor-keys | 8.46.2 | MIT | dev | web |
| @vitejs/plugin-react | 5.1.0 | MIT | dev | web |
| @vitest/expect | 3.2.4 | MIT | dev | web |
| @vitest/mocker | 3.2.4 | MIT | dev | web |
| @vitest/pretty-format | 3.2.4 | MIT | dev | web |
| @vitest/pretty-format | 3.2.7 | MIT | dev | web |
| @vitest/runner | 3.2.4 | MIT | dev | web |
| @vitest/snapshot | 3.2.4 | MIT | dev | web |
| @vitest/spy | 3.2.4 | MIT | dev | web |
| @vitest/utils | 3.2.4 | MIT | dev | web |
| acorn | 8.19.0 | MIT | dev | web |
| acorn-jsx | 5.3.2 | MIT | dev | web |
| ajv | 6.15.0 | MIT | dev | web |
| ansi-regex | 5.0.1 | MIT | dev | web |
| ansi-styles | 4.3.0 | MIT | dev | web |
| ansi-styles | 5.2.0 | MIT | dev | web |
| argparse | 2.0.1 | Python-2.0 | dev | web |
| aria-query | 5.3.0 | Apache-2.0 | dev | web |
| aria-query | 5.3.2 | Apache-2.0 | dev | web |
| assertion-error | 2.0.1 | MIT | dev | web |
| balanced-match | 1.0.2 | MIT | dev | web |
| baseline-browser-mapping | 2.11.33 | Apache-2.0 | dev | web |
| brace-expansion | 1.1.21 | MIT | dev | web |
| brace-expansion | 2.1.7 | MIT | dev | web |
| braces | 3.0.3 | MIT | dev | web |
| browserslist | 4.29.4 | MIT | dev | web |
| cac | 6.7.14 | MIT | dev | web |
| callsites | 3.1.0 | MIT | dev | web |
| caniuse-lite | 1.0.30001819 | CC-BY-4.0 | dev | web |
| chai | 5.3.3 | MIT | dev | web |
| chalk | 4.1.2 | MIT | dev | web |
| check-error | 2.1.3 | MIT | dev | web |
| color-convert | 2.0.1 | MIT | dev | web |
| color-name | 1.1.4 | MIT | dev | web |
| concat-map | 0.0.1 | MIT | dev | web |
| convert-source-map | 2.0.0 | MIT | dev | web |
| crelt | 1.0.7 | MIT | runtime | web |
| cross-spawn | 7.0.6 | MIT | dev | web |
| css.escape | 1.5.1 | MIT | dev | web |
| csstype | 3.2.3 | MIT | dev | web |
| debug | 4.4.3 | MIT | dev | web |
| deep-eql | 5.0.2 | MIT | dev | web |
| deep-is | 0.1.4 | MIT | dev | web |
| dequal | 2.0.3 | MIT | dev | web |
| dom-accessibility-api | 0.5.16 | MIT | dev | web |
| dom-accessibility-api | 0.6.3 | MIT | dev | web |
| electron-to-chromium | 1.5.464 | ISC | dev | web |
| es-module-lexer | 1.7.0 | MIT | dev | web |
| esbuild | 0.25.12 | MIT | dev | web |
| escalade | 3.2.0 | MIT | dev | web |
| escape-string-regexp | 4.0.0 | MIT | dev | web |
| eslint | 9.39.1 | MIT | dev | web |
| eslint-scope | 8.4.0 | BSD-2-Clause | dev | web |
| eslint-visitor-keys | 3.4.3 | Apache-2.0 | dev | web |
| eslint-visitor-keys | 4.2.1 | Apache-2.0 | dev | web |
| espree | 10.4.0 | BSD-2-Clause | dev | web |
| esquery | 1.7.0 | BSD-3-Clause | dev | web |
| esrecurse | 4.3.0 | BSD-2-Clause | dev | web |
| estraverse | 5.3.0 | BSD-2-Clause | dev | web |
| estree-walker | 3.0.3 | MIT | dev | web |
| esutils | 2.0.3 | BSD-2-Clause | dev | web |
| expect-type | 1.4.0 | Apache-2.0 | dev | web |
| fast-deep-equal | 3.1.3 | MIT | dev | web |
| fast-glob | 3.3.3 | MIT | dev | web |
| fast-json-stable-stringify | 2.1.0 | MIT | dev | web |
| fast-levenshtein | 2.0.6 | MIT | dev | web |
| fastq | 1.20.3 | ISC | dev | web |
| fdir | 6.5.0 | MIT | dev | web |
| file-entry-cache | 8.0.0 | MIT | dev | web |
| fill-range | 7.1.1 | MIT | dev | web |
| find-up | 5.0.0 | MIT | dev | web |
| flat-cache | 4.0.1 | MIT | dev | web |
| flatted | 3.4.4 | ISC | dev | web |
| gensync | 1.0.0-beta.2 | MIT | dev | web |
| glob-parent | 5.1.2 | ISC | dev | web |
| glob-parent | 6.0.2 | ISC | dev | web |
| globals | 14.0.0 | MIT | dev | web |
| graphemer | 1.4.0 | MIT | dev | web |
| happy-dom | 20.0.10 | MIT | dev | web |
| has-flag | 4.0.0 | MIT | dev | web |
| ignore | 5.3.2 | MIT | dev | web |
| ignore | 7.0.14 | MIT | dev | web |
| import-fresh | 3.3.1 | MIT | dev | web |
| imurmurhash | 0.1.4 | MIT | dev | web |
| indent-string | 4.0.0 | MIT | dev | web |
| is-extglob | 2.1.1 | MIT | dev | web |
| is-glob | 4.0.3 | MIT | dev | web |
| is-number | 7.0.0 | MIT | dev | web |
| isexe | 2.0.0 | ISC | dev | web |
| js-tokens | 4.0.0 | MIT | dev | web |
| js-tokens | 9.0.1 | MIT | dev | web |
| js-yaml | 4.3.2 | MIT | dev | web |
| jsesc | 3.1.0 | MIT | dev | web |
| json-buffer | 3.0.1 | MIT | dev | web |
| json-schema-traverse | 0.4.1 | MIT | dev | web |
| json-stable-stringify-without-jsonify | 1.0.1 | MIT | dev | web |
| json5 | 2.2.3 | MIT | dev | web |
| keyv | 4.5.4 | MIT | dev | web |
| leaflet | 1.9.4 | BSD-2-Clause | runtime | web |
| levn | 0.4.1 | MIT | dev | web |
| locate-path | 6.0.0 | MIT | dev | web |
| lodash.merge | 4.6.2 | MIT | dev | web |
| loupe | 3.2.1 | MIT | dev | web |
| lru-cache | 5.1.1 | ISC | dev | web |
| lz-string | 1.5.0 | MIT | dev | web |
| magic-string | 0.30.21 | MIT | dev | web |
| merge2 | 1.4.1 | MIT | dev | web |
| micromatch | 4.0.8 | MIT | dev | web |
| min-indent | 1.0.1 | MIT | dev | web |
| minimatch | 3.1.5 | ISC | dev | web |
| minimatch | 9.0.9 | ISC | dev | web |
| ms | 2.1.3 | MIT | dev | web |
| nanoid | 3.3.20 | MIT | dev | web |
| natural-compare | 1.4.0 | MIT | dev | web |
| node-releases | 2.0.60 | MIT | dev | web |
| optionator | 0.9.4 | MIT | dev | web |
| p-limit | 3.1.0 | MIT | dev | web |
| p-locate | 5.0.0 | MIT | dev | web |
| parent-module | 1.0.1 | MIT | dev | web |
| path-exists | 4.0.0 | MIT | dev | web |
| path-key | 3.1.1 | MIT | dev | web |
| pathe | 2.0.3 | MIT | dev | web |
| pathval | 2.0.1 | MIT | dev | web |
| picocolors | 1.1.1 | ISC | dev | web |
| picomatch | 2.3.2 | MIT | dev | web |
| picomatch | 4.0.7 | MIT | dev | web |
| playwright | 1.56.1 | Apache-2.0 | dev | web |
| playwright-core | 1.56.1 | Apache-2.0 | dev | web |
| postcss | 8.5.31 | MIT | dev | web |
| prelude-ls | 1.2.1 | MIT | dev | web |
| pretty-format | 27.5.1 | MIT | dev | web |
| punycode | 2.3.1 | MIT | dev | web |
| queue-microtask | 1.2.3 | MIT | dev | web |
| react | 19.2.0 | MIT | runtime | web |
| react-dom | 19.2.0 | MIT | runtime | web |
| react-is | 17.0.2 | MIT | dev | web |
| react-refresh | 0.18.0 | MIT | dev | web |
| redent | 3.0.0 | MIT | dev | web |
| resolve-from | 4.0.0 | MIT | dev | web |
| reusify | 1.1.0 | MIT | dev | web |
| rollup | 4.65.0 | MIT | dev | web |
| run-parallel | 1.2.0 | MIT | dev | web |
| scheduler | 0.27.0 | MIT | runtime | web |
| semver | 6.3.1 | ISC | dev | web |
| semver | 7.8.5 | ISC | dev | web |
| shebang-command | 2.0.0 | MIT | dev | web |
| shebang-regex | 3.0.0 | MIT | dev | web |
| siginfo | 2.0.0 | ISC | dev | web |
| source-map-js | 1.2.2 | BSD-3-Clause | dev | web |
| stackback | 0.0.2 | MIT | dev | web |
| std-env | 3.10.0 | MIT | dev | web |
| strip-indent | 3.0.0 | MIT | dev | web |
| strip-json-comments | 3.1.1 | MIT | dev | web |
| strip-literal | 3.1.0 | MIT | dev | web |
| style-mod | 4.1.4 | MIT | runtime | web |
| supports-color | 7.2.0 | MIT | dev | web |
| tinybench | 2.9.0 | MIT | dev | web |
| tinyexec | 0.3.2 | MIT | dev | web |
| tinyglobby | 0.2.17 | MIT | dev | web |
| tinypool | 1.1.1 | MIT | dev | web |
| tinyrainbow | 2.0.0 | MIT | dev | web |
| tinyspy | 4.0.6 | MIT | dev | web |
| to-regex-range | 5.0.1 | MIT | dev | web |
| ts-api-utils | 2.5.0 | MIT | dev | web |
| type-check | 0.4.0 | MIT | dev | web |
| typescript | 5.9.3 | Apache-2.0 | dev | web |
| typescript-eslint | 8.46.2 | MIT | dev | web |
| undici-types | 6.21.0 | MIT | dev | web |
| update-browserslist-db | 1.3.4 | MIT | dev | web |
| uri-js | 4.4.1 | BSD-2-Clause | dev | web |
| vite | 6.4.1 | MIT | dev | web |
| vite-node | 3.2.4 | MIT | dev | web |
| vitest | 3.2.4 | MIT | dev | web |
| w3c-keyname | 2.2.8 | MIT | runtime | web |
| whatwg-mimetype | 3.0.0 | MIT | dev | web |
| which | 2.0.2 | ISC | dev | web |
| why-is-node-running | 2.3.0 | MIT | dev | web |
| word-wrap | 1.2.5 | MIT | dev | web |
| yallist | 3.1.1 | ISC | dev | web |
| yocto-queue | 0.1.0 | MIT | dev | web |

<!-- web-third-party:end -->

Toolchain used to build `web/` (not npm packages): Node.js 22 (MIT), pnpm 10.33.4 (MIT). `caniuse-lite` is CC-BY-4.0 data used by the dev browserslist toolchain; this entry is the attribution.

## Simulator stack (test-infra)

Packages and images used only by the test simulators. Scope `test`: none of this is linked into the gateway.
npm licenses come from `test-infra/sim/opcua/package-lock.json` (lockfileVersion 3), 147 packages under `node-opcua@2.187.16`. `dequeue@1.0.5` is BSD-2-Clause and `humanize@0.0.9` is MIT from their LICENSE files (the registry record has no `license` field). `precond@0.2.3` is MIT from its source header and the GitHub license (MIT).
Eight of those packages are already listed in the web table at the same version and license, so they are not repeated: ansi-styles 4.3.0, chalk 4.1.2, color-convert 2.0.1, color-name 1.1.4, fast-deep-equal 3.1.3, has-flag 4.0.0, semver 7.8.5, supports-color 7.2.0.
No npm or PyPI dependency in this set is GPL or AGPL. Alpine bases (Mosquitto, Mailpit, TimescaleDB) include BusyBox, which is GPL-2.0-only, and the Debian slim bases (Python, Node) include GPL-2.0 and GPL-3.0 OS utilities. Those operating-system packages stay inside the test containers and are not distributed with the gateway.

| Artifact | Version | License | Scope | Used by |
|---|---|---|---|---|
| eclipse-mosquitto | 2.0.22 | EPL-2.0 OR BSD-3-Clause | test | test-infra image sha256:09b62ff32c2e7b64d4b431b9a89236073289b68db273de2b089edeb9b0d92b5c (Alpine 3.23 base) |
| timescale/timescaledb | 2.31.0-pg16-oss | Apache-2.0 AND PostgreSQL | test | test-infra image sha256:1bfcf994089b9c97b4ddfa07f2ff9eec430709715e419d9a6957d1a4b4614cd1 (Apache OSS build, Alpine 3.23 base; PostgreSQL License for the server) |
| axllent/mailpit | v1.32.0 | MIT | test | test-infra image sha256:0790692be935ea4420d085694f7985b1f692ca6a8771ab0e0bd8ba75ac180498 (Alpine base) |
| ghcr.io/shopify/toxiproxy | 2.13.0 | MIT | test | test-infra image sha256:5d3dae09bc185dcc5ff6cf43abf6062a4a720aff0c4306a471e773acdcd107aa |
| python | 3.12-slim-bookworm | PSF-2.0 | test | test-infra base image sha256:6a0e06f5ba0b373d051aabd3fffb9b873f27f9a2a3a14bcbe37dee6f0e3bbc70 (CPython; Debian bookworm slim). Also `test-infra/perf-sim`, stdlib only, same digest |
| node | 22-bookworm-slim | MIT | test | test-infra base image sha256:de170b39439eb0e5a3444ad7d60b015614a381e10fecae2e30ec9e735122f38d (Node.js; Debian bookworm slim) |
| paho-mqtt | 2.1.0 | EPL-2.0 OR BSD-3-Clause | test | test-infra/sim/sparkplug (no transitive Python dependencies) |
| @leichtgewicht/ip-codec | 2.0.5 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/asn1-asym-key | 2.11.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/asn1-cms | 2.11.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/asn1-csr | 2.11.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/asn1-ecc | 2.11.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/asn1-pfx | 2.11.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/asn1-pkcs8 | 2.11.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/asn1-pkcs9 | 2.11.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/asn1-rsa | 2.11.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/asn1-schema | 2.11.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/asn1-x509 | 2.11.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/asn1-x509-attr | 2.11.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/asn1-x509-post-quantum | 2.11.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/json-schema | 1.1.12 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/utils | 2.0.3 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/webcrypto | 1.7.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @peculiar/x509 | 2.2.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @ster5/global-mutex | 3.3.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @types/dns-packet | 5.6.5 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @types/multicast-dns | 7.2.4 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @types/node | 26.6.5 | MIT | test | test-infra/sim/opcua (node-opcua) |
| @types/semver | 7.8.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| array-back | 6.2.3 | MIT | test | test-infra/sim/opcua (node-opcua) |
| array-flatten | 2.1.2 | MIT | test | test-infra/sim/opcua (node-opcua) |
| asn1 | 0.2.6 | MIT | test | test-infra/sim/opcua (node-opcua) |
| asn1js | 3.1.0 | BSD-3-Clause | test | test-infra/sim/opcua (node-opcua) |
| assert-plus | 1.0.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| backoff | 2.5.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| bcrypt-pbkdf | 1.0.2 | BSD-3-Clause | test | test-infra/sim/opcua (node-opcua) |
| byline | 5.0.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| chalk | 6.0.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| chalk-template | 0.4.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| chokidar | 4.0.3 | MIT | test | test-infra/sim/opcua (node-opcua) |
| command-line-args | 6.0.2 | MIT | test | test-infra/sim/opcua (node-opcua) |
| command-line-usage | 7.0.4 | MIT | test | test-infra/sim/opcua (node-opcua) |
| dashdash | 1.14.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| dequeue | 1.0.5 | BSD-2-Clause | test | test-infra/sim/opcua (node-opcua) |
| dns-equal | 1.0.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| dns-packet | 5.6.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| ecc-jsbn | 0.1.2 | MIT | test | test-infra/sim/opcua (node-opcua) |
| env-paths | 4.0.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| find-replace | 5.0.2 | MIT | test | test-infra/sim/opcua (node-opcua) |
| getpass | 0.1.7 | MIT | test | test-infra/sim/opcua (node-opcua) |
| hexy | 0.4.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| humanize | 0.0.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| is-safe-filename | 0.1.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| jsbn | 0.1.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| lodash.camelcase | 4.3.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| lodash.partition | 4.6.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| lodash.sortby | 4.7.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| multicast-dns | 7.2.5 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-address-space | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-address-space-base | 2.187.14 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-address-space-for-conformance-testing | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-address-space-store | 2.187.14 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-aggregates | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-alarm-condition | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-assert | 2.186.7 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-basic-types | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-binary-stream | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-buffer-utils | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-certificate-manager | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-chunkmanager | 2.187.8 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-client | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-client-dynamic-extension-object | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-client-proxy | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-common | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-constants | 2.185.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-crypto | 6.2.3 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-data-access | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-data-model | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-data-value | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-date-time | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-debug | 2.186.7 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-enum | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-extension-object | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-factory | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-guid | 2.184.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-hostname | 2.187.6 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-json | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-nodeid | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-nodeset-ua | 2.187.14 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-nodesets | 2.187.4 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-numeric-range | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-object-registry | 2.186.7 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-packet-analyzer | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-packet-assembler | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-pki | 7.1.4 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-pseudo-session | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-schemas | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-secure-channel | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-server | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-server-discovery | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-browse | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-call | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-discovery | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-endpoints | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-filter | 2.187.14 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-history | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-node-management | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-query | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-read | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-register-node | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-secure-channel | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-session | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-subscription | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-translate-browse-path | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-service-write | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-status-code | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-transport | 2.187.8 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-types | 2.187.9 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-uanodeset-json | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-utils | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-variant | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-vendor-diagnostic | 2.187.16 | MIT | test | test-infra/sim/opcua (node-opcua) |
| node-opcua-xml2json | 2.187.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| pend | 1.2.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| precond | 0.2.3 | MIT | test | test-infra/sim/opcua (node-opcua) |
| pvtsutils | 1.3.6 | MIT | test | test-infra/sim/opcua (node-opcua) |
| pvutils | 1.2.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| readdirp | 4.1.2 | MIT | test | test-infra/sim/opcua (node-opcua) |
| reflect-metadata | 0.2.2 | Apache-2.0 | test | test-infra/sim/opcua (node-opcua) |
| safer-buffer | 2.1.2 | MIT | test | test-infra/sim/opcua (node-opcua) |
| sshpk | 1.18.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| sterfive-bonjour-service | 1.1.4 | MIT | test | test-infra/sim/opcua (node-opcua) |
| table-layout | 4.1.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| thenify-ex | 4.4.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| thunky | 1.1.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| tslib | 1.14.1 | 0BSD | test | test-infra/sim/opcua (node-opcua) |
| tslib | 2.8.1 | 0BSD | test | test-infra/sim/opcua (node-opcua) |
| tsyringe | 4.10.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| tweetnacl | 0.14.5 | Unlicense | test | test-infra/sim/opcua (node-opcua) |
| typical | 7.3.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| undici-types | 8.9.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| webcrypto-core | 1.9.2 | MIT | test | test-infra/sim/opcua (node-opcua) |
| wordwrapjs | 5.1.1 | MIT | test | test-infra/sim/opcua (node-opcua) |
| xml-writer | 1.7.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| yauzl | 3.4.0 | MIT | test | test-infra/sim/opcua (node-opcua) |
| io.grpc:grpc-netty-shaded | 1.68.2 | Apache-2.0 | runtime | gateway/server (peer network, ADR 0012). Shaded Netty so it does not collide with Milo's Netty |
| io.grpc:grpc-protobuf | 1.68.2 | Apache-2.0 | runtime | gateway/server (peer network protobuf marshalling) |
| io.grpc:grpc-protobuf-lite | 1.68.2 | Apache-2.0 | runtime | transitive via grpc-protobuf |
| io.grpc:grpc-stub | 1.68.2 | Apache-2.0 | runtime | gateway/server (generated gateway-network stubs) |
| io.grpc:grpc-api | 1.68.2 | Apache-2.0 | runtime | transitive via grpc-stub |
| io.grpc:grpc-core | 1.68.2 | Apache-2.0 | runtime | transitive via grpc-stub |
| io.grpc:grpc-context | 1.68.2 | Apache-2.0 | runtime | transitive via grpc-api |
| io.grpc:grpc-util | 1.68.2 | Apache-2.0 | runtime | transitive via grpc-core |
| com.google.protobuf:protobuf-java | 3.25.5 | BSD-3-Clause | runtime | peer network messages; test-only in modules/driver-mqtt to cross-check the Sparkplug payload codec |
| com.google.api.grpc:proto-google-common-protos | 2.41.0 | Apache-2.0 | runtime | transitive via grpc-core |
| com.google.guava:guava | 33.2.1-android | Apache-2.0 | runtime | transitive via grpc-core |
| com.google.guava:failureaccess | 1.0.2 | Apache-2.0 | runtime | transitive via guava |
| com.google.guava:listenablefuture | 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 | runtime | empty jar pulled in by grpc-core to avoid a guava conflict |
| com.google.errorprone:error_prone_annotations | 2.28.0 | Apache-2.0 | runtime | transitive via grpc and guava |
| com.google.code.findbugs:jsr305 | 3.0.2 | Apache-2.0 | runtime | transitive via grpc |
| com.google.code.gson:gson | 2.11.0 | Apache-2.0 | runtime | transitive via grpc-core |
| com.google.android:annotations | 4.1.1.4 | Apache-2.0 | runtime | transitive via grpc |
| io.perfmark:perfmark-api | 0.27.0 | Apache-2.0 | runtime | transitive via grpc-core |
| org.checkerframework:checker-qual | 3.42.0 | MIT | runtime | transitive via guava |
| org.codehaus.mojo:animal-sniffer-annotations | 1.24 | MIT | runtime | transitive via guava |

## Load bench, optional OPC UA read

Not imported by the bench unit tests, the simulator image, or the gateway. Installed only to read tags through an OPC UA server (`bench/requirements-reference.txt`). asyncua is LGPL-3.0-or-later and stays in that client process.

| Artifact | Version | License | Scope | Used by |
|---|---|---|---|---|
| asyncua | 2.1.0 | LGPL-3.0-or-later | test | bench OPC UA tag read |
| aiosqlite | 0.22.1 | MIT | test | asyncua |
| anyio | 4.15.1 | MIT | test | asyncua |
| cffi | 2.1.1 | MIT-0 | test | cryptography |
| cryptography | 50.0.2 | Apache-2.0 OR BSD-3-Clause | test | asyncua |
| idna | 3.20 | BSD-3-Clause | test | anyio |
| pycparser | 3.12 | BSD-3-Clause | test | cffi |
| python-dateutil | 3.0.0 | Apache-2.0 OR BSD-3-Clause | test | asyncua |
| pytz | 2026.5 | MIT | test | asyncua |
| six | 1.17.0 | MIT | test | python-dateutil |
| sortedcontainers | 2.4.0 | Apache-2.0 | test | asyncua |
| typing_extensions | 4.16.0 | PSF-2.0 | test | asyncua |
| tzdata | 2026.5 | Apache-2.0 | test | python-dateutil |

## Windows service wrapper

Not a Gradle dependency. `packaging` downloads it when building the Windows gateway exe and passes it to jpackage as `service-installer.exe`. The zip is checked against a pinned SHA-256. It is not on the JVM classpath.

| Artifact | Version | License | Scope | Used by |
|---|---|---|---|---|
| nssm (service-installer.exe) | 2.24 | Public domain | runtime | Windows gateway service (jpackage launcher-as-service). https://nssm.cc/ |
| WiX Toolset (candle.exe, light.exe) | 3.14.1 | MS-RL | host | host tool for jpackage `--type exe`; not shipped inside the installer |
