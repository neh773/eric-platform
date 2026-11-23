# Contributing features

You can ask for a feature, help pay for one, build it into the shared tree, or keep it on your own branch.

## Ask

Open an Ideas discussion, or a feature-request issue, and say what the screen or the API should do. If it matches a row on [Future features](wishlist.md), say which row.

## Help pay

Each wishlist item can become an issue with a funding goal. The goal is the estimated token cost plus the testing that item needs. When the goal is met, work starts. People who paid are credited on the issue and in the changelog. The result stays under the AGPL and is available to everyone.

How payment works is in [Funding and support](funding.md). The payment page is not built in this beta.

## Build it for the shared version

ERIC is a hobby project with no guaranteed support or timelines, so the fastest way to get a feature is often to build it. The full steps are in [CONTRIBUTING.md](../../CONTRIBUTING.md). In short:

1. **Pick an item.** Choose a row on [Future features](wishlist.md) or an issue labeled `good first issue`. Comment on the issue to claim it, or open a Feature request issue if there is none.
2. **Fork and branch.** Fork `neh773/eric-platform` on GitHub and create a branch named `feature/<issue-number>-short-name`.
3. **Build it.** Add tests, and a page in this manual if a user will see the change.
4. **Run the local check.** `scripts/check.sh` (Linux, macOS, WSL) or `pwsh scripts/check.ps1` (Windows) runs the same build, lint, and tests CI runs, then prints a summary.
5. **Open a pull request against `main`.** Fill in the template: link the issue, paste the check summary, add screenshots for anything on screen, and tick the CLA box.
6. **Review.** Nick Hepburn reviews and decides whether it merges. Only he merges. Changes that write to devices or touch security get extra care.

A merged change is written up in the manual.

ERIC is beta software. Test your change only with hardware on a bench, under safe conditions. See [Safety](safety.md).

## Keep your own branch

You may change ERIC and not send the change back. If you give that copy to someone else, or you serve it over a network, the AGPL requires you to publish the source of your changes. You may not call that copy ERIC or Control Canvas. See [Mission and business model](mission-and-business-model.md).
