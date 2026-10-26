# Unified AniProject releases

A request to update a release applies to every active product: AniHome, AniBrave Android, the AniBrave desktop extension, and the banner catalog. AniBrowser Full/Lite are retired and are not rebuilt.

1. Update `release.json`, launcher versionName, `anibrave/version.json` name, and extension manifest version together. Run `python3 tools/check_release_versions.py`. Keep Android versionCodes higher than previously shipped builds; they need not equal one another. Brave engine identity is separate application metadata, not the product display version.
2. Preserve completed work from other tasks. Confirm build provenance and run relevant product tests. Retain signing identities, package IDs, profiles and user data. Never rename an old binary to imply a different embedded version.
3. Build both signed Android APKs, package the unpacked desktop extension, and package the banner catalog. Create one all-products ZIP plus a SHA256SUMS.txt covering every release asset. Include validation evidence and actual limitations in release notes.
4. Publish reviewed project-only source through the independent public history. Review staged source, images, binaries, certificate subjects and archive contents for privacy, and scan for secrets. Keep private context, signing material and device configuration excluded.
5. Publish identical verified assets to the private and public combined releases. Verify anonymous public downloads and extracted bundle hashes before marking delivery complete.
6. Before deleting a superseded standalone release, preserve its technical changes in the combined changelog and confirm its functionality is represented in the new release. Keep previous complete combined releases as rollback points. Remove release records and attached assets only; retain Git tags/history. Do not remove drafts or retired-product archives implicitly.

An existing combined release may be updated in place. A new shared product version gets one new combined release, rather than separate product releases.
