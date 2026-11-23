# Release process and continuous improvement (proposal)

**Status: proposal for tester feedback.** Nothing here is final. Comment in the "Release process proposal" discussion on GitHub: what would you need before trusting a stable release on a real plant?

ERIC is beta software. Use it only with hardware for testing purposes in safe conditions.

## Versions

ERIC uses `major.minor.patch`, for example `1.4.2`.

- **Patch** releases only fix bugs.
- **Minor** releases add features and never break existing projects.
- **Major** releases may break things. Each one ships with an automatic project migrator.

## Channels

| Channel | Who it is for | Cadence |
| --- | --- | --- |
| Nightly | Maintainers and contributors | Every day there are changes |
| Beta | Testers, lab and bench use | Every few weeks |
| Stable | Real use, after the gates below | When the gates pass |

A build is promoted to the next channel only after it has spent a set time in the previous one with no open critical bugs.

## Branches

- Day-to-day work lands on `main`.
- When a release is ready, it gets its own branch, for example `release/1.0`.
- A release branch only takes bug fixes. Each fix is made on `main` first and then copied over. This lets `1.0.3` ship without half-finished `1.1` features.

## Long-term support

One stable release a year is marked long-term support (LTS). It receives security and safety fixes for two to three years, because plants upgrade slowly.

## Required check before every push and tag

Before any push, and again on the exact commit before a release tag, a maintainer runs the full local check and it
must pass:

```
scripts/check.sh          # Linux
scripts\check.ps1         # Windows, from an elevated PowerShell
```

The check ends with two new-user tests on the built package:

1. **Fresh install.** The Linux app image (`packaging/linux/fresh-install-test.sh`) or the Windows installers
   (`packaging/windows/smoke.ps1`) on an empty machine state: first administrator from the first-run form, admin
   page, Control Canvas, manual, and a Structured Text program deployed with the bundled compiler and scan engine.
2. **Every feature through the UI** (`web/e2e-ui`, `pnpm test:ui`): each feature set up only through the admin page
   and Control Canvas forms, then a restart, a kill, a backup restore and a reinstall.

A tag is pushed only after both pass on Linux and on Windows. The release notes list the results.

## Gates for a stable release

A stable tag requires all of these:

1. The full local check passes (`scripts/check.sh` and `scripts\check.ps1`), including the fresh-install and new-user UI tests.
2. The hardware test rig passes the scenario tests.
3. An upgrade from the previous stable release works with projects intact.
4. Backup and restore work end to end.
5. A 72-hour soak test with no memory growth and no lost history.
6. No open issues labelled `critical`.
7. The manual matches the software.

## Continuous improvement

- **Hardware test rig.** One or two small industrial PCs run ERIC around the clock against simulated PLCs. Each release candidate runs scenario tests: the example wastewater project, alarm floods, network drops and store-and-forward recovery.
- **Diagnostic bundle.** A button in the gateway zips logs, configuration and version information, with secrets removed, to attach to an issue.
- **Bug triage.** A new issue is reproduced on the test rig. A confirmed bug gets a failing test first, then the fix, then a pull request for maintainer review. Every real bug leaves a test behind so it cannot return.
- **Optional usage data.** Off by default. If turned on, it reports only which features are used and crash signatures, never process data.
- **Funded wishlist items.** Items funded or contributed through the [funding model](funding.md) ship behind a feature flag in beta. The flag is removed once the feature has proven itself.
- **Checks.** Maintainers run `scripts/check.sh` or `scripts\check.ps1` before each push. Hosted CI runs on release tags and on demand.

## Path to 1.0

1. Beta rounds with testers until no critical issues remain.
2. Freeze the project file format and public APIs.
3. Build the upgrade migrator and the soak test.
4. Release candidate: feature-frozen, bug fixes only.
5. 1.0 stable, the first LTS release.

## Questions for testers

- What would you need to see before running a stable ERIC release on a live process?
- How long should an LTS release be supported?
- Should optional usage data exist at all?
- How often do you want beta releases?
