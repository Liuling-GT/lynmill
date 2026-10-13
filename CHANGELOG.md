# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

## [1.1.0] — 2026-10-12

### Added

- **Four UI accent colors** — violet (default), blue, green and amber. Pick one in
  Settings; the choice is remembered locally and also drives the desktop tray icon.
- **Chinese / English UI** — the whole interface, the tray menu and error messages
  switch language. English mode is verified to contain no Chinese text at all.
- **`GET/PUT /api/preferences`** — lets the browser UI hand the chosen language and
  theme back to the desktop layer, so the tray menu follows the web UI instead of
  drifting out of sync with it.
- **Excel (`.xlsx`) data source** — the fifth data source type. Reads real workbooks,
  including the awkward cases: multi-line cells, CJK text, and dates.
- **LICENSE and THIRD-PARTY.md in every package.** Previously the packages shipped
  without either, which was a compliance gap for MIT-licensed third-party code.

### Changed

- **Much smaller runtime: 128.5 MB → 46.9 MB (a 63.5 % reduction).**
  The bundled runtime is now built with `jlink` and only the modules the app actually
  uses (~26 of them) instead of the full JRE. Effect on download size:

  | Package | 1.0.0 | 1.1.0 | Saved |
  |---|---|---|---|
  | Windows `.zip` | 90.6 MB | 76.4 MB | 14.2 MB |
  | macOS `.dmg` | 94.6 MB | 76.3 MB | 18.3 MB |
  | Linux `.tar.gz` | 90.9 MB | 77.6 MB | 13.3 MB |
  | Linux `.deb` | 75.0 MB | 73.6 MB | 1.4 MB |
  | Linux `.rpm` | 90.7 MB | 77.6 MB | 13.1 MB |

- **Licensed under MIT** (previously unlicensed).
- Version bumped to 1.1.0 across the backend, frontend, packaging scripts and docs.
- Comment style unified across the whole repository, and the README/NOTES pair is now
  maintained in both English and Chinese.

### Fixed

Five packaging defects that only showed up when building on Linux or macOS — the
Windows build never hit any of them:

- The bundled runtime tried to include `jdk.crypto.mscapi`, a **Windows-only** module.
  `jlink` aborted on Linux and macOS with `Module jdk.crypto.mscapi not found`.
- The application icon was silently ignored on Linux: the `.ico` file was passed where
  a PNG was required, so packages shipped with the generic Java icon.
- The app-image was built twice (once by the runtime builder, once by the platform
  script), which failed with `Application destination directory … already exists`.
- The size report always printed `runtime 0.0 MB / app 0.0 MB`, because it measured
  the wrong paths on Linux and macOS layouts.
- The runtime size summary assumed the Windows layout (`<app>/runtime`); Linux and
  macOS use `<app>/lib/runtime`.

### Security

- No known vulnerabilities. Passwords are stored in the OS credential manager
  (Windows Credential Manager / macOS Keychain / Linux Secret Service) and are never
  written to disk unless the user explicitly ticks "Save password".

## [1.0.0] — 2026-09-16

First release.

- Query CSV, Excel, MySQL, PostgreSQL and SQLite data with plain SQL.
- Columnar in-memory store, so aggregate queries over hundreds of thousands of rows
  are fast.
- Chart panel, `ORDER BY`, backend pagination, CSV export.
- CSV encoding selection with auto-detection.
- Desktop app (system tray + local web UI) for Windows, macOS and Linux.

[Unreleased]: https://github.com/Liuling-GT/lynmill/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/Liuling-GT/lynmill/releases/tag/v1.1.0
[1.0.0]: https://github.com/Liuling-GT/lynmill/releases/tag/v1.0.0
