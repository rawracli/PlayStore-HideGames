# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0] - 2026-10-20

Initial release.

### Added

- Hide games from Play Store **search results** (filtered as you type, results
  re-emitted to prevent flashing).
- Hide games from the Play Store **home feed**.
- **Block game detail pages** opened via direct links
  (`https://play.google.com/store/apps/details?id=...` and
  `market://details?id=...`); pages recognized as games late are closed
  automatically.
- Learned game package cache persisted in Play Store `SharedPreferences`
  (survives restarts, rescan on miss).
- Drop leftover loading spinners when entries are hidden.

### Notes

- Hooks only — no Play Store APK modification; all hooks are guarded with
  try/catch.
- Signed release builds via CI (GitHub Actions) on `v*` tags.
