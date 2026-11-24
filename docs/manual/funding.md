# Funding and support

This is the plan for how ERIC (Engineered Runtime Interface & Control) and Control Canvas pay for themselves. It is not built yet. It is planned for after the 0.1.0 beta. Comments belong in Discussions.

The same write-up is in the repository at `docs/funding-model.md`.

**ERIC v0.1.1 is BETA software and must only be used with hardware for testing purposes under safe conditions — not for live production, life-safety, or equipment-protection use. It is provided AS IS with no warranty (AGPL-3.0).**

## The goal

ERIC is meant to run close to not-for-profit.

Money that comes in pays for development: the tokens an agent spends to write and change the software, and the machines and time used to test it. Hosting and review cost something too. What is left goes back into the product, not into a profit target.

The books stay open. Money in, money out, and which wishlist item a payment was for, are published. Anyone can read that record.

## Paying for a feature

Every item on the wishlist can be a GitHub issue with a funding goal. The goal is the estimated token cost plus the testing that item needs.

A funding page shows how far each issue has gotten. You pay through Stripe, GitHub Sponsors, Open Collective, or more than one of those. The page is the record. The payment service is only how the money moves.

When the goal is met, development starts. People who paid are credited on the issue and in the changelog. The result is merged under the same AGPL license as the rest of ERIC, written up in the manual, and available to everyone. Paying does not buy a private copy of the ERIC or Control Canvas names.

## Contributing code instead of money

You can build or fix an item and send a pull request. You do not have to pay.

A pull request needs the contributor agreement in CLA.md. That agreement lets Nick Hepburn relicense the contribution, and it includes a patent grant.

An automated review checks the change for safety and for fit with the project. It looks for writes to field devices, secrets, and changes that do not belong. Nick Hepburn is the maintainer. He decides what merges. The review does not merge by itself.

## Paid support

After the beta, a support assistant is planned for people who want help with their own gateway.

You buy credit. You upload logs, a backup, or a project export. The assistant reads the manual and the source and tells you what it found. You see the running cost while it works. When the credit runs low, it stops and asks you to buy more before it continues. It does not keep working on an empty balance.

Your files stay private. They are not posted in public issues, discussions, or docs. They are deleted after a retention period, which will be stated when the service opens.

## Bugs found during support

If the assistant finds a real defect in ERIC, it can open a GitHub issue. It asks you first. The issue is sanitized: no plant names, no addresses, no passwords, no tag values that identify a site.

That bug can then be fixed the same way as any other wishlist item. Someone in the community can send a pull request, or people can fund it until the fix is done.

## What a credit costs

Tokens are charged at what they cost, plus a modest margin of about 20 to 30 percent. The margin covers hosting and review. The exact prices are not set. They will be published before anyone is asked to pay.

## Status

Planned. Not built. The beta does not take payments and does not offer the support assistant. If you have a view on this model, say so in Discussions.
