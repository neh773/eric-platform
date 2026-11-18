# ERIC governance

This document describes how **ERIC** (Engineered Runtime Interface & Control) is steered, released, and funded. The longer system design for agents, payments, and support is in [docs/governance/ARCHITECTURE.md](docs/governance/ARCHITECTURE.md). Goals and non-goals are in [GOALS.md](GOALS.md). How to contribute is in [CONTRIBUTING.md](CONTRIBUTING.md).

Nothing in this file is legal advice. Payment and tax rules will also appear in the terms published with Open Collective and any paid checkout, and those terms control if there is a conflict.

## Human final say

ERIC uses agents heavily: they review pull requests, estimate work, implement funded features, and help paying operators with logs. They do not own the project.

- A human maintainer clicks merge. Agents may label a pull request ready and may make small integration fixes; they may not merge.
- Safety-critical changes always need human approval, even when CI is green and the review agent is satisfied. That includes drivers that write to controllers, security, secrets, identity, authentication, authorization, and anything that signs or publishes a release.
- Agents never hold merge rights or release-signing keys by themselves. Those credentials stay with human maintainers, or with a protected automation path that still requires a human approval gate.
- When the review agent and [GOALS.md](GOALS.md) disagree with a contributor, a human maintainer decides, and the written goals should be updated if the decision changes policy.

Nick Hepburn is the sole maintainer. See [MAINTAINERS.md](MAINTAINERS.md).

## License and contributions

The repository is licensed under the GNU Affero General Public License v3.0 or later, with the linking permission in [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md); see [LICENSE](LICENSE). The SPDX expression is `AGPL-3.0-or-later WITH LicenseRef-ERIC-EPL-Linking-Exception`. Contributions need the agreement in [CLA.md](CLA.md) and a Developer Certificate of Origin sign-off, as described in [CONTRIBUTING.md](CONTRIBUTING.md). The CLA gives Nick Hepburn the right to relicense a contribution and includes a patent grant.

## Release cadence

Versions follow [Semantic Versioning](https://semver.org/). The automated changelog is generated from pull requests and conventional commit messages.

| Channel | Cadence (target) | Meaning |
|---|---|---|
| Development builds | On each merge to `main`, or at least weekly | Integration builds. Useful for contributors and early testers. Not a support baseline. |
| Minor / patch releases | As changes accumulate, version by version | Tagged, changelogged builds that have passed ordinary CI. |
| STABLE / LTS | Periodically, every few months when the tree is ready | A supported baseline. Gated by the full performance suite (PRF benchmarks) and a soak test, plus the ordinary CI and a human release approval. |

A STABLE or LTS tag is not published by an agent acting alone. The performance suite and the soak must pass on declared hardware profiles; thresholds are not relaxed to ship a date.

Signed release artifacts and a software bill of materials (SBOM) are required for STABLE and LTS, and are the aim for every tagged release.

## Funding and transparency

The public write-up for testers is [docs/funding-model.md](docs/funding-model.md). This section is the maintainer's copy of the same plan.

ERIC is run close to not-for-profit. The intended fiscal home is a host such as the Open Source Collective, with a public ledger on Open Collective, plus GitHub Sponsors if that is convenient for donors. All surplus — donations, paid feature work, and paid support after costs — is reinvested in model tokens, infrastructure, and paid human review.

- The ledger is public: money in, money out, and the issue or work item a payment was earmarked for, when it was earmarked.
- Agent spend is capped per request and per day. Unpaid questions do not draw on the maintainer's personal budget; the support agent says what credit is needed before it works.
- Estimates are estimates. A margin is included because token use is hard to predict. Unused prepaid credit is refunded or rolled over, as the published payment terms will state.
- Taxes, invoicing, and payout are handled through the fiscal host where possible.

A later step, if the project grows, is a foundation or a similar legal home. That move would be written here before it happens.

## Decision records

Governance changes (maintainers, license policy, funding host, release rules) are made by updating this file in a pull request.
