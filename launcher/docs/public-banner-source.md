# Public banner source

AniHome 0.8.14 refreshes from `https://raw.githubusercontent.com/akasumitlamba/AniProject/main/banners/`. Catalog and image paths are constrained; HTTPS host validation, no redirects, byte limits, image dimensions, SHA-256 checks and atomic caching remain enforced.

Requests are anonymous and include no Authorization header. The banner menu no longer offers token entry. When the new source is first loaded, obsolete encrypted banner access and its Android Keystore alias are removed; the previous source's retry pause is reset. No app tiles, artwork, notes or history are deleted.

Opening the store reads the bundled/cached catalog without a network probe. Explicit Refresh loads the public catalog; failed requests retain offline artwork and offer explicit Retry. Images already present in the bundle or cache avoid unnecessary downloads.

Unit checks cover the catalog endpoint, image endpoint and rejection of traversal/arbitrary URLs. Network acceptance compares anonymous public catalog/image bytes with source hashes; emulator acceptance covers refresh and legacy-credential cleanup separately from offline browsing.
