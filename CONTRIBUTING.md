# Contributing to ERIC

Thanks for wanting to help build **ERIC** (Engineered Runtime Interface & Control) and Control Canvas. Pick something from the wishlist, build it, and send it back.

Two things to know first:

- **ERIC is a hobby project.** Nick Hepburn maintains it in his spare time. There is no guaranteed support, review time, or release schedule. Reviews happen when they happen.
- **ERIC is beta software.** Use it only with hardware for testing, under safe conditions. Never on live production, life-safety, or equipment-protection systems. See [Safety](docs/manual/safety.md).

The manual is online at <https://neh773.github.io/eric-platform/>.

## The path, start to finish

### 1. Pick or open an issue

- Browse the [wishlist](docs/wishlist.md) or the issues labeled [`good first issue`](https://github.com/neh773/eric-platform/labels/good%20first%20issue).
- If there is an issue, comment "I'd like to take this" so nobody doubles up.
- If there is no issue, open one with the **Feature request** template and say which wishlist row it is. For anything large (a new subsystem, a driver that writes to controllers), wait for a reply on the issue before you write a lot of code.

### 2. Fork and branch

Fork the repository on GitHub, then:

```bash
git clone https://github.com/<you>/eric-platform.git
cd eric-platform
git remote add upstream https://github.com/neh773/eric-platform.git
git switch -c feature/<issue-number>-short-name     # for example feature/42-postgres-form
```

Use `fix/<issue>-short-name` for bug fixes and `docs/<issue>-short-name` for manual-only changes. Keep one feature per branch.

### 3. Set up your machine

| Tool | Version | Used for |
| --- | --- | --- |
| JDK | 21 (Temurin or similar) | Gateway, modules, tools (Gradle wrapper is included) |
| Node and pnpm | Node 22+, pnpm 10 | Control Canvas, HMI, admin (`web/`), manual site (`docs-site/`) |
| Docker | any recent | Some gateway and MQTT tests start containers |
| Python | 3.10+ with `pip install pyyaml==6.0.2` | Repository checks |
| Rust | stable, plus a C compiler | PLC engine (`plc/eric-plc`), Linux only |

To try a change, build the gateway with `./gradlew :gateway:server:installDist`, start it as the [install page](docs/manual/install.md) describes for a checkout, and open `http://127.0.0.1:8090/admin`. Control Canvas development builds come from `web/` (`pnpm install`, then `pnpm dev`).

### 4. Build it, then run the local check

Add or update tests for what you changed, and a manual page in `docs/manual/` if a user will see it. Then run the same build, lint, and tests CI runs:

```bash
scripts/check.sh            # Linux, macOS, or WSL
pwsh scripts/check.ps1      # Windows (the PLC group needs Linux or WSL)
```

You can run only the parts you touched, for example `scripts/check.sh web docs`. Before you open the pull request, run the full check once. It ends with a short summary table. Paste that into the pull request. Do not loosen a test, threshold, or timeout to make it pass.

CI does not run on pull requests. It only runs on release tags and when the maintainer starts it by hand. Your local check is the evidence.

### 5. Open a pull request

```bash
git commit -s -m "Add a Postgres connection form (#42)"   # -s adds the sign-off line
git push origin feature/42-postgres-form
```

Open a pull request against `main` and fill in the template:

- Link the issue (`Closes #42`).
- Paste the `scripts/check.sh` summary.
- Add screenshots or a short clip for anything on screen.
- Tick the CLA box. That is how you accept the [Contributor License Agreement](CLA.md).

### 6. Review and merge

Nick reviews when he can and may ask for changes. Push more commits to the same branch to update the pull request. Only Nick merges to `main`. Changes that write to controllers or touch security, secrets, identity, or release signing get extra care.

Keep your fork current with `git fetch upstream && git rebase upstream/main`.

## Ground rules

- Read [GOALS.md](GOALS.md). Work that does not fit the goals is unlikely to land.
- No passwords, keys, tokens, plant data, or real addresses in code, tests, screenshots, or logs. Use clearly fake values.
- Do not name other companies' products in the software or the manual. Describe what a feature does instead.
- Be kind. See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

## Developer Certificate of Origin

ERIC uses the [Developer Certificate of Origin](https://developercertificate.org/) (DCO), version 1.1, and the contributor agreement in [CLA.md](CLA.md). By signing off, you attest that you have the right to submit the work. By checking the CLA box on the pull request, you accept that agreement, including Nick Hepburn's right to relicense the contribution and the patent grant. The project license is the GNU Affero General Public License v3.0 or later.

Add a `Signed-off-by` line to every commit, matching your name and email:

```
Signed-off-by: Your Name <you@example.com>
```

`git commit -s` adds this line for you when `user.name` and `user.email` are set. Commits without a sign-off will be asked to amend or retitle until they have one. Check the Contributor License Agreement box in the pull request template as well. That checkbox is the acceptance of [CLA.md](CLA.md) for the commits in that pull request.

## Code and docs style

- Kotlin is the usual language for gateway and module code. Follow the style of the file you are in.
- Put `SPDX-License-Identifier: AGPL-3.0-or-later WITH LicenseRef-ERIC-EPL-Linking-Exception` and `Copyright (C) 2026 Nick Hepburn` at the top of each new source file. Use `//` in Kotlin and TypeScript, `#` in Python and PowerShell. The exception is [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md). It is an AGPL section 7 permission to link EPL-2.0 and EPL-1.0 libraries. Your contribution is covered by it. See [CLA.md](CLA.md).
- User-facing strings and manual pages should be plain, specific, and free of another company's trademarks.
- The Graphite design system (dark default, optional light theme, accent `#3D8BFF`, reserved alarm colors, no decorative shadows or blur on the HMI) applies to Control Canvas, the gateway admin, and the HMI runtime.
- New user-facing features should include a screen where that is practical, and a manual page that a person who does not write code can follow. Keep JSON and API paths working.

## Adding a protocol driver

A driver is a module. It lives in its own directory, declares a manifest, implements the module lifecycle, and talks to the tag system through the host's public services. It should not require patches to the gateway core.

### 1. Start from a known layout

Create `modules/driver-<protocol>/` next to the existing drivers. A minimal tree looks like this:

```
modules/driver-<protocol>/
  build.gradle.kts
  src/main/kotlin/.../<Protocol>DriverModule.kt
  src/main/resources/module.yaml
  src/test/kotlin/.../<Protocol>DriverTest.kt
```

`modules/hello-world` is the smallest module to copy for manifest and lifecycle shape. An existing driver under `modules/driver-*` is the better template for device config, connections, and tags.

Register the Gradle project in `settings.gradle.kts`.

### 2. Write the manifest

`src/main/resources/module.yaml` must include at least:

- `id` — a stable module id
- `version` — semver for the module package
- `name` — the name shown in the gateway admin
- `entrypoint` — the class that implements `Module`
- `permissions` — the host services this module actually needs, and no others
- `configSchema` — JSON Schema (draft 2020-12) for the module's default config, if any
- `dependencies` — other modules this one requires, or an empty list

Do not request permissions you do not use. Do not open network connections that are not implied by the protocol the user configured.

### 3. Implement the module lifecycle

Implement the `Module` interface from the module SDK:

- `install` — first install; write default config through `ModuleContext.config`
- `restore` — gateway restart when the module is already installed; keep existing config
- `upgrade` — optional; default is `restore`
- `start` / `stop` — open and close connections
- `uninstall` — release external resources; the host then deletes the module's config

Register a `Device` (or connection) kind with `config.registerKind`, supply a JSON Schema, and add semantic checks with `config.registerSemantic` for rules the schema cannot express (for example, a path that must parse, or fields that depend on each other). Subscribe to config events so that creating, updating, or deleting a device takes effect without a gateway restart.

### 4. Be a good citizen of the tag model

- Map protocol addresses to tags through the host services the SDK documents, not through private gateway internals.
- Surface quality: good, uncertain, and bad should be visible to the HMI and to alarms.
- Writes are privileged. A driver that can write must enforce the gateway's existing permission and audit path, must be explicit about when a write is sent, and must be covered by tests. Pull requests that add or expand write paths are safety-critical and need human approval.
- Config changes should be validated before they are committed. Unknown fields should be rejected.
- Secrets (passwords, keys, certificates) belong in the secret store, referenced as `secret://<name>`, never as plain text in config or logs.

### 5. Tests and the conformance kit

Every driver needs unit tests for config validation, connect and disconnect, reads, and writes if the driver supports them.

Drivers that move process values at scale should also pass the conformance kit. Today the load floors live in `test-infra/driver-floors` (and related bench images). A general kit for third-party protocols is part of the roadmap; until it lands, add tests that prove:

- The driver can be installed, started, stopped, and uninstalled cleanly.
- Reads deliver values and quality on a configured scan or subscription.
- Writes, if supported, are authorized, audited, and reversible in the test fixture.
- A device can be added or removed at runtime.
- Failure of the remote device is reported as bad quality, not as a gateway crash.
- The driver does not need loopback-only networking in order to work; plant networks are not localhost.

Do not lower a published performance floor to admit a driver.

### 6. Manual and admin screens

Add a device or connection path in the gateway admin so an integrator can configure the driver without editing JSON by hand. Document the settings, addressing, and common failures in the product manual.

### 7. What will be rejected

- A driver that bundles a copy of the gateway, or that patches core packages instead of using the module SDK.
- Undocumented outbound network or telemetry.
- Proprietary protocol code you do not have the right to submit (the DCO is your attestation).
- Credentials or plant captures checked in as test data.

If you are unsure whether a protocol or a dependency can be included, open an issue before you write a lot of code.
