# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing yet.

## [1.2.0] — 2026-10-16

Two themes: **export beyond CSV**, and a **rebuilt SQL parser**.
The parser work was not on the original plan — it turned out to be a prerequisite,
because the old parser split SQL with `indexOf` and regular expressions, so perfectly
ordinary statements (a column whose name contains `from`, a quoted identifier) were
parsed incorrectly. Building export on top of that would have been building on sand.

### Added

**Export**

- **Export to Excel (`.xlsx`)** — written directly rather than through Apache POI, so
  the app stays a single small jar. Numbers are written as numbers (not text), so
  Excel can sum and sort them; the header row is bold and frozen.
- **Export to PDF** — paginated A4, header row repeated on every page, with a
  **CJK font embedded in the jar** so an exported report looks the same on a machine
  that has no Chinese fonts installed. This matters for the "send the report to
  someone else" case.
- **Charts export to PDF** — chart type, colours, Y-axis range and the optional data
  table below the chart all match what the on-screen ECharts chart shows.
- **PDF layout preview** — check the pagination before downloading.

**SQL**

- **Column aliases: `select 城市 as c`** — result headers use the alias. Aliases are
  kept separately from the column list internally, so `count(*)` prefix matching and
  column-lookup logic keep working unchanged. Expressions may omit `as`
  (`select sum(x) 总额`, `select amount * 2 双倍`); a bare column may not, because
  `select city c` is indistinguishable from "a column name containing a space" —
  and such column names really do exist in the wild.
- **`in (...)` / `not in (...)`** — expanded at parse time into `or` / `and` chains.
- **`between a and b` / `not between a and b`** — expanded at parse time into
  `>= and <=` / `< or >`.
- **Quoted identifiers** — `` `x` ``, `"x"` and `[x]` are all accepted, delimiters are
  stripped at the lexical layer, and the escape rules match SQLite:
  - Doubling works **inside a delimiter of the same kind**: `` `a``b` `` → `` a`b ``,
    `[a]]b]` → `a]b`, `"a""b"` → `a"b`.
  - A different quote character inside needs no escaping at all: `` `a'b` `` → `a'b`.
  - Bare delimiters are **rejected** rather than silently matched: `select a`b` now
    reports "unterminated delimiter". SQLite rejects it too.
- **Keywords usable as column names** — a column actually named `from`, `order`,
  `limit`, `and` etc. is no longer mistaken for syntax.

### Changed

- **The parser was split into three layers**: a tokenizer
  (`parser/lex/Tokenizer`, the only component that touches characters), a
  token-only parser (`Parser`, which never looks at characters), and a semantic
  analyser (`SemanticAnalyzer`). The old `SQLParser` is now a thin orchestrator.
- **All parse-time validation moved into `ParserChecks`** (18 call sites), so error
  messages live in one place.
- **A 114-statement behaviour baseline** (`src/test/resources/parser-baseline.tsv`)
  now pins parser behaviour, guarded by `ParserEquivalenceTest`. It exists because a
  three-layer rewrite can silently change behaviour; the baseline catches that. It
  did: it caught a real regression during alias work, where `select city_as from_x
  from t_from_x` started splitting into a column and an alias.
- **Error messages are fully localised.** The Chinese UI no longer shows English SQL
  errors. `ColumnStore` was still hardcoding `"error:Unknown column:"` — the earlier
  i18n pass covered the parser layer and missed the execution layer. Five more
  one-directional gaps were found and fixed in the same sweep (they were invisible in
  Chinese, wrong in English). A new source-scanning test
  (`HardcodedUserMessageAuditTest`) now fails the build if a user-visible message is
  hardcoded instead of going through `Messages`.

### Fixed

A 584-statement differential corpus (run against the packaged app over HTTP) went from
**147 mismatches to 0**. Beyond the parser rewrite, the corpus exposed:

- **Nested delimiters** — a quote character inside a delimited identifier closed it
  early, so `` `a"b` `` was cut short and `` `'''` `` (a column genuinely named
  `'''`) resolved to a *different* column. That last one is the dangerous kind of bug:
  the query **succeeds** and returns the wrong data. Root cause was `unquote` in the
  execution layer stripping the leading and trailing quotes off a delimited
  identifier, mistaking it for a string constant.
- **`]]` inside brackets** ended the identifier prematurely.
- **`where` type checking happened per cell rather than per column** — mixed-type
  columns now report "value is not a valid number" instead of silently mis-comparing.
- **Trailing comma in `SELECT`** and an **empty column list** were silently ignored;
  both now report "empty column name".
- **Excel data sources misread numeric columns as text**, and three chart-export
  visual defects (Y-axis range not matching the UI, inconsistent dialog row heights).
- **CORS did not expose `Content-Disposition`**, so the browser could not read the
  export file name cross-origin.

Five defects in the **corpus and test data itself** were found and fixed — worth
noting because they masquerade as product bugs. The test-data generator used
`csv.writer`'s default quoting, which re-escaped quote characters already present in
column names (intended `a"b`, written as `"a""b"`), and it emitted two duplicate
column names, which `ColumnStore` merged — a 35-column CSV surfaced as 33 columns.

### Verified

- **921 unit tests pass** (911 at 1.1.0), 20 skipped.
- **584 / 584 differential statements match expected behaviour** (0 mismatches),
  run against the packaged Windows build over HTTP.
- **27 / 27 hands-on acceptance checks pass** on the deployed build.
- Package self-check: 17/17 checks, 29 runtime modules.

### Notes

- **The result-column check was tightened.** The corpus previously compared only
  "expected ok / actual ok", so a query that *succeeded but returned a different
  column* still counted as a pass. Expected column names now come from **real SQLite**
  running the same statement, rather than from hand-written unescaping rules —
  hand-rolled rules drifted from the lexer in both directions (16 false positives from
  over-decoding, 8 from under-decoding).
- **Still not supported**: `is null` / `is not null`, `like`, `group by`, and
  arithmetic in the projection without an alias (`select age * 2` fails; the engine
  has no expression evaluator). These are pre-existing gaps, unchanged by this release.
- **No installer packages are attached to this release.** The version number is bumped
  and the source is tagged; build the package you need from source with
  `packaging/package.ps1` (Windows), `packaging/package.sh` (macOS) or
  `packaging/package-linux.sh` (Linux).

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

---

## 中文摘要

> 本文件正文按 [Keep a Changelog](https://keepachangelog.com/) 惯例用英文书写。
> 下面是各版本的中文摘要；**详细内容以英文正文为准**。

### [1.2.0] — 2026-10-16

**两条主线：导出不再只有 CSV，以及 SQL 解析器重建。**

**新增（导出）**：导出 Excel（数字按数值写，可求和排序；表头加粗冻结）、
导出 PDF（A4 分页、每页重复表头、**中文字体内嵌**所以换台电脑也不缺字）、
图表导出 PDF（配色与 Y 轴范围跟界面一致）、PDF 布局预览。

**新增（SQL）**：列别名 `select 城市 as c`（表达式可省略 `as`）、
`in` / `not in`、`between` / `not between`（都在解析期改写成已有的比较组合）、
定界标识符 `` `x` `` `"x"` `[x]`（转义规则对齐 SQLite，实测核对；
裸定界符改为报错而不是静默匹配）、关键字可作列名。

**变更**：解析器拆成词法 / 语法 / 语义三层，旧 `SQLParser` 退化为薄编排；
解析期校验集中到 `ParserChecks`；建立 **114 条行为基线**由 `ParserEquivalenceTest` 守着
（它在别名改造中真的抓到一次回归）；报错文案彻底本地化（执行层 6 处漏翻已修，
并新增扫描源码的测试防再漏）。

**修复**：**584 条对拍从 147 条不符降到 0**。其中最重要的是一处
**「查询成功但取错列」**——执行层 `unquote` 把定界标识符当字符串常量剥引号，
列 `'''` 被取成了 `'`。另有：方括号内 `]]` 提前收尾、
Excel 数据源数值列误判为文本、图表导出三处观感问题、
跨域未暴露 `Content-Disposition`。
此外修掉了**语料与测试数据自身 5 处错误**（重复列名导致 35 列并成 33 列等）。

**验证**：单元测试 921 项通过（1.1.0 时 911）；584 / 584 对拍一致；
真实应用 27 / 27 通过；包自检 17/17。

**不附安装包**：版本号已提升、源码已打 tag，安装包需自行从源码构建。

### [1.1.0] — 2026-10-12

四种界面主题色；中英双语界面（含托盘菜单与报错）；
`GET/PUT /api/preferences` 让浏览器界面把语言与主题回传给桌面层；
Excel 数据源（第五种）；补上 LICENSE 与 THIRD-PARTY.md。
运行时用 `jlink` 裁剪：**128.5 MB → 46.9 MB**。改用 MIT 许可。
修掉 5 个只在 Linux / macOS 构建时出现的打包缺陷。

### [1.0.0] — 2026-09-16

首个版本。CSV / Excel / MySQL / PostgreSQL / SQLite 统一用 SQL 查询；
列式内存存储；图表面板、`ORDER BY`、后端分页、CSV 导出；
CSV 编码自动探测；Windows / macOS / Linux 桌面程序（托盘 + 本地 Web 界面）。

---

[Unreleased]: https://github.com/Liuling-GT/lynmill/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/Liuling-GT/lynmill/releases/tag/v1.2.0
[1.1.0]: https://github.com/Liuling-GT/lynmill/releases/tag/v1.1.0
[1.0.0]: https://github.com/Liuling-GT/lynmill/releases/tag/v1.0.0
