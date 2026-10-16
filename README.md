# LynMill

**English** | [中文](README.zh-CN.md)

**A local, zero-ops SQL query engine for multiple data sources.** Drag in CSV / Excel files, MySQL, PostgreSQL, or SQLite, and query them with SQL — no database to install, no code to write.

A single executable with a bundled runtime, so no JDK installation is required. Your data never leaves your machine.

---

## Table of Contents

- [What Is This](#what-is-this)
- [Features](#features)
- [Supported Data Sources](#supported-data-sources)
- [Download and Run](#download-and-run)
- [⚠️ First Launch Prompt (Unsigned Application)](#-first-launch-prompt-unsigned-application)
- [Quick Start](#quick-start)
- [SQL Support and Limitations](#sql-support-and-limitations)
- [Where Data Is Stored](#where-data-is-stored)
- [Security](#security)
- [Build from Source](#build-from-source)
- [Known Issues](#known-issues)
- [Third-Party Dependencies and Licenses](#third-party-dependencies-and-licenses)
- [About This Project](#about-this-project)
  - [How It Was Built](#how-it-was-built)
  - [Disclaimer](#disclaimer)
  - [Feedback and Contributions](#feedback-and-contributions)
  - [License](#license)

---

## What Is This

Say you want to query a CSV file. You either open it in Excel (which dies at a few hundred thousand rows) or install MySQL and import the data just to run one SQL statement. LynMill aims to shorten that to "double-click → drop in a file → write SQL."

It does this by loading data into an in-memory **columnar store** and executing queries in a **vectorized** fashion. That makes scan-heavy queries (`count(*)`, filtering, aggregation) far faster than row-by-row processing.

**Good for**: up to a few million rows, single machine, ad-hoc or repeated analysis of the same dataset.
**Not for**: terabyte-scale data, multi-user concurrency, or workloads that need transactional writes — use ClickHouse / DuckDB / a real database for those.

---

## Features

- **Many data sources, one SQL interface**: CSV, Excel, MySQL/MariaDB, PostgreSQL, and SQLite all become queryable through the same SQL once registered — you can even analyze the results further
- **Columnar storage + vectorized execution**: fast scans and aggregations, well suited to wide tables and large-scale filtering
- **Paginated results**: 50 rows per page by default, so large result sets don't flood the browser
- **Charts**: render query results directly as line/bar/pie charts, with aggregation computed server-side
- **CSV export**: includes a UTF-8 BOM so Chinese column names open correctly in Excel; the exported range matches the UI, and `limit` / `offset` apply as well
- **Excel (`.xlsx`) export**: numbers are written as real numbers rather than text, so Excel can sum and sort them; written directly rather than through a heavyweight library, keeping the download small
- **PDF export**: paginated A4 with the header row repeated on each page, and a **CJK font embedded in the app** so exported reports render correctly on machines with no Chinese fonts installed — reordering rows, collapsing groups and printing to hand out all work
- **Chart export to PDF**: line/bar/pie charts export with the same colours, Y-axis range and optional data table as the on-screen chart; a **PDF layout preview** lets you check pagination before downloading
- **Persistent data sources**: registered data sources are written to `datasources.json` and restored automatically on the next launch
- **Optional password storage**: when enabled, passwords go into the **system credential manager** (Windows Credential Manager / macOS Keychain / Linux Secret Service), never into a config file
- **Chinese-friendly**: automatic CSV encoding detection (UTF-8 / GBK), BOM stripping, and support for Chinese paths and file names
- **UTF-8 logging**: log files are always written as UTF-8, so Chinese text never breaks because of the system locale
- **Local-only listener**: binds to `127.0.0.1` with a one-time access token, unreachable from other machines on the LAN

---

## Supported Data Sources

| Type | Required fields | Notes |
|---|---|---|
| **CSV file** | File path | Configurable encoding, delimiter, comment prefix, quote awareness |
| **Excel file** | File path + sheet name | `.xlsx`. Leave the sheet name empty to read the first sheet; formula cells are read as cached values only |
| **SQLite file** | Database file path + table name | Each table in a file is registered as a separate data source |
| **MySQL / MariaDB** | Host, port, database, table, username, password | Runs `SELECT *` to load the whole table; watch memory on large tables |
| **PostgreSQL** | Host, port, database, schema, table, username, password | Same as above; unquoted identifiers fold to lowercase, so write them in lowercase |

> **The "name" you give at registration time is the table name in SQL.** For example, if you register a source named `sales`, later you write `select * from sales`.

### Why There Is No PDF Data Source

LynMill **does not support PDF**. An early version read "bordered tables only," but after real-world testing that support was **deliberately removed**. The reasoning is worth spelling out, because "reads some of them" sounds better than "not supported" but isn't:

**1. A PDF contains no "table," only lines and positioned text.** Reconstructing rows and columns from those is necessarily an **inference**, and inferences are sometimes wrong. The best open-source solution in the field (Camelot, built specifically for PDF tables) reaches only about **0.78** F1 on the established ICDAR-2013 benchmark — roughly one in five is wrong.

**2. The files that matter most are exactly the ones it can't rescue — PDFs exported from Excel.** In these files, cell text **overflows its own column boundary** (sometimes across rows), while the ruling lines are drawn along visual rows. So **the "cells" cut out by the lines are not the same as the text's logical cells**:

```
┌────┬──────────────────────────┐
│    │ 阶段 0 · 代码改造（收        │  ← 文字跨过竖线
│  1 │ 尾端）端口策略改造：首选 8080 │
└────┴──────────────────────────┘
   实际得到：  列1="阶段 0 · 代码改造（收"   列2="尾端）端口策略改造：…"
```

All the file says is "there is a character at this coordinate." **Nothing states that "（收" and "尾端）" belong to the same cell** — both readings are self-consistent. Reconstructing it means guessing.

**3. Switching libraries or "convert to Excel first, then read" doesn't help.** Both were tried: `pdfplumber` (the most widely used in the field) produces the **exact same** wrong split on the same file, because it sees the same ruling lines we do. And "convert to Excel first" is worse — the error gets laundered into an **xlsx that looks normal**, so users assume the original file was written that way, **turning a detectable failure into an undetectable error**.

**4. So this is a trade-off, not a missing capability.** With a badly reconstructed table, `select count(*)` still returns a row count and the column names still look plausible, so users rarely notice the numbers landed in the wrong columns. **That kind of "looks right, is wrong" result is far more dangerous than an explicit statement that something isn't supported.**

**If a table only exists as a PDF**: find the original Excel/CSV file and import it directly (most reliable). If a PDF really is all you have, use "PDF to Excel" in Acrobat / WPS, **verify the conversion yourself**, and then import it. Conversion tools are guessing too, and that verification step is not optional.

---

## Download and Run

Grab the package for your platform from the **[Releases page](https://github.com/Liuling-GT/lynmill/releases/latest)**.
Each release ships a `.sha256` / MD5 checksum alongside the files if you want to verify the download.

The application is **portable**: unzip it and run — no need to install Java first (the runtime is bundled).

### Windows

1. Unzip `LynMill-1.2.0-win.zip`
2. Double-click `LynMill.exe` inside

> Don't drag `LynMill.exe` out on its own — it needs the `app\` and `runtime\` directories alongside it to start.

### macOS

1. Open `LynMill-1.2.0.dmg` and drag LynMill into Applications
2. For the first launch, see the workaround in the next section

> The current build is for **Apple Silicon (M1–M4)**. On an Intel Mac, build from source or run the jar directly (see [Build from Source](#build-from-source)).

### Linux

**Option 1: Archive (recommended, works on all distributions)**

```bash
tar -xzf LynMill-1.2.0-linux-x86_64.tar.gz
./LynMill/bin/LynMill
```

**Option 2: Installer package**

```bash
sudo dpkg -i lynmill_1.2.0-1_amd64.deb     # Debian / Ubuntu
sudo rpm -i lynmill-1.2.0-1.x86_64.rpm     # Fedora / RHEL
```

After installation, launch it from the application menu.

> The UI requires **Chinese fonts** to be installed, otherwise Chinese text in the console window and tray menu appears as boxes.
> Install them with: `sudo apt install fonts-wqy-microhei` (about 5 MB) or `fonts-noto-cjk` (about 91 MB, broader coverage).
> The `.deb` declares `Recommends` for both packages, so `apt` usually pulls one in automatically;
> the `.rpm` declares nothing, so you must install one yourself.

---

## ⚠️ First Launch Prompt (Unsigned Application)

**This is expected behavior — not a corrupted file, and not a virus.**

This is an open-source, non-profit project without a code-signing certificate (both Windows certificates and an Apple Developer account cost money). So the system reports an "unknown publisher." Allow it once using the method below and it won't prompt again.

### Windows: SmartScreen blocked it

When you see "Windows protected your PC":

> Click **More info** → click **Run anyway**

**If you downloaded it from a browser** (cloud drive, QQ, email attachment, etc.), this prompt always appears.
**If you copied it over on a USB drive or over the LAN**, it usually won't — local copies carry no "mark of the web."

### macOS: Gatekeeper blocked it

When it says the app "cannot be opened because the developer cannot be verified," **pick either option**:

- **Right-click the icon → choose "Open" → click "Open" again** (double-clicking directly won't work)
- Or run this once:
  ```bash
  xattr -dr com.apple.quarantine /Applications/LynMill.app
  ```

### Linux

Normally nothing blocks it. If you get a permission error:

```bash
chmod +x LynMill/bin/LynMill
```

---

## Quick Start

1. Launch the application; it opens your browser to the UI automatically (if not, visit the address printed in the log)
2. On the left, go to "Data Sources" → "Add", pick a type, fill in the parameters, and give it a name
3. Write SQL in the query box and click Run

**Example**: suppose you registered a CSV named `sample` (columns: `name, age, city, salary`)

```sql
select * from sample                       -- all rows
select name, age from sample where age > 25 -- filter
select * from sample order by salary desc limit 10   -- sort and take top 10
select count(*), avg(salary) from sample   -- aggregate
select city, count(*) from sample where age > 20   -- note: no GROUP BY, see limitations below
```

> **Table names may be quoted** (supported since 1-2). `` `sample` ``, `"sample"`, `[sample]` all have their quotes stripped at parse time and are equivalent to `sample`.
> Quoting is the necessary form when a name contains spaces, commas, or SQL keywords: `` from `对拍 引号 表` ``.
> **But you cannot nest quotes inside a quote** — see the "Not Supported" table below.

---

## SQL Support and Limitations

This is a **lightweight query engine**, not a full SQL database. It supports a commonly used subset.

### Supported

| Category | Syntax |
|---|---|
| Projection | `select a, b`, `select *` |
| Filtering | `where`, `and` / `or` / `not`, `=` `!=` `<>` `>` `>=` `<` `<=`, parentheses around conditions |
| Sorting | `order by` one or more columns `[asc\|desc]`, e.g. `order by city asc, salary desc` |
| Pagination | `limit n`, `limit n offset m`, `offset m` (the server also has separate page / pageSize pagination) |
| Aggregation | `count(*)`, `count(column)`, `sum`, `avg`, `min`, `max` |
| Delimited identifiers | Table or column names wrapped in `` ` `` / `"` / `[]`; the quotes are stripped. Use when a name contains spaces or keywords |
| Column aliases | `select a as alias`, `select sum(x) as total`. The alias changes only the result header; `where` / `order by` still use the **original column name** |
| `in (...)` / `not in (...)` | Expanded automatically: `c in ('a','b')` means `c = 'a' or c = 'b'`; `not in` uses `and` + `!=` |
| `between a and b` | Expanded automatically: `n between 1 and 10` means `n >= 1 and n <= 10`; `not between` is the reverse |

### Not Supported (reports an error)

| Syntax | Notes |
|---|---|
| `select a alias` (omitting `as`) | Two bare identifiers cannot be told apart from a column name containing a space; write `as` in full. Expressions may omit it: `select amount * 2 double` works |
| `group by` | **Deliberately omitted.** Aggregation is currently whole-table, not grouped. Use the chart feature to see distributions |
| `is null` | **Deliberately omitted.** Null semantics are not syntactic sugar and cannot be expanded |
| `like` | **Deliberately omitted.** Pattern matching is likewise not syntactic sugar |
| `limit m, n` | Argument order is reversed from the standard and easy to misread; use `limit n offset m` instead |
| `select distinct` | Not supported |
| `join` / subqueries / `union` | Not supported |
| Improperly escaped quotes inside a quote | `` `a"b` `` **is** supported, but `` `a`b` ``, `"a"b"`, `[a]b]` written **without** the escape rule report an error. The correct form doubles the same-type quote: `` `a``b` ``, `"a""b"`, `[a]]b]` |

> This table has previously contained two kinds of errors, where the docs claimed something the implementation didn't do (or vice versa):
> `in` / `between` / `is null` were wrongly listed as "supported", `select a as alias` was likewise wrongly listed as "supported",
> and the implemented "parentheses around conditions" was listed as "not supported".
> **This table is now checked entry by entry against actual behavior by `ReadmeAudit`** (see section 4 of [NOTES.md](NOTES.md)).
>
> See [NOTES.md](NOTES.md) for a fuller description of limitations and defects.

### ⚠️ Error Messages Can Be Misleading

There is a known class of inaccurate error messages: **when you write unsupported syntax, the error may say "data source not found"**, even though the data source is fine and the real problem is the unsupported syntax.

For example:

```
select * from sample join other on sample.id = other.id
→ 报：数据源不存在：sample join other on sample.id = other.id
```

It treats everything after `from` as the table name. **If you get "data source not found" but the name looks correct, first check whether you used syntax the table above lists as unsupported.**

(The root cause is that the parser has no notion of `join` / `union` / subqueries / `group by`, so it keeps reading to the end of the statement as the table name. This is a known issue, not yet fixed — see section 2.2 of [NOTES.md](NOTES.md).)

> Since 1.2.0 the parser no longer splits on spaces, so the *original* cause of this
> class of message is gone — but the message still appears for the unsupported
> constructs above, because there is genuinely no clause to stop at.
>
> Also note `select distinct a` reports **"column not found: distinct a"** rather than
> "not supported" — misleading in the same way. Write `group by` / `distinct` / `join`
> out of your query rather than trying to work around the message.
>
> `limit m, n`, `is null`, `like`, and mixing plain columns with aggregates **do** have
> dedicated messages that say what is wrong instead of blaming the data source.

---

## Where Data Is Stored

The application's own files (data source config, logs, lock file) live in the **user data directory**, not in the application directory:

| Platform | Path |
|---|---|
| Windows | `%APPDATA%\LynMill\` |
| macOS | `~/Library/Application Support/LynMill/` |
| Linux | `~/.local/share/LynMill/` (respects `$XDG_DATA_HOME`) |

Directory contents:

```
datasources.json     registered datasources (no passwords)
tinyquery.lock       single-instance lock (process-level, released on exit)
tinyquery.url        access URL of this instance
logs/tinyquery.log   runtime log - check here first when something breaks
```

The `LYNMILL_HOME` environment variable overrides this entire directory (useful for portable deployments).

**Uninstalling does not delete this directory**, nor any saved passwords (see below).

---

## Security

- **Listens only on `127.0.0.1`**, unreachable from the LAN or the internet
- **Every API requires a token**: generated randomly at process startup and injected into the home page; requests without a token get a 401
  - Header: `X-LynMill-Token`
  - The token changes on every launch
- All data stays in memory; apart from the files you explicitly register, nothing else is read or written
- **Passwords**: when "Save password" is checked, they go into the system credential manager under the entry name `LynMill/<datasource name>`; **if unchecked, nothing is ever written to disk**

> ⚠️ **Uninstalling does not clear saved passwords.** Delete them yourself:
> - Windows: Control Panel → Credential Manager → Windows Credentials → find `LynMill/...`
> - macOS: Keychain Access → search for `LynMill`
> - Linux: search for `LynMill` in a keyring manager such as Passwords and Keys (seahorse), or run
>   `secret-tool clear key 'LynMill/<datasource name>'`

---

## Build from Source

### Requirements

| | Version |
|---|---|
| JDK | **17** (jpackage ships with the JDK from JDK 14 onward) |
| Maven | 3.9+ |
| Node.js | **`^22.18.0 \|\| >=24.12.0`** |

### Build

```bash
# 1. Frontend (must come first: the backend build enforces a "dist freshness" guard)
cd tinyquery-frontend
npm install
npm run build

# 2. Backend + tests
cd ..
mvn clean package          # produces target/tinyquery-1.2.0.jar and runs the full test suite

# 3. Package the portable app
bash    packaging/package-linux.sh     # Linux: tar.gz (add --deb / --rpm for installer packages)
pwsh -File packaging/package.ps1       # Windows
bash    packaging/make-icns.sh         # macOS: generate the icns from PNG first
```

The header comment of every script under `packaging/` explains **why it works that way** (pitfalls hit, platform constraints) — worth reading before making changes.

### Running the Jar Directly (no packaging)

```bash
java -jar target/tinyquery-1.2.0.jar
```

This path works on **any machine with JDK 17**, including platforms with no ready-made installer (Intel Mac, for example). In this mode it runs as a "development mode" build: it **does not open the browser automatically**, so you need to visit the address from the log manually.

### Running Tests

```bash
mvn test
```

About 340 tests, some of them platform-specific (Windows and macOS each skip the other's), so "skipped" counts differ by platform — that's normal.

---

## Known Issues

| Issue | Impact | Notes |
|---|---|---|
| **Unsupported syntax reports "data source not found"** | Hard to diagnose | See [the note above](#-error-messages-can-be-misleading) |
| **Linux needs a keyring to actually save passwords** | Passwords not saved | Secret Service (libsecret) support is implemented, but the **system must have a working keyring** (GNOME Keyring / KWallet). Server editions, minimal distributions, and bare window-manager setups often lack one or don't start it, in which case "save password" errors out and you must retype it every time. Install with: `sudo apt install gnome-keyring libsecret-1-0` |
| **Menus show boxes when Linux lacks Chinese fonts** | Cosmetic | Install `fonts-wqy-microhei` (about 5MB) or `fonts-noto-cjk`. The `.deb` already declares `Recommends`, so `apt` usually installs one automatically |
| **`.deb` can't be removed on very minimal systems** | Uninstall | Affects only minimal installs / WSL / headless servers. Regular desktop distributions are **unaffected** (confirmed by testing). Workaround: `sudo dpkg --purge --force-all lynmill`. Details below |
| **Large package size** | About 180 MB | jpackage bundles the full JDK by default; jlink could later trim this to 60–80 MB |
| **No installer for Intel Mac yet** | Build it yourself | You can run it via the jar |

### About `.deb` Removal Failures (Linux)

> **The short version: regular desktop distributions don't hit this.** Verified on a real Ubuntu VM: `sudo dpkg -r lynmill` completes normally and removes both the application files and the menu entry. What follows applies to **minimal installs** (servers, WSL, container images).

Symptom: on a minimal system, `sudo dpkg -r lynmill` reports

```
xdg-desktop-menu: No writable system menu directory found.
dpkg: error processing package lynmill (--remove):
 installed lynmill package pre-removal script subprocess returned error exit status 3
```

Cause: the `prerm` script generated by jpackage uses `set -e` and calls `xdg-desktop-menu uninstall`. That command **also handles `*.directory` files**, and that code path requires `/usr/share/desktop-directories` to exist and be writable — minimal installs don't have it, so the command exits with status 3, `set -e` aborts the whole removal, and `/opt/tinyquery` is left untouched.

(That directory comes from desktop components, so any distribution with a desktop environment has it.)

Workarounds (pick one):

```bash
# 1) Force-remove
sudo dpkg --purge --force-all lynmill

# 2) Or restore the missing directories, then uninstall normally
sudo mkdir -p /usr/share/desktop-directories
sudo dpkg -r lynmill
```

**Also**: after `dpkg -r`, `dpkg -l lynmill` shows status `rc`. That is **normal** — it means "removed, but dpkg still keeps the package record" (because the package includes the `/opt` directory entry). The application really is gone. To clear the record as well:

```bash
sudo dpkg --purge lynmill
```

---

## Third-Party Dependencies and Licenses

Main dependencies: Spring Boot, Vue 3, Element Plus, JNA, and the JDBC drivers for each database.

The complete dependency list, versions, and licenses (including notes on LGPL-class dependencies) are in [`packaging/THIRD-PARTY.md`](packaging/THIRD-PARTY.md).

---

## About This Project

LynMill is a personal learning project by a university student, written in spare time outside coursework, and built with heavy large language model assistance.

**It is not a commercial product, and not production-grade software.** Please read this section before using it or contributing.

The purpose is learning, specifically two things:

1. **Building a cross-platform data query engine** — how to load heterogeneous data sources into a columnar store, execute SQL over them in a vectorized way, and ship the result as a desktop application that runs on Windows, macOS, and Linux.
2. **Practicing AI-assisted software engineering** — how to work effectively with a large language model on a codebase of real size, including the parts that are *not* writing code: design boundaries, testing strategy, regression baselines, packaging, and release process.

### How It Was Built

- **The design intent is the author's.** Module decomposition, the design intent of each core component, and the boundaries of what the project will and will not do were decided by the author.
- **The AI did a large share of the implementation.** It wrote a substantial portion of the code, and much of the engineering process around it — test scaffolding, packaging scripts, and parts of the tooling.
- **Not every line was hand-written or line-by-line reviewed by the author.** That is a direct consequence of working this way, and it is stated here rather than left implicit.

What the author does stand behind is the design, the direction, and the acceptance criteria. If you find a problem — in code the AI wrote or anywhere else — please open an issue. It will be taken seriously and handled properly.

### Disclaimer

LynMill is provided **as is**, without warranty of any kind, express or implied.

- The project has made a genuine effort to be **usable**, but makes **no guarantee that it is suitable for production use**.
- **Evaluate the risk yourself before relying on it.** For anything important, verify the results independently.
- The author is a student. There is **no support commitment**, no service-level expectation, and no guarantee of timely responses.
- Data stays on your own machine and you are responsible for it — including backups.

### Feedback and Contributions

Feedback is genuinely welcome. Two things to understand first:

- **This is a personal project.** The author's priority is coursework, so maintenance time is limited and **responses may be slow**. Issues are not ignored — they are queued behind school.
- **Check the docs first.** Before opening an issue, please read this README and the [Known Issues](#known-issues) section, and confirm the problem is not already documented.

**Reporting a problem.** Open an issue. Useful reports include what you did, what you expected, and what actually happened; your operating system and how you ran LynMill (portable build or from source); the exact SQL and the data source type involved; and log excerpts if relevant — see [Where Data Is Stored](#where-data-is-stored) for log locations.

If the problem lies in a part of the code that was AI-assisted, that is still worth reporting. Pointing it out is helpful, and it will be fixed.

**Before opening a pull request,** please open an issue first to agree on the direction. This avoids wasted effort on both sides — a change may already be planned, may conflict with a design decision, or may not fit the project's scope.

### License

LynMill is released under the **MIT License**. See [LICENSE](LICENSE) for the full text.

In short: you may use, modify, and redistribute it, including commercially, provided the copyright notice and license text are kept. The software comes with no warranty.

Third-party components bundled in the release are covered by their own licenses — see [`packaging/THIRD-PARTY.md`](packaging/THIRD-PARTY.md).
