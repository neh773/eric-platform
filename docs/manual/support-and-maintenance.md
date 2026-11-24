# Support and maintenance

**ERIC v0.1.1 is BETA software.** The release process below is a proposal. It is not a promise that a stable channel exists yet. Comment in the "Release process proposal" discussion.

## Channels

From [Release process](release-process.md):

| Channel | Who it is for | Cadence |
| --- | --- | --- |
| Nightly | Maintainers and contributors | Every day there are changes |
| Beta | Testers, lab and bench use | Every few weeks |
| Stable | Real use, after the gates | When the gates pass |

A build moves up only after it has spent time in the previous channel with no open critical bugs.

Versions are `major.minor.patch`. Patch releases fix bugs. Minor releases add features and do not break existing projects. Major releases may break things and ship with a project migrator.

## Long-term support

One stable release a year is marked long-term support (LTS). It receives security and safety fixes for two to three years. Day-to-day work stays on `main`. A release branch, such as `release/1.0`, takes only bug fixes copied from `main`.

A stable tag requires the full test suite, the hardware rig, an upgrade test from the previous stable, a signed installer, and a changelog. Details are in the release-process page.

## How to get help

- **Free.** Open a GitHub issue for a bug. Use Discussions for questions and ideas. Tester notes are in TESTING.md.
- **Paid credits.** Planned after the beta. You buy credit. See [Funding and support](funding.md).
- **Paid support assistant.** Planned, not built. You upload logs, a backup, or a project export. The assistant reads the manual and the source, shows the running cost, and stops when credit runs out. Your files stay private. If it finds a product bug, it can open a public issue with the secrets removed.

Security reports go to the address in SECURITY.md. Do not file a vulnerability in a public issue.

Nick Hepburn is the sole maintainer. He decides what merges.
