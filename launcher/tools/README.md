# Banner Store checks

Run the catalog validator before packaging/publishing curated artwork:

```sh
python3 launcher/tools/validate_banner_catalog.py banners/catalog.json \
  --bundled banners --minimum 100
python3 -m unittest discover -s launcher/tools -p 'test_banner_catalog.py' -v
```

The validator checks the Android catalog schema, minimum number of entries,
image filenames, real local files, checksums, image dimensions, landscape ratio,
download byte limits, package/host mapping conflicts and matching offline assets.
It reports duplicate normalized names/aliases as ambiguity warnings because
the launcher asks the user to choose a banner explicitly.

These checks do not prove that a logo depicts the correct brand. Review final
images against the installed app icon or current official brand references.
Previously generated contact sheets can be stale after logo corrections; inspect
the actual catalog files. Record the exact reference source with the artwork's
generation/source provenance. Do not invent brand marks to fill missing entries.

Private GitHub refresh must keep the repository URL pinned to HTTPS, refuse
redirects, bound catalog/image reads, and verify each downloaded image hash.
User credentials belong only in Android Keystore-encrypted no-backup storage;
never include them in catalog JSON, source control, logs, screenshots or APKs.
The offline bundled catalog must remain usable without a token or network.
