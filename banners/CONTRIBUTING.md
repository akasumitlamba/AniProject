# AniHome banner contribution guide

Read `../CONTRIBUTING.md` before project work. AniHome should feel like switching on a smart TV: landscape logo cards on brand colours, a glass toolbar and system wallpaper. Native apps and websites are equal tiles. This catalog provides artwork; it never installs apps or changes a tile automatically.

## What to prioritise

Start with entertainment and apps people use on tablets: video/music streaming, games, reading, drawing/design, learning and document/productivity tools. India-relevant services matter. Shopping/payment/phone utilities remain available, but rank lower. This is a curated selection, not an asserted live “top 100” chart. Keep all existing utility banners unless removal is explicitly part of the change.

## Find the correct identity

1. Find the current official app listing, installed app icon or developer brand kit. Record its URL in `sources.json`. Check the product and regional variant, not just the company name. Google Pay India differs from other products; Adobe has multiple apps; Grok Bot differs from Grok AI.
2. Use the actual icon as a generation reference. Never invent a mark from memory. Follow the developer's current brand artwork; older chat or contact sheets may be stale. Use a short recognizable display name and put alternatives in aliases.
3. Verify the Android package against the official listing. Do not guess it. Add exact product website hosts only; a shared parent domain such as `adobe.com` cannot identify one of several products.

## Make one banner

- Landscape ratio **1.62:1**, typically about **1600 × 988 px**. Accepted ratio: 1.57–1.67; width 320–4096px, height ≤4096px, encoded file ≤4MiB.
- Full-bleed brand colour, authentic large logo and readable app name, balanced safe margins of about 9%. Use high contrast; wrap long names cleanly.
- No outer frame, rounded outer corners, watermark, extra slogan or unrelated artwork. AniHome clips its own card corners. Avoid tiny text and baked-in UI.
- One app per image. Preserve the original mark, proportions and colours. Game-character icons may retain their original illustrated style.
- Use an image-generation tool with the genuine icon as a reference, or compose exact developer-provided vector artwork. Do not trace a guessed logo. Inspect the final image at the size of a Home tile.

Reusable AI prompt (replace the bracketed values and attach the actual app icon):

> Create ONE landscape 1.62:1 smart-TV launcher banner for [APP NAME]. The attached image is the genuine app icon: reproduce its recognizable logo faithfully, without invented symbols. Use a clean full-bleed [BRAND COLOUR] background, a large authentic emblem on the left and exact text “[APP NAME]” in a bold highly readable contrasting sans-serif on the right. Balance the layout with generous safe margins; long names may wrap into two lines. Preserve official identity and colours. No outer border, rounded outer corners, device frame, slogan, watermark, collage or other apps.

Compare against the source icon. Check spelling, logo geometry, colour, contrast and crop safety. Reject plausible-looking but wrong logos. Keep the full-resolution source outside git if desired; publish an efficiently encoded WebP (quality ~92) or PNG. Encoding must not distort/crop the composition.

## Add it to this folder

Use lowercase app-name filenames with hyphens: `images/youtube-music.webp`, `images/clash-of-clans.webp`. Existing IDs are stable; do not rename them casually. No user photos, credentials, device dumps or signing material belong here.

Add a row to `catalog.json` → `entries`:

```json
{
  "id": "youtube-music",
  "name": "YouTube Music",
  "file": "images/youtube-music.webp",
  "packages": ["com.google.android.apps.youtube.music"],
  "aliases": ["YouTube Music", "YT Music"],
  "webHosts": ["music.youtube.com"],
  "category": "entertainment",
  "tabletPriority": 100,
  "popular": true,
  "sha256": "REPLACE_WITH_ACTUAL_FILE_SHA256"
}
```

Top-level schema is `{"schema":1,"entries":[...]}`. Update the dated catalog field. Categories use lowercase names such as `entertainment`, `games`, `creativity`, `learning`, `productivity`, `communication`, `shopping`, `payments`, `utilities`. Priority is 0–100: entertainment ~98–100, games ~96, creative ~92–94, learning/reading ~90, productivity ~82–89, shopping ~45, payments ~25. Use judgment; this is tablet suitability, not a download rank. `popular` describes the curated mainstream subset, not a ranking claim.

Use `sha256sum images/APP.webp` and copy the real digest. IDs, files and package mappings must be unique; avoid ambiguous hosts. Aliases support name searches, including punctuation and alternate labels. Matching orders exact package, exact host, then exact normalized name/alias. Selection remains explicit; no fuzzy automatic application.

Update `sources.json` with the public reference URL, developer/listing title and any identity notes. Keep generation prompts in `generation.json`; never include local private paths or tokens. Brand marks belong to their respective owners; these launcher banners are independent artwork, not an endorsement.

## Validate and publish

From the repository root:

```sh
python3 launcher/tools/validate_banner_catalog.py banners/catalog.json --bundled banners --minimum 100
python3 -m unittest discover -s launcher/tools -p 'test_banner_catalog.py' -v
cd launcher
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

The Gradle assets source is this `banners/` folder directly, so the same catalog/images ship offline. Verify the built APK's `assets/catalog.json` and every image against the source SHA-256. Test matched/renamed apps, no-match search, offline use, preview/cancel, save and reset. Refresh failures must preserve cached artwork and existing tiles. Do not overwrite a user's chosen images during testing.

Commit catalog, images, sources and guide changes together, update technical documentation as needed. APKs remain GitHub Release assets; keystores stay out of git. New offline banners reach devices through an app update; existing devices can also Refresh to fetch the GitHub catalog.

## Private GitHub access

Offline banners work without an account. Explicit online Refresh uses the public AniProject repository through raw.githubusercontent.com, pinned HTTPS, hash checks and bounded downloads. No account, token or installed-app-list upload is required. Requests must not include stored GitHub credentials. Failed refresh keeps bundled/cached artwork; retry remains explicit.

Never include credentials in APKs, catalogs, URLs, screenshots, logs or commits. Keep contributions and examples technical, without private conversations or personal-device inventories.
