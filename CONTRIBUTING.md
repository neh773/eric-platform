# Contributing to ERIC

Thank you for wanting to improve **ERIC** (Engineered Runtime Interface & Control). This guide covers the fork and pull request flow, the Developer Certificate of Origin, and how to add a protocol driver without changing the gateway core.

Longer product and project context lives in [GOALS.md](GOALS.md), [GOVERNANCE.md](GOVERNANCE.md), and [docs/governance/ARCHITECTURE.md](docs/governance/ARCHITECTURE.md).

## Before you start

Read [GOALS.md](GOALS.md). Work that does not fit the project's goals will be labeled for a human maintainer and is unlikely to land.

If you are proposing a large feature, open an issue first. Wishlist items and the funding plan are in [docs/wishlist.md](docs/wishlist.md) and [docs/funding-model.md](docs/funding-model.md).

Maintainers run the test suites on their own machine before a change is merged. Automated checks run when a `v*` release tag is pushed, and when a maintainer starts a workflow by hand. A normal push or pull request does not start them.

## Reporting a bug

First check the [known bugs](docs/manual/known-bugs.md) list (also on the [public manual](https://neh773.github.io/eric-platform/manual/known-bugs/)). If the problem is already listed, you do not need a new issue unless you have new information, such as a different operating system or a clearer way to reproduce it.

To report a new bug, choose **Issues** → **New issue** → **Bug report**. Fill in the ERIC version, operating system, install type, area, the steps to reproduce, what you expected, what you saw, and logs or screenshots. Remove passwords and plant addresses from anything you attach.

## Before you push

Follow the [release test plan](docs/testing/release-test-plan.md): the automated gates below, plus the hands-on
new-user pass on fresh Linux and Windows installs for releases and for changes to user-facing features.

Run the full local check and push only when it passes:

```
scripts/check.sh          # Linux
scripts\check.ps1         # Windows, from an elevated PowerShell
```

It runs the Gradle build and every JVM test, the repository checks, the `eric-plc` tests, web lint, typecheck, unit
and browser tests, then builds the installable package and tests it as a new user would:

- **Fresh install.** On Linux, `packaging/linux/fresh-install-test.sh` extracts the app image into an empty folder,
  starts it with a clean environment, creates the first administrator in the first-run form, opens the admin page,
  Control Canvas and the manual, and deploys a Structured Text program with the bundled compiler and scan engine.
  On Windows, `packaging/windows/smoke.ps1` installs, runs, upgrades and uninstalls the service.
- **Every feature through the UI.** `web/e2e-ui` (`pnpm test:ui`) drives every feature through the admin page and
  Control Canvas forms only: devices, tags, UDTs, alarms, historian, security, every admin page, the canvas
  palette, `/hmi`, Structured Text logic, then restart, kill, backup restore and reinstall.

A pull request description lists the check result. A feature is not done until a person can reach it from the
admin page or a Control Canvas form, and a test in `web/e2e-ui` or `web/admin/e2e` does it that way.

## Fork, branch, and pull request

1. Fork the repository and clone your fork.
2. Create a branch from `main` with a short, descriptive name (`driver-ads`, `fix-alarm-ack`, `docs-redundancy`).
3. Keep the change focused. Prefer a small stack of pull requests over one large one.
4. Add or update tests for the behavior you changed. Do not loosen an existing test, threshold, or timeout to make a run pass.
5. Run `scripts/check.sh` (or `scripts\check.ps1`) and push only when it passes. See [Before you push](#before-you-push).
6. Sign off every commit (see [Developer Certificate of Origin](#developer-certificate-of-origin) below).
7. Open a pull request against `main` and fill in the pull request template.
8. A review agent runs automatically on the `pull_request` event, in an isolated sandbox, with no repository secrets. It checks safety, license and dependency issues, secret leakage, fit with `GOALS.md`, and tests. It may propose or push small integration fixes on branches it is allowed to write to, and it will label the pull request when it thinks the change is ready.
9. Nick Hepburn is the maintainer. He decides what merges. A review agent checks safety and fit with the project first. Safety-critical areas — including drivers that write to controllers, and anything in security, secrets, identity, or release signing — always need his approval.

Do not submit generated secrets, plant data, or live credentials. Use clearly fake values in fixtures and examples.

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
