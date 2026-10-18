# Contributing

AniBrowser contains AniHome (`launcher/`), AniBrave (`anibrave/`) and reusable banner assets (`banners/`). Technical boundaries are recorded in [PROJECT-CONTEXT.md](PROJECT-CONTEXT.md). Choose the product before changing code; retired browser variants in historical commits are not active projects.

Use JDK 17 and Android SDK 35 for AniHome. Run the launcher unit tests and release lint, then assemble a debug APK. AniBrave Java/JavaScript checks are listed in `.github/workflows/source-checks.yml`; packaging instructions are in `anibrave/README.md`. Banner changes must pass the catalog validator and its tests.

Keep examples reproducible with synthetic data. Do not commit private chats, agent handoffs, screenshots of personal notifications, browsing histories, account data, local machine paths, device serials, databases or recovery backups. Use Android version/API and screen geometry to describe compatibility rather than identifying a particular device installation. Product screenshots must use clean emulator fixtures.

Signing keys, passwords and access tokens stay outside Git, including past commits and release attachments. Configure signing through environment variables or ignored local properties. Never replace an existing installation's signing key or clear its data during routine verification. APK signatures include a public certificate; its private key must never be distributed.

Run `python3 tools/check_public_source.py` before committing. For a full-history secret audit, also run Gitleaks against all refs. The source check is a preventive guard, not proof that arbitrary binaries or hosting metadata are safe. Review release archives, logs, issue/PR bodies and screenshots before publishing them.

Use a privacy-safe Git author name and a private/noreply email. GitHub repository ownership, release uploaders and issue authors remain associated with the hosting account.

Keep third-party license and copyright notices intact. Describe validation accurately; emulator or synthetic-player checks do not establish physical radio, biometric or protected-streaming compatibility.
