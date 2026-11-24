# Security

ERIC talks to controllers and can write setpoints. Treat a gateway as a plant system, not as a website.

## Supported versions

| Version | Status |
| --- | --- |
| 0.1.1-beta | The soft-release line. Fixes land on `release/soft-0.1`. |
| 0.1.0-beta | Superseded by 0.1.1-beta. |

## Reporting a vulnerability

Email Nick Hepburn. Do not open a public issue for a vulnerability that is still exploitable. Include the version, the endpoint or screen, and a log with secrets removed.

You will get a reply acknowledging the report. A fix, when one is made, is noted in CHANGELOG.md.

## What to expect in 0.1

- The admin UI requires a password. There is no built-in default password. Set `ERIC_ADMIN_PASSWORD` before the first start.
- Public HMI is off unless a project turns it on. A project that turns it on lets an anonymous session read and write tags. Do not enable it on a gateway that can reach real equipment.
- The Windows soft-PLC is not a safety controller. Do not use it for a safety function.
