# Phase 8.48 — bundled default backup, app name, version tag

His Default.txt (a manual Settings → Restore backup) now
ships INSIDE the app so everyone gets working settings and
customization out of the box:

- Sanitized copy at app/src/main/assets/pro_default_backup.json:
  ALL settings kept (player/UI switches, repositories,
  home prefs, quality profiles, his custom home lists +
  active list), personal data STRIPPED (resume-watching
  entries, watch positions, per-title dub/season picks,
  download header caches, files-to-delete, stored version
  name) — the file is for every user of the app.
- CloudStreamApp.applyDefaultBackupOnce(): on a FRESH
  install only (datastore still empty, one-time marker),
  the bundled backup is restored through the normal
  BackupUtils.restore path before anything else runs.
  Existing installs are never overwritten.

Branding:
- App name is "CloudStream Pro" everywhere (main, debug
  and prerelease labels were CloudStream / CloudStream
  Debug / CloudStream Beta).
- The prerelease flavor's versionNameSuffix "-PRE" is
  removed — the version now reads plain "4.8.0".
