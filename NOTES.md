# NOTES.md · Known Defects and Known Limitations

**English** | [中文](NOTES.zh-CN.md)

This file collects **the things the current version deliberately does not do**, **the places known to break**, and **behaviors that are easy to misdiagnose as bugs**.

The README is for users and talks about "what it can do"; this one is for maintainers and troubleshooters and talks about "where it will trip you up."

**Two categories, handled completely differently:**

| | Meaning | What to do |
|---|---|---|
| **Known defect** | We **think it should work**, but it does not right now | Fixes welcome; read the root cause in the entry first |
| **Known limitation** | We **chose not to do it**, for tradeoff reasons | Not a bug — do not "fix" it in passing; overturn the rationale first |

---

## 1. Known Limitations (Intentional)

### 1.1 `GROUP BY` is not supported

**Current state**: `select count(*) from t group by 城市` **is not stopped** — the parser takes `t group by 城市` as the table name, and the query fails with "Data source not found". `group by` has **no dedicated error message of its own**; see 2.4.

**Rationale**: the current execution model is **whole-table aggregation** — all data collapses into one row. What `group by` needs is grouped aggregation, which is a separate execution path (a grouping hash table plus independent output per group). It is not a matter of adding a keyword.

**Workarounds**:
- Only need the grand total → just use `count(*)` / `sum(c)` etc.
- Need the distribution by dimension → use the **chart feature**. Internally it goes through the grouped-aggregation endpoint (`/api/query/chart`), which can group by dimension and supports Top N — exactly the common use case for `group by`

**Note**: mixing a plain column with an aggregate, as in `select 城市, count(*) from t`, is **actively rejected**, and the error says why (an aggregate result has only one row, so a plain column has no row to reference). This is a deliberate check, not a parse failure.

### 1.2 Only 7 comparison operators plus `in` / `between` — no `is null` / `like`

**Supported**: `>=` `<=` `!=` `<>` `>` `<` `=`, plus `in (...)`, `not in (...)`,
`between a and b`, `not between a and b`

**Not supported**: `is null` / `is not null`, `like`

**Why `in` / `between` were done but `is null` / `like` were not**

The distinction is: **can it be rewritten into a form that is already supported?**

`in` / `between` can — they say the same thing in a different way, so the parser
**rewrites them on the spot** into existing comparison combinations, and the layers
below never learn that the user wrote `in`:

| You want to write | What the parser turns it into |
|---|---|
| `c in ('a','b')` | `c = 'a' or c = 'b'` |
| `c not in ('a','b')` | `c != 'a' and c != 'b'` |
| `n between 1 and 10` | `n >= 1 and n <= 10` |
| `n not between 1 and 10` | `n < 1 or n > 10` |

(The usual name for "same meaning, different spelling" syntax is **syntactic sugar**.)

Once expanded into `OrCondition` / `AndCondition`, **the execution layer, charts,
pagination and PDF export need not change a single line** — they only understand
`SimpleCondition(field, op, value)` plus the three combinators. That is exactly why this
route was chosen: new capability without a new execution path, so the risk is low.

`is null` needs **null semantics** (testing for "no value", while how comparison
operators treat nulls is a separate set of rules), and `like` needs **pattern matching**
(a wildcard matcher would have to actually run). Neither can be rewritten into anything
that already exists, so they are genuinely new capabilities and stay on the unsupported
list.

**One trap worth recording**: `between` must be handled **before the top-level AND
split**. The `and` in `n between 1 and 10` belongs to `between`, not to logical
conjunction; splitting on `and` first turns `between 1` into half a condition and
reports "missing and". The position of the `tryParseBetween` call inside
`Parser.parseCondition` is deliberate.

**Argument validation** (each has dedicated copy): `in ()` with an empty list errors,
`in 'a','b'` missing parentheses errors, `between 1` missing `and` errors.

### 1.3 Formula cells read the cached value only (xlsx data source)

Excel formula cells are **not evaluated**; only the cached value computed the last time Excel saved the file is read.

**Rationale**: implementing a formula evaluator amounts to rebuilding an Excel calculation engine (function library, dependency graph, circular-reference detection). The cost and the payoff are wildly out of proportion.

**Symptom**: if the file was generated programmatically and the formulas were never opened by Excel, the cached value may be empty → that cell reads as empty. **This is expected behavior, not a missed read.**

### 1.4 Other xlsx boundaries

- **`.xlsx` only**; `.xls` errors out explicitly and tells you to save as `.xlsx`
- **Merged cells keep only the top-left value** (matching how Excel displays them; the remaining positions are empty)
- Styles and colors are not read (we only care about the data)

### 1.4b PDF data source: built, measured, then removed

**Conclusion**: LynMill **does not offer a PDF data source**. A version that read "tables with ruled borders only" was once implemented (hand-written parsing, zero dependencies, about 3,100 lines of main code + 550 lines of tests) and was **deliberately deleted** after being measured against real files. Why is recorded here, because "it can read some of them" always sounds better than "unsupported" — it is not.

**1. There are no "tables" in a PDF, only lines and text with coordinates.**

Reconstructing rows and columns from those is necessarily an **inference**. The best open-source project in the field (Camelot, purpose-built for PDF tables, with a neural-network mode) scores an F1 of about **0.778** on the accepted benchmark ICDAR-2013 — roughly 22% wrong.

**2. The type of file that matters most is exactly the one it cannot save: PDFs exported from Excel.**

This was the **main motivation** for building the feature — one person converts an Excel file to PDF and sends it to a second person, who only has the PDF.

But on real files of this kind we found: **the text of a cell overruns its own column boundary** (sometimes even across rows), while the ruled lines are drawn along visual rows. So **the "cells" cut out by the ruled lines and the "logical cells" of the text are not the same thing**:

```
┌────┬──────────────────────────┐
│    │ 阶段 0 · 代码改造（收        │  ← 文字跨过竖线
│  1 │ 尾端）端口策略改造：首选 8080 │
└────┴──────────────────────────┘
   实际得到：  列1="阶段 0 · 代码改造（收"   列2="尾端）端口策略改造：…"
```

The file only says "there is a character at such-and-such coordinate"; **nothing in it says that "（收" and "尾端）" belong to the same cell** — both readings are self-consistent. Reconstructing it means guessing.

**3. Swapping libraries does not solve it, and "convert to Excel first" is worse.**

All measured, not speculated:

| Approach | Result |
|---|---|
| Parse it ourselves (the implementation at the time) | Cut in the wrong place |
| `pdfplumber` (the most widely used in the field) | **Exactly the same** wrong cut — because it sees the same ruled lines we do |
| Convert to xlsx first, then read | The error carries over **untouched**, and it turns into an xlsx that **looks normal** |

The third one is the most dangerous: **the error has been laundered**. Seeing `列1 = 阶段 0 · 代码改造（收`, the user will simply assume the original file was written that way and will never suspect the conversion. **Turning a "detectable failure" into an "undetectable error" is a net loss.**

**4. So this is a tradeoff, not a lack of capability.**

For a table reconstructed incorrectly, `select count(*)` still returns a row count and the column names still look plausible, and the user can hardly discover on their own that the numbers landed in the wrong columns. **"Looks right, actually wrong" is far more dangerous than an explicit unsupported.**

**5. Boundaries verified in passing while removing it (for reference if this is ever re-evaluated).**

If it is ever redone, the conclusions below still hold, so nobody has to step on these again:

- PDF text does not store characters but **glyph IDs**, which must be mapped back through the font's `/ToUnicode` table. Without that table, all Chinese is lost (IDs below 32 are treated as control characters) while **numbers look normal** → this must raise an error, never return an empty table
- **Both CMap key-value syntaxes are legal**: `<0001> <0031>` and `<0001><0031>`. Real files use the latter, and **splitting on whitespace pairs up two adjacent rows into one key-value pair, wrecking the entire code table**
- Horizontal lines in real files are **split into multiple segments per cell**, with no single line spanning the whole table → to decide "are there ruled lines", all segments must first be merged by y and only then checked for span
- Real files **emit one `Tj` per character** → concatenation must not blindly insert spaces (column names would become 「城 市」); spacing has to be judged from the character advance
- Scanned documents (no text layer) and "the font cannot be decoded" must be diagnosed separately: the latter **does** have text-drawing operators, and telling the user to go run OCR sends them on a wasted trip

**6. What users should do when they have a PDF (also written in the README).**

Find the original Excel/CSV and import that directly (most reliable); when only the PDF exists, use Acrobat / WPS "PDF to Excel" and **check it over yourself** before importing — conversion tools are guessing too, and the check is not optional.

### 1.4c Build identity: `build-info.properties` is generated at packaging time

**Current state**: at packaging time the antrun `generate-build-info` execution in the pom generates `target/classes/build-info.properties` with three lines:

```
build.version=1.2.0
build.time=2026-10-10 15:02
build.commit=e0dce58
```

At runtime it is read by `BuildInfo` and used in two places: one line in the startup log ("build version"), and one line at the bottom of the settings page (served by `/api/version`).

**Why Ant writes the whole file instead of letting Maven filter placeholders**: resource filtering was tried, and both halves got stuck —

1. A property Maven has already defined **overrides** Ant's `<condition>`: once `build.commit` is declared as empty in `<properties>`, Ant can never fill in a value;
2. If it is not declared, the properties Ant sets are **invisible** to Maven's `resources` filtering and get packaged verbatim (a build once emitted `${git.commit.resolved}`).

Handing it off to a file and reading it back does work, but it needs an extra `.properties` resource plus an extra phase, and the chain gets too long to maintain. Having Ant generate the whole file directly is the simplest: all three values written in one pass, with no filtering in between.

**When git is unavailable, write an empty string, not a placeholder**: git not installed, not a repository, and nothing committed yet all count as "unavailable", and none of them should fail the build. In code an empty string is recognized as "unknown", and the UI **omits that segment** (becoming `1.1.0 · 2026-10-10 15:02`) rather than leaving a dangling "·".

By the same token, when running in development mode directly (`mvn spring-boot:run` / IDE) this file may not be generated, or may still hold `@...@` placeholders — `BuildInfo` recognizes the **leading and trailing `@`** form as "unknown" and omits it. Showing users `@project.version@` looks more like the program is broken than showing nothing.

**Why not "compare timestamps inside the jar"**: a jar is a zip, and the timestamp on every entry is **the moment it was written into the jar**, not the file's original modification time. So "the time of `index.html` inside the jar" is always exactly the packaging moment, and it **cannot tell you whether the frontend is older than the jar**. That approach does not work; the only option is to bake a copy of the metadata in at packaging time.

### 1.5 There is no `application.properties`; configuration lives in the startup class

**Current state**: there is **no** `application.properties` under `src/main/resources/` (and no `.yml` either). Every configuration item is set by `TinyQueryApplication` via `System.setProperty` before `SpringApplication.run`.

**Why this is better**: all three items set here are **values that can only be computed at runtime** —

| Configuration item | Why a static file cannot work |
|---|---|
| `logging.file.name` | The value comes from the **user data directory**, which depends on the OS (`%APPDATA%` / `~/Library/Application Support` / `$XDG_DATA_HOME`). Hardcoding it would be wrong |
| `server.port` | An **idle port has to be probed** (8080 preferred, falling back by incrementing when it is taken). A static file cannot express "which port is free is only known at runtime" |
| `server.address` | The same precedence logic as the port (command line > system property > default) |

**Ordering is also a hard constraint**: logging must be configured **before Spring starts** — `SpringApplication` initializes the logging system during startup, so putting it in a configuration file may well be too late (the file is only read once the environment is ready).

**So do not "helpfully add an `application.properties`"**: there is nothing to put in it, and forcing one in only freezes runtime-computable values into wrong constants.

**When adding a new configuration item**: first decide whether it is **static** (like `spring.mvc.throw-exception-if-no-handler-found`) or **computed at runtime**. Static ones can go in a configuration file; runtime ones belong in `TinyQueryApplication`, following the same "command line > system property > default" precedence.

### 1.6 Data sources stay resident in memory in full, at roughly 5× the file size

**Current state**: once a data source is opened, **the entire table stays on the heap** — no cap, no eviction, no LRU.
The only way to release it is to delete the data source or restart the program.

**Measured numbers** (`MemoryFootprintTool`; methodology: trigger GC before and after loading, compare heap usage, take the minimum of 5 runs):

| Data | File | Rows | Resident heap | Multiple |
|---|---|---|---|---|
| `huge.csv` | 27.4 MB | 1,000,000 | 134.7 MB | 4.92× |
| `orders.csv` | 27.7 MB | 1,000,000 | 144.8 MB | 5.22× |
| `social_media.csv` | 0.9 MB | 8,000 | 5.0 MB | 5.32× |

**Why it inflates 5×**: `ColumnStore` stores columns as `Map<String, List<Object>>`,
every cell is a **boxed object** (an `Integer` is at least 16 bytes, not 4),
on top of the growth slack of every `ArrayList`. Once text is in memory its size necessarily grows.

**The guardrail that exists**: a memory pre-check is run before loading (`MemoryGuard`), and when it clearly will not fit the load is
**refused up front**, with a readable sentence, instead of crashing into an `OutOfMemoryError`. The rules are:

- Estimated requirement = file size × **6** (the measured upper bound; better to refuse wrongly than to let one through)
- Available budget = JVM max heap × **50%** (half is left for the temporary objects of loading/querying and for other data sources)
- Exceeding the budget means refusal, error code `error.memoryInsufficient`, with the message carrying the concrete numbers and the alternatives

**Heap cap in packaged builds**: the `packaging/` scripts pass `-XX:MaxRAMPercentage=50`,
i.e. half of physical memory. Without it the JVM defaults to about 1/4, which varies by machine and gives users nothing to expect.
(Running the jar directly has no such argument and falls back to the JVM default.)

**Remaining limitations**:

- **JDBC data sources get no pre-check** — how big the result set is is only known once you connect, and cannot be estimated in advance.
  This is a known gap: `JdbcLoader` can still OOM
- The pre-check can only estimate from **file size**, and cannot model the real object graph — hence the upper-bound coefficient
- The pre-check does **not** help the user "find a way to make it fit" (no automatic chunking, no spilling to disk)

**What users can do**: switch to a SQLite data source (it reads on demand and does not pull the whole table into memory — the right answer for this situation),
or split the file into smaller pieces externally and filter out rows they do not need.

**Why no eviction (LRU / chunked spilling)**: it would change the semantics of "a data source stays usable once registered" —
registered as usable, yet a query might have to reload it because it was evicted, making behavior unpredictable.
The current positioning is a local lightweight tool, and users have an intuition for how big the file they opened is; a guardrail plus documentation suits it better than automatic eviction.

---

### 1.7 Release packages ship a **trimmed** runtime, and the module list is maintained by hand

**Current state**: at packaging time `jlink` first produces a runtime containing only the required modules (about 47 MB),
which `jpackage --runtime-image` then packages in. The release package dropped from about 176 MB to about 94 MB.

**The module list is maintained by hand in `packaging/make-runtime.py`**, 27 modules in total. This is a deliberate tradeoff:
automatic trimming cannot be made correct, because —

**The answer `jdeps` gives is wrong**. `jdeps --print-module-deps` reports only 4 modules
(`java.base, java.desktop, java.management, java.sql`), because Spring Boot's auto-configuration
is **conditional + reflective** and the dependencies are not in the bytecode. Trimming by that answer makes startup fail outright:

```
org.springframework.context.ApplicationContextException: Unable to start web server
Caused by: java.lang.NoClassDefFoundError: org/ietf/jgss/GSSException
```

What was missing is `java.security.jgss` (needed when Tomcat builds the default Realm), which `jdeps` never mentioned.
Note that the top level reports "cannot start web server", and **the root cause is buried two `Caused by` levels down** —
without unpacking the log you cannot tell it is a module problem at all.

**So the list is based on measurement, not inference**: run the real application with
`java --show-module-resolution -jar ...` and see which modules it actually binds.
The list is split into three sections, each annotated with its basis, so that a future JDK upgrade makes clear which section needs touching.

**Why this failure mode is dangerous**: a missing module **does not fail at packaging time**; it surfaces for the user as
"double-clicked and nothing happened" — the least informative kind of failure there is. In particular these categories:

| Missing module | Symptom | Why it is hard to spot |
|---|---|---|
| `jdk.crypto.ec` | MySQL/PostgreSQL connections fail | **Plaintext connections still work**, so only TLS scenarios expose it |
| `jdk.localedata` | Chinese dates turn into `1970 Jan 1` | No error, just a silent downgrade to English formats |
| `java.security.jgss` | Startup fails outright | The message says "cannot start web server", with no hint that it is a module problem |

**Escape hatch**: if it really goes wrong, package with a full JRE to get unblocked —

```
powershell -File packaging\package.ps1 -FullRuntime     # Windows
./packaging/package.sh -FullRuntime                     # macOS
./packaging/package-linux.sh --full-runtime             # Linux
```

**Acceptance testing is mandatory**: `packaging/verify-package.py` (full chain, including real MySQL/PostgreSQL/SQLite connections)
and `packaging/verify-crypto.py` (crypto / charsets / localization). Both run **the packaged artifact itself**,
not `java -jar` — only the former exercises the bundled runtime.

The value of these two scripts has been verified by **fault injection**: deliberately trimming `java.sql` makes them go red and print the root cause;
deliberately trimming `jdk.crypto.ec` + `jdk.localedata` pins the failure to exactly those two.
**An acceptance script that has never gone red does not count as an acceptance script.**

### 1.8 `packaging\package.ps1` must be saved as UTF-8 **with BOM**

**Current state**: the file has a BOM, and the top of the file carries a prominent warning.

**Why**: when Windows PowerShell 5.1 (the one that ships with the OS) reads a `.ps1` file,
**without a BOM it decodes using the system ANSI code page** (GBK on Simplified Chinese Windows).
The file is full of Chinese comments, which decode into garbage under GBK, and some of those characters break quote pairing,
so the script **does not even parse**, reporting "unexpected token in expression or statement".

Measured the hard way: `git show HEAD:packaging/package.ps1` starts with `255,254` (UTF-16 LE BOM),
while the working-tree copy had been written as UTF-8 without BOM by some edit — the script was completely unrunnable on Windows.

**It will keep happening**, because most editors default to writing "UTF-8 without BOM". So a guard was added:

```
python packaging/check-ps1-encoding.py
```

It scans every `.ps1` under `packaging/` and reports an error, with the fix, for any file that is "UTF-8 without BOM and contains non-ASCII".
**Run it after touching a `.ps1`** — including after editing with automated tools, since those can eat the BOM too.

---

## 2. Known Defects (Should Work But Currently Does Not)

### 2.1 Error messages for unsupported syntax are misleading

**Symptom**: when you write unsupported syntax, the error message may say "**Data source not found**", when the data source is in fact fine.

```
select * from t join t2 on t.姓名 = t2.姓名
```

The parsed table name is `t join t2 on t.姓名 = t2.姓名` — **the whole thing was taken as the table name**. No such data source is found, so it reports "Data source not found".

**Root cause**: unknown clauses after `from` are swallowed whole, with no validity check on the clause boundary — the parser **does not implement** `join` / `union` / subqueries / `group by` at all, so there is genuinely nowhere to stop.

> **Do not confuse this with "the split happened at the wrong place".** There used to be a
> second cause: the old parser split on the first `" from "` **by character position**, so
> a column or alias merely *containing* `from` produced a garbage table name. That cause
> disappeared in 1-2 with the parser rewrite (`select 'from' as x from t` parses now).
> **What remains is a different cause, unrelated to that one.**

**Troubleshooting hint**: **when you hit "Data source not found" but the name looks fine, first check whether you used unsupported syntax** (`join`, `distinct`, subqueries, `union`, etc.). The way to tell is whether the "table name" in the error is a long string — a normal table name does not contain spaces or `on`.

**Which unsupported syntax gives which message**:

| You wrote | Message you get | Says what's wrong? |
|---|---|---|
| `join` / `union` / `group by` / subquery | Data source not found | ❌ misleading |
| `select distinct a` | Column not found: `distinct a` | ❌ misleading |
| `limit m, n` | dedicated message | ✅ |
| `is null` / `like` | dedicated message listing supported operators | ✅ |
| plain columns mixed with aggregates | dedicated message | ✅ |

**What has already improved**: `limit m, n`, `is null`, `like` and mixing plain columns with aggregates are now **stopped at parse time**, with dedicated error messages, instead of falling into "Data source not found". **`group by` is not among them** — it is still swallowed into the table name and reported as "Data source not found". (This line used to claim `group by` was covered too; corrected 2026-10-11 by measurement — the claim had never been true, and two test fixtures were hiding it.)

## 3. Behaviors Easily Misdiagnosed as Bugs

### 3.1 After switching language, calls without the request header fall back to the desktop language

The UI language has two paths: the `X-LynMill-Locale` request header (sent by the frontend on every request) and the desktop setting (tray menu / settings page).

**With no request header** (for example calling the API directly with curl), the **desktop setting** applies — not Chinese, and not a hardcoded English. So "I did not send a locale header, why is it returning English?" usually means the language was switched to English in the tray.

Precedence: `LYNMILL_LOCALE` environment variable > `locale.txt` in the data directory > Chinese.

### 3.2 Chinese is still visible in English mode

The following **should not** be translated; seeing Chinese is correct:

- **User data**: file names you chose yourself, sheet names, column names, data source names (echoing them back in errors is deliberate — translating them would leave users unable to find their own files)
- **Stored configuration values**: such as `自动` for the CSV encoding
- **The two characters 「中文」 in the language selector** (the one and only deliberately preserved piece of Chinese UI copy)
- Chinese that users wrote themselves in SQL / comments

How to tell: replace the user data with placeholders and see whether the **template skeleton** still contains Chinese. Chinese in the skeleton is what a missing translation looks like.

### 3.2b Seeing English in Chinese mode: not a missing translation, it is **cached from the previous pass**

**Symptom**: after switching from Chinese to English and back to Chinese, the "Data source type" dropdown, the configuration item names, the question-mark tooltips next to those names, and the group headings of registered data sources **all stay in English**. A cold start straight into Chinese is entirely normal.

**Cause**: the copy in these four places is **not a frontend string entry**; it is type metadata the backend serves according to the `X-LynMill-Locale` request header. The frontend fetches it once at startup into `types`, and switching language only changes the i18n locale, **without re-fetching**. So the UI shell follows along while these four places stay on the copy from before the switch.

**Why it is hard to spot**: it looks like "this part was not translated", when in fact it is "this part was translated, it simply was not refreshed". And **testing only one direction will miss it** — the original acceptance test only covered "no Chinese in English mode" (which did pass) and never tested "Chinese in Chinese mode". **Checking one direction only is as good as not checking.**

The fix is a `watch(locale, ...)` in `App.vue` that re-fetches `types` (see the comment there). It hangs off `locale` rather than off the language-switching button, because the language can also change when `reconcileLocale` aligns with the server at startup.

**It can now be verified automatically**: `tools/locale-e2e/run_all.py` uses a real browser (Edge/Chrome + CDP) to cover
the 5 scenarios of this rule, so **no manual clicking** is needed. Scenario s4 specifically watches this "half new, half old" shape:
after the switch `documentElement.lang` is already `zh`, but if the type dropdown still says `CSV File` the run fails.

**Why Vitest / jsdom was ultimately not introduced**: the branching logic of `reconcileLocale` looks like a good fit for unit tests,
but this very defect is the kind jsdom cannot reach (reactive refresh + backend re-fetch).
Rather than build a harness that can only test mocks, it is better to make the real-browser scenarios solid.

See `tools/locale-e2e/README.md`.

### 3.2c The API's `elapsed` used to be measured with wall-clock time, so the numbers were quantized

**Symptom**: firing the same SQL repeatedly reports `elapsed` values like `16, 0, 5, 0, 0, 16, 0, 0`, with zeros mixed in.

**Cause**: `System.currentTimeMillis()` (wall-clock time) was used for the start and end points. It answers "what time is it", not "how long has passed", and its granularity depends on the operating system. Typical query times in this project sit right at that magnitude, so measurements could only be integer multiples of the granularity, and **short queries reliably reported 0** — callers would think nothing was queried at all.

Switched to `System.nanoTime()` (monotonic, nanosecond-resolution, designed for measuring elapsed time). It is now collected into a single class, `QueryTimer`, shared by both endpoints (table query and chart aggregation), so a future endpoint does not get it wrong a third time. **Note that `currentTimeMillis` still has one correct use**: `DataSourceManager` stamps data sources with a registration timestamp — there, "what time is it" is exactly what is wanted and must not be changed.

**Measured addendum (inconsistent with the original record, noted faithfully here)**: the to-do said "granularity on Windows is about 15.6 ms", but **on this machine (Windows 11 / JDK 17) `currentTimeMillis` was measured at 1 ms granularity** (measured continuously for 400 ms; the distribution of jumps was `{1ms: 389, 2ms: 5}`), while `nanoTime` was 99 ns.

So the symptom looks different from the original record: it is not "jumping between 0 and 16" but **short queries uniformly reporting 0**. Measured on 200,000 rows: before `cancel`, `[5, 2, 2, 2, 1, 1, 1, 0, 8, 0]`, with 15~33 ms end to end. **The conclusion (the wrong API was used) stands, but the figure "15.6 ms granularity" must not be copied to another machine — it varies with operating system and hardware.**

**An easy misreading**: `elapsed` only covers the **engine execution** segment, not HTTP, JSON serialization, or result transfer. So "`elapsed` far below end-to-end" is normal, not a bug. The way to verify is to build a gradient of engine workload:

| Scenario | elapsed | End to end |
|---|---|---|
| `select * from big limit 5` | 0~1 ms | 15~23 ms |
| `select * from big limit 1000` | 1 ms | 16 ms |
| `order by amount desc` (full-table sort) | 32~42 ms | 45 ms |

Only a query that really does work (the full-table sort) pulls `elapsed` up, and it does so **at the same order of magnitude**; light queries read 0 because they **really do take under 1 ms**. Whether the timing is accurate should be judged by whether it keeps up on heavy work, not by comparing it with end-to-end.

### 3.2d Copying Element Plus default colors into components = the culprit behind theme-switch drift

**Symptom**: in the chart color panel, the selected swatch has a **blue** ring around it, while every neighboring element is purple.

**Cause**: `tokens.css` has already overridden `--el-color-primary` from Element Plus's default blue `#409eff`
to Violet, but `ChartPanel.vue` hardcodes `border-color: #409eff` —
**it copied EP's default value, not the theme value**, so it does not change along with it.

The same batch also includes `#606266` (EP's `--el-text-color-regular`), `#909399`,
`#dcdfe6` (EP's `--el-border-color`), and `#e6a23c` (EP's warning).

**This is not "not tidy enough" — it is an already-visible bug.** The test: change some token in tokens.css
to something obviously different, and whatever does not change on screen is hardcoded.

**Fix**: add a layer of semantic aliases `--ui-*` in `tokens.css`, with values pointing at the EP variables themselves:

```css
--ui-selected: var(--el-color-primary);   /* not #409eff */
--ui-text: var(--el-text-color-regular);
--ui-border: var(--el-border-color);
```

Components reference only `--ui-*`. That way, EP changing its theme and this file changing its overrides both take effect together.

**An inconsistency fixed along the way**: the chart overlay originally had `background: #ffffff` + `border: #dcdfe6`,
while the comment in tokens.css explicitly says "panels are no longer `#ffffff` but a slightly purple-tinted `#fbfafd`".
Switching to `--ui-surface` / `--ui-border` brought it in line with the other panels.
**The one place deliberately kept pure white is the canvas overlay** — it must be opaque there, since translucency would let the old graphics show through.

**How to check for stragglers**:

```
python -c "<scan literal color values on color properties across all .vue/.css under src>"
```

The test must **look only at color properties** (`color` / `background` / `border-*` / `box-shadow` / `--*`, etc.),
otherwise the `white` in `white-space: nowrap` gets misread as a color — this false positive has been hit before.

### 3.3 Export range matches the UI, and aggregate results cannot be exported

Export honors the current `limit` / `offset`. Also, aggregate results (such as `count(*)`) have no detail rows and **are not exportable** — this is deliberate, not a broken button.

### 3.4 Box glyphs in the tray menu on Linux = the system lacks Chinese fonts

**Symptom**: after installing the program on Linux, the Chinese in the tray menu turns into little boxes (tofu).

**Cause**: Windows / macOS ship Chinese fonts with the system; Linux **does not guarantee** them.
LynMill's tray menu uses `SansSerif`, which fontconfig maps to some actual font;
if the system has no font containing CJK glyphs at all, the mapping result cannot draw Han characters.

**This is not a program bug**, it is a missing font. Installing one is enough:

```bash
sudo apt-get install fonts-wqy-microhei    # about 5 MB, enough
# or
sudo apt-get install fonts-noto-cjk        # about 91 MB, broader coverage
```

**An improvement already made**: `packaging/package-linux.sh` adds a
`Recommends: fonts-wqy-microhei | fonts-noto-cjk` line when building the deb, so an apt install usually pulls it in automatically.
(`Recommends` rather than `Depends`: the program runs fine without the font, and nobody should be blocked from installing because of it.
jpackage has no option for writing `Recommends` — the OpenJDK jpackage lead confirmed
"dependency resolution cannot be disabled, and dependencies can only be appended, not retyped", hence editing the control file after packaging.)

**rpm has no such line**: jpackage only produces a binary rpm, and changing it requires `rpmrebuild`,
which is not in the default Ubuntu/Debian repositories. rpm users should install it themselves with the commands above.

**Why not `--linux-package-deps`**: that option adds a `Depends` (a hard dependency),
turning a font package into an installation prerequisite — the cost outweighs the benefit.

**How to check for yourself whether this is the problem**:

```bash
fc-list :lang=zh | wc -l      # 0 means no Chinese font is installed
```

**A test guards this**: `TrayIconInstallerTest` checks whether the menu font can display every character in the menu copy,
and it **deliberately fails** when the font is missing — that is a safety net, so do not loosen it just to "make the build pass".
There is also a separate self-check, `packaging/check-font-recommends.sh`, verifying that the deb Recommends logic is not broken.

### 3.5 Which quoting forms are legal inside a delimiter

**This is not a defect** — it is a semantics note. Users write these forms and want to
know why one errors and another does not.

Quote characters of a **different** kind may be nested freely (a backtick may contain
single or double quotes). A same-type quote written **bare** is illegal and must be
doubled. Brackets use the SQL Server `]]` rule.

This cannot be settled by opinion, because "the quoting form itself is illegal" and
"the parser has a bug" both show up as an error. The table below is the result of
running each form against **real SQLite 3**:

| Form | Real SQLite | tinyquery | Verdict |
|---|---|---|---|
| `` `a"b` `` | column `a"b` | column `a"b` | ✅ match |
| `` `a'b` `` | column `a'b` | column `a'b` | ✅ match |
| `` `a``b` `` | column ``a`b`` | column ``a`b`` | ✅ match |
| ```` ```a``` ```` | column `` `a` `` | column `` `a` `` | ✅ match |
| `` [a`b] `` | column ``a`b`` | column ``a`b`` | ✅ match |
| `[a``b]` | error (`no such column: a``b`) | error | ✅ match |
| ```` ``a`` ```` | syntax error | error | ✅ match |
| `` `a`b` `` | unrecognized token | error | ✅ match |

> **The one people trip over**: `` [a`b] `` works but `[a``b]` does **not**.
> Brackets do **not** treat backticks as special, so `[a``b]` asks for a column
> literally named `` a``b `` (two backticks), and the table only has `` a`b `` (one).
> SQLite behaves identically — correct behaviour, not a defect. Inside brackets only
> `]` needs doubling.

### 3.6 "No error, but the wrong thing came back" — the most dangerous class of bug

**Remember this pattern when troubleshooting.**

There was once a defect where the query succeeded, the row count was right, and yet
**a different column came back** (a column named `'''` read as `'`). Zero errors, the UI
looked perfectly normal. The cause was the execution layer treating a delimited
identifier as a string constant and stripping its quotes.

**Why it is the most dangerous class**: there is no error to chase, the user may act on
wrong data, and **a test that only compares "success/failure" cannot catch it at all** —
that defect passed the whole differential run, because the criterion at the time only
looked at "expected ok / actual ok".

**Two rules that came out of it**:

1. **The criterion must check the returned column names**, not just success/failure.
   Expected column names in the differential run now come from **real SQLite**.
2. **Do not hand-roll "strip the delimiter + decode the escape" rules.** A hand-written
   rule inevitably drifts from the lexical layer — in measurement it was wrong in both
   directions: over-decoding produced 16 false positives, under-decoding 8. Switching
   to SQLite as the oracle made both ends correct.

**A test guards this**: `DocumentedLimitationsTest.delimitedIdentifierKeepsItsOwnQuotes`.

---

## 4. The Assertions in This File Are Guarded by Tests

Every "we think it behaves like this" above has a corresponding test:

| Entry | Test class |
|---|---|
| Parser-related limitations and defects (1.1–1.4, 2.1, 3.x) | `src/test/java/com/tinyquery/parser/DocumentedLimitationsTest.java` |
| Startup configuration and user data directory (1.5) | `src/test/java/com/tinyquery/config/StartupConfigurationTest.java` |
| Memory footprint and pre-check (1.6) | `src/test/java/com/tinyquery/datasource/loader/MemoryGuardTest.java` |
| The trimmed runtime still connects to databases, reads Chinese, and renders charts (1.7) | `packaging/verify-package.py` (standalone acceptance, **needs real databases**) |
| Crypto/charset/localization modules intact after trimming (1.7) | `packaging/verify-crypto.py` (standalone acceptance) |
| The `.ps1` BOM has not been eaten (1.8) | `packaging/check-ps1-encoding.py` (standalone self-check) |
| The tray menu font can display Chinese (3.4) | `src/test/java/com/tinyquery/desktop/TrayIconInstallerTest.java` |
| The deb adds a Chinese-font Recommends (3.4) | `packaging/check-font-recommends.sh` (standalone self-check, **runs on Linux only**) |
| The README's two tables match real behavior (all) | `src/test/java/com/tinyquery/audit/ReadmeAuditTest.java` (runs in `mvn test`) + `ReadmeAudit.java` (the assertions, and a `main` for manual runs) |

The **measured numbers** in 1.6 are produced by `src/test/java/com/tinyquery/bench/MemoryFootprintTool.java`,
and can be re-run at any time to re-check (`mvn test-compile`, then run the main method directly, or run it from the IDE).

3.4's second self-check is **not in Maven**: it needs `dpkg-deb`, which does not exist on Windows,
and putting it in Maven would either make Windows builds go red or force it to be skipped (and skipping equals not having it).
It is invoked proactively by `packaging/package-linux.sh` before building the deb/rpm, and packaging aborts if the self-check fails —
so "it broke" does not wait until a user has installed the package to surface. Run it manually with: `bash packaging/check-font-recommends.sh`.

The three self-checks for 1.7 / 1.8 are likewise **not in Maven**, each for a different reason:

- `verify-package.py` needs **real databases** (MySQL/PostgreSQL started via docker, ports configurable).
  Putting it in Maven would turn machines without databases running red, and people would habitually skip it — and skipping equals not having it
- `verify-crypto.py` depends on the directory structure of the `jpackage` output, and at the `mvn test` stage there is no output at all
- `check-ps1-encoding.py` only concerns Windows `.ps1` files, and is a **sub-second** cheap check,
  well suited to being run manually after changing packaging scripts

All three **report on every run** (printing how many checks passed and which one failed) — they are not "no error means pass".

**The README's two tables** ("SQL Support Scope and Limitations" and "Known Issues") are verified item by item by `ReadmeAudit`.
It **goes through the real HTTP entry point**, printing "claimed behavior" and "actual behavior" side by side. See the class comment for how to run it.

> **It used to be only a `main` program, and therefore never ran automatically** (fixed 2026-10-11):
> the assertions had long been written, but the class name did not match surefire's default pattern,
> so it only ran when someone remembered to run it by hand — a gatekeeper that does not fire on its own is no gatekeeper.
> It is now driven by `ReadmeAuditTest` and runs in `mvn test`; `main` is kept for reading the detailed output while troubleshooting.
> The assertions are written in exactly one place, `ReadmeAudit` — duplicating the list would just create a new drift surface.

> **Wiring it up exposed a false green inside the tool itself** (2026-10-11): the `group by` entry's fixture was
> `select city, count(*) ... group by city`, which trips the "plain column mixed with aggregate" rule first and picks up
> *that* rule's dedicated message — so it showed ✅, hiding the truth that **`group by` has no dedicated message at all**.
> `DocumentedLimitationsTest` had two false greens of the same kind (limitation 1.1 and defect 2.1).
> All three are corrected, and the "has a dedicated message" claim in the README and in NOTES 2.1 was corrected with them.
> **A fixture must test only the one thing it claims to test** — incidentally exercising another rule hides the truth.

> **The measuring instrument needs its own test**: the `garbageTableName` helper used to decide
> "does the error mention a garbage table name" originally treated `group by` as a clause boundary and derived the table name
> `sales` — while the real parser returns `sales group by city`. **The instrument was wrong for exactly the reason it exists to detect**
> (the parser does not recognize `group by`). So `ReadmeAuditTest` has a self-check that compares against the real `SQLParser`
> entry by entry, deriving the expected values on the spot rather than maintaining them from memory.

> **Why this tool exists at all**: during the 2026-10 review, the README's [Supported] table was found to list
> `select a as 别名` as "supported" when the implementation had never had it (that defect was fixed in 1-2, and the entry was deleted per convention).
> That error had been there for a long time, since the README was rewritten in `8cc9c9f`, and **no test ever went red because of it**.
> Section 4 of this file covers "limitations", whereas the README covers "capabilities" —
> and the latter had never been guarded by anything.

**This is not formalism** — this repository really has drifted: the README long listed `in` / `between` / `is null`
as "supported" (they were never implemented), while listing the already-implemented "outer parentheses around conditions" as "not supported".
**The errors went in both directions, and lasted more than one version, and no test ever went red because of it.**

So after a behavior change those tests go red, and whoever made the change must decide at the same time: change the conclusion (the code), or change the documentation.
Only when both are done is the work complete.

**Maintenance conventions**:

| Situation | What to do |
|---|---|
| A limitation has been implemented | Change the test from "assert it throws / assert the garbage table name" to "assert the correct behavior", **and delete the entry from this file** |
| A new limitation was added | Add the assertion to the test first, then write the entry |
| Documentation and implementation disagree | Run `DocumentedLimitationsTest` first to establish which one is right, then fix the wrong side |

---

## 5. How to Self-Check After Changing Code

1. **Run the full test suite**: `mvn test` (the frontend has to be built first, see README)
2. **Changed SQL parsing or execution**: run `DocumentedLimitationsTest`, and sync the README's
   "SQL Support Scope and Limitations" table and this file
3. **Changed the parser**: confirm that the descriptions of the two parser-related entries in
   this file are still accurate — limitation **1.1** (`group by` unsupported) and defect **2.1** (misleading errors)
4. **Changed copy or i18n**: follow the "how to verify after changes" section of `TinyQuery-Data/06-开发文档/i18n-术语表与风格规范.md`
5. **Run the javadoc reference check once after finishing**:

   ```
   javadoc -Xdoclint:reference -quiet -d <temp dir> -classpath target/classes \
           -sourcepath src/main/java com.tinyquery.datasource.loader
   ```

   **Why it gets its own item**: `{@link}` is part of javadoc, **it does not participate in compilation, and `mvn test` cannot see it at all**.
   Links pointing at private members, or at members that do not exist, will keep on "looking fine"; only the IDE and javadoc report them.
   One such case was missed this way in practice (`{@link XlsxReader#numberOrDate}`, where the target is a private method).

6. **Clean up leftovers after refactoring**: after deleting a field or method, make sure there is no dead code that is "still declared, still passed around, but read by nobody".
   One case was missed in practice: after the criterion changed from "is the header a placeholder" to "does the page have any text at all",
   `headersArePlaceholders`'s declaration, constructor parameter, and three call sites were all left in place, **with the comment still describing the abandoned design** —
   the tests were all green, but the next person to read the code would be misled by the comment. **All-green tests do not mean clean code.**

7. **"I changed it and nothing happened" → check the version number first, do not suspect the code first**.

   After the program starts, the bottom of the settings page shows a build identity line such as `1.2.0 · e0dce58 · 2026-10-10 15:02`;
   there is also a "build version" line in the startup log. Compare it against **the time of your most recent packaging run**:

   - **They match** → what you are looking at is the latest code; the problem is elsewhere
   - **It is an old time** → you are not running the package you just built (an old process did not exit, browser cache,
     or you are running `mvn spring-boot:run` instead of the new jar)

   This deserves its own item because **before that line existed, this problem could only be guessed at**: an old bundle
   loaded by the browser looks exactly like a new one, and nothing in the UI can tell them apart. A lot of time was wasted on exactly this in practice.

   If the packaging time does not show (only the version number), `build-info.properties` was not generated —
   check whether `generate-build-info` in the pom ran.

8. **Changed language-related code? Run the real-browser regression**:

   ```
   mvn package -DskipTests
   python tools/locale-e2e/run_all.py
   ```

   Covers 5 scenarios (precedence of the local and server-side languages, following across restarts, re-fetching backend copy after a language switch).
   **Why unit tests cannot substitute**: it depends on the real state of `localStorage`, the real startup sequence,
   and Vue's reactive refresh — mocking jsdom away means testing the mock.
   This project has been burned by "a fixture that cannot distinguish implementations → false green", so a real browser is used here.
   See `tools/locale-e2e/README.md`.

9. **Changed packaging scripts, or changed the runtime module list? Artifact acceptance must be run**:

   ```
   powershell -File packaging\package.ps1       # or ./packaging/package.sh
   python packaging\verify-package.py           # full end-to-end
   python packaging\verify-crypto.py            # crypto / encoding / localization
   ```

   `verify-package.py` runs **the packaged launcher** (not `java -jar`),
   so only it can verify the bundled runtime. See 1.7 and 1.8 of this file.

---

## 6. Maintenance Conventions for This File

- **Location**: `NOTES.md` in the repository root. Developer-facing docs live in the repository and are versioned with the code
- **New entries**: decide first whether it is a "defect" or a "limitation", and put it in the right section — putting it in the wrong one makes people change things with the wrong expectation
- **After fixing it**: **delete the entry**; do not leave a "fixed" tombstone behind.
  History is in git. What got fixed belongs in the CHANGELOG and README; this file only
  describes what will trip you up **today**.
  - Exception: if an entry contains something **independent of whether it is fixed**
    (a semantics note, a troubleshooting lesson, a measured comparison table), move that
    part to section 3 rather than deleting it along with the entry
  - After deleting, **check other entries' cross-references**: a deleted entry's number
    gets reused, so references to the old number must be updated too
- **When a limitation becomes a defect**: if one day you decide it should work, move it from section 1 to section 2 and write down why you changed your mind
- **Write "1-2", not "this round"**: round numbers are a temporary development label;
  a few months later nobody knows which round "this round" was
