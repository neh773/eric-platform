# ERIC project goals

**ERIC** (Engineered Runtime Interface & Control) is an open industrial controls platform. This file is the source of truth for what belongs in the project and what does not. The pull request review agent judges fit against this document. When a proposal is in tension with these goals, the agent labels it for a human maintainer and does not try to merge it.

## What ERIC is

ERIC is the control-room side of a plant: a gateway that talks to field devices and other systems, a tag and alarm model, a historian, an HMI runtime that operators use, and **Control Canvas**, the design application for building screens and configuration.

People should be able to configure and operate ERIC without writing code, using Control Canvas, the gateway admin, and the product manual. JSON and code remain first-class for people who want them.

## Goals

1. **Open and contributable.** The project is intended to live in the open on GitHub. Anyone can fork it, add a feature — especially a new protocol driver — and open a pull request.
2. **Safe to run a plant with.** Changes are reviewed for malicious or careless code, secret leakage, license problems, and unsafe writes to controllers. Safety-critical areas always need a human maintainer's approval, even when the review agent is satisfied.
3. **Plugin-style drivers.** A new protocol should live in its own module, behind a small driver interface, and pass a conformance test kit. A driver must not require a fork of the gateway core.
4. **No-code first, code welcome.** Every user-facing capability should have a screen and a manual page where that is practical. Power users keep JSON, scripting, and APIs.
5. **Calm, truthful interface.** The Graphite design system is the visual standard: dark default, optional light theme, one blue accent (`#3D8BFF`), and alarm colors reserved for alarms. The HMI stays gray-first and does not spend its performance budget on decoration.
6. **Measurable performance.** Published performance floors (the PRF benchmarks) and soak tests gate stable releases. Thresholds are not loosened to make a run look green.
7. **Operator-grade operations.** Install, backup and restore, redundancy, secrets, identity, and audit exist as product features, not as undocumented tribal knowledge.
8. **Close to not-for-profit.** Surplus from donations, paid feature work, and paid support is reinvested in the product: model tokens, infrastructure, and paid human review. The books are public.
9. **Agents as maintainers' helpers, not owners.** Agents review, estimate, implement funded work, and help paying operators with logs. A human clicks merge and signs releases. Agents do not spend the maintainer's own budget on unpaid work.

## Non-goals

The review agent should flag pull requests whose main purpose is any of the following, unless a maintainer has explicitly accepted the change.

- Replacing a safety-rated controller, safety instrumented system, or certified safety function. ERIC supervises and coordinates; it is not itself a safety-certified runtime.
- Building a general enterprise resource planning, accounting, or payroll system.
- Shipping a driver or integration that writes to field devices without tests, a clear permission model, and human review.
- Adding telemetry, licensing phoning home, or advertising that leaves a plant network by default.
- Collecting, selling, or training public models on operator logs, plant data, or support uploads.
- Auto-merging stranger code, or giving an agent sole possession of merge or release keys.
- Spending project or maintainer funds on unpaid support, or running unbounded agent sessions against the project's own wallet.
- Changing a performance threshold, test, or timeout in order to pass a failing run.
- Using another vendor's trademarks, copyrighted manuals, or proprietary protocols in a way that the project does not have the right to use.
- Scope that does not serve industrial operations, Control Canvas, the gateway, or the operator runtime.

## How the review agent should apply this file

- **Fits:** the change advances one or more goals and does not land in the non-goals list.
- **Needs discussion:** the change could fit, but it expands scope, touches safety-critical surfaces, or trades one goal against another. Label it and wait for a maintainer.
- **Does not fit:** the change is primarily a non-goal, or it conflicts with safety, openness, or the funding rules. Say so on the pull request and do not attempt integration.

When this file and a pull request disagree, a human maintainer decides, and then this file should be updated so the next review is consistent.
