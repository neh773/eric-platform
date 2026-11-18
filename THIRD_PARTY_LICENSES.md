# Third-party licenses

ERIC is AGPL-3.0-or-later. A dependency can ship inside an AGPL program when its license allows that combination. This list is what the Windows and Linux packages include or call. Versions move with the build; the license does not.

## Compatible and how they are used

| Component | License | How it is used |
| --- | --- | --- |
| Eclipse Temurin JDK 21 | GPL-2.0 with the Classpath Exception | The Windows package runs on this runtime. The Classpath Exception lets an AGPL program run on it. Use a build that carries that exception. |
| Jetty | Apache-2.0 or EPL-2.0 | HTTP server. Distributed under the Apache-2.0 terms, which an AGPL program may include. |
| Jackson | Apache-2.0 | JSON. |
| Eclipse Milo 1.1.8 | EPL-2.0 only | OPC UA client and server. Not dual-licensed Apache-2.0. Combined with ERIC under the additional permission in LICENSE-EXCEPTION.md. The Milo jars stay EPL-2.0. |
| SQLite JDBC (Xerial) | Apache-2.0 | Historian and configuration store. |
| Kotlin, Gradle | Apache-2.0 | Language and build. Gradle is a build tool and is not shipped. |
| Leaflet | BSD-2-Clause | Map component in the HMI. |
| OpenStreetMap tiles | Open Database License (data) plus the tile usage policy | The map asks the public tile servers for pictures. Attribute OpenStreetMap contributors on the map. Do not restyle the attribution. Heavy use needs your own tile server. This is a usage rule, not a code license. |
| NSSM | Public domain | Windows service wrapper in the installer. |
| WiX | Microsoft Reciprocal License | Build tool used by jpackage to make the Windows installer. WiX is not copied into the installed product. |
| RuSTy | LGPL-3.0-or-later | Structured Text compiler. Shipped as its own program, `rusty.exe` or `rusty`, started by the gateway. It is not linked into the gateway. LGPL-3.0 allows that. A copy of the RuSTy source and the offer to obtain it ship beside the binary. |

## Eclipse Public License libraries

Eclipse Milo 1.1.8 is EPL-2.0 only. EPL-2.0 does not, by itself, name the AGPL as a secondary license. Nick Hepburn, as copyright holder of ERIC, grants an additional permission under AGPL section 7 to combine and convey ERIC with EPL-2.0 and EPL-1.0 libraries. That permission is LICENSE-EXCEPTION.md (`LicenseRef-ERIC-EPL-Linking-Exception`). Milo is resolved by that permission. Do not relicense the Milo jars. Ship their EPL notices with them.

Jetty is used under its Apache-2.0 option. Where a component is taken under the EPL instead, the same additional permission covers the combination.

## Other rules

- RuSTy stays a separate program. Do not statically link it into the gateway or the PLC engine. LGPL-3.0 is compatible while it stays a separate process.
- The JDK build must keep the Classpath Exception. A proprietary JDK without that exception is not a substitute.

## Attribution text

The map shows: `© OpenStreetMap contributors`.
RuSTy shows: `RuSTy, LGPL-3.0-or-later, https://github.com/PLC-lang/rusty`.
