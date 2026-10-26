# Phase 8.49 — auto-download only the default plugins

## Why
The CloudStream backup format stores repositories and settings
but never the installed plugin list (plugins are excluded from
backups upstream). So the 8.48 auto-restore brought his
repositories back, and the setup screen then offered to download
EVERY plugin in them (hundreds of files he never used).

## What ships
- New asset `pro_default_plugins.json`: the five plugins behind
  the providers in the default backup, all from the CNC repo
  (identified from his provider list and verified against the
  repo's plugins.json by name, version and file size):
  - CNC Verse (Netflix, Prime Video, Hotstar, Disney, Marvel,
    Pixar, Star Wars)
  - MovieBoxProvider (MovieBox)
  - MovieLinkBDProvider (MovieLinkBD)
  - MLSBDProvider (MLSBD)
  - Rtally (Rtally)
- CloudStreamApp: when the bundled backup is applied on a fresh
  install, it now also sets the `pro_default_plugins_pending_v1`
  flag.
- MainActivity: on launch, if that flag is set, it downloads any
  missing default plugins in the background via
  PluginManager.downloadPlugin (and loads them), then clears the
  flag once all five files are present. If a download fails
  (offline), the flag stays and the next launch retries.
  Existing installs never get the flag, so nothing is pushed to
  them.
