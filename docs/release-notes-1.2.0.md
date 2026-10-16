# LynMill 1.2.0

**Two themes: export beyond CSV, and a rebuilt SQL parser.**

> ⚠️ This release **does not include installer packages**. Version numbers are bumped
> and the source is tagged; build the package you need from source (see
> [Build from source](#build-from-source) below). No `.zip` / `.dmg` / `.tar.gz` /
> `.deb` / `.rpm` is attached.

---

## Export: more than CSV

1.1.0 could export CSV. 1.2.0 adds three more formats — all generated in-process, so the
download does not grow.

### Excel (`.xlsx`)

Written directly rather than through Apache POI, keeping the app a single small jar.

- **Numbers are written as numbers**, not text, so Excel can sum and sort them
  immediately. (1.1.0's CSV export had this problem in a milder form: everything landed
  in Excel as text.)
- Header row is **bold and frozen**, so scrolling a long result does not lose the
  column names.

### PDF

- **Paginated A4**, with the header row **repeated on every page**.
- **A CJK font is embedded in the application**, so an exported report renders correctly
  on a machine that has no Chinese fonts installed. This is the "send the report to
  someone else" case — without it the recipient sees boxes.
- A **layout preview** shows the pagination before you download.

### Charts → PDF

- **Line, bar and pie charts** export to PDF with the same colours, the same **Y-axis
  range**, and the same optional data table as the on-screen chart. Getting the axis
  range to match the UI took several rounds — the PDF used to pick its own range and
  the two never agreed.
- The data table below the chart is **optional and off by default**.

---

## SQL parser: rebuilt

The parser work was not on the original plan. It turned out to be a **prerequisite**:
the old parser split SQL with `indexOf` and regular expressions, so perfectly ordinary
statements were parsed incorrectly. Building export on top of that would have been
building on sand.

### Now supported

| Syntax | Example |
|---|---|
| **Column aliases** | `select 城市 as c from t` — the alias becomes the result header |
| | `select sum(x) 总额 from t` — expressions may omit `as` |
| **`in` / `not in`** | `where c in ('a','b')` — expanded to `or` / `and` at parse time |
| **`between` / `not between`** | `where n between 1 and 10` — expanded to `>=` / `<=` |
| **Quoted identifiers** | `` `x` ``, `"x"`, `[x]` — delimiters stripped, escapes match SQLite |
| **Keywords as column names** | a column actually named `from`, `order`, `limit`, `and` is no longer mistaken for syntax |

The escape rule for quoted identifiers is **SQLite's**, verified against a real SQLite 3:

```sql
select `a"b`    -- column a"b   (a different quote kind needs no escaping)
select `a``b`   -- column a`b   (same kind doubles)
select [a]]b]   -- column a]b   (brackets double the bracket)
select [a`b]    -- column a`b   (brackets do not treat backticks specially)
```

Bare delimiters are now **rejected** rather than silently matched: `` select a`b `` reports
"unterminated delimiter", matching SQLite.

### Structure

The parser is now **three layers**, and the old `SQLParser` is a thin orchestrator:

| Layer | Class | Rule |
|---|---|---|
| Lexical | `parser/lex/Tokenizer` | the **only** component that touches characters |
| Syntactic | `Parser` | works on tokens only, never sees characters |
| Semantic | `SemanticAnalyzer` | name resolution, type checks |

All parse-time validation moved into `ParserChecks` (18 call sites), so error messages
live in one place.

---

## Fixed

A **584-statement differential corpus**, run against the packaged Windows app over
HTTP, went from **147 mismatches to 0**. Beyond the parser rewrite, it exposed:

- **A query that succeeded but returned the wrong column.** A quote character inside a
  delimited identifier closed it early, so a column genuinely named `'''` resolved to a
  *different* column named `'`. The root cause was `unquote` in the execution layer
  stripping the leading and trailing quotes off a delimited identifier, mistaking it for
  a string constant. **This is the dangerous kind of bug — no error, wrong data.**
- **`]]` inside brackets** ended the identifier prematurely.
- **`distinct` / `join` / `union` / subqueries / `group by`** are still unsupported, and
  the error message still says "data source not found" rather than "not supported" —
  because there is genuinely no clause to stop at. **Workaround: check for these first
  if the data source name looks correct.** Documented in both READMEs.
- **Excel data sources misread numeric columns as text.**
- Three **chart-export visual defects**: Y-axis range not matching the UI, and
  inconsistent dialog row heights.
- **CORS did not expose `Content-Disposition`**, so the browser could not read the
  export file name cross-origin.

### Error messages are now fully localised

The Chinese UI no longer shows English SQL errors. `ColumnStore` was still hardcoding
`"error:Unknown column:"` — an earlier i18n pass covered the parser layer and missed the
execution layer. **Five more one-directional gaps** were found in the same sweep (they
looked fine in Chinese and only showed up in English).

A new source-scanning test now **fails the build** if a user-visible message is
hardcoded instead of going through the i18n layer — because measuring a handful of known
messages cannot catch this class, and what slips through is always the one nobody
thought of.

### Corrections to the test corpus and test data

Five defects in the corpus and test data **itself** were found and fixed. Worth noting
because they masquerade as product bugs:

- The corpus compared only "expected ok / actual ok", so a query that **succeeded but
  returned a different column** counted as a pass. Expected column names now come from
  **real SQLite** running the same statement, rather than from hand-written unescaping
  rules — the hand-rolled rules drifted from the lexer in both directions
  (16 false positives from over-decoding, 8 from under-decoding).
- The test-data generator's default CSV quoting **re-escaped quote characters already
  present in column names** (intended `a"b`, written as `"a""b"`), causing a whole batch
  of spurious failures.
- The generator emitted **two duplicate column names**, which the column store merged —
  a 35-column CSV surfaced as 33 columns, showing up as "`select *` is missing two
  columns".

---

## Verified

| Check | Result |
|---|---|
| Unit tests | **921 pass** (911 at 1.1.0), 20 skipped |
| Differential corpus | **584 / 584 match** (0 mismatches), against the packaged build over HTTP |
| Hands-on acceptance | **27 / 27 pass** on the deployed build |
| Package self-check | 17/17 checks, 29 runtime modules |

---

## Still not supported

Unchanged by this release — listed so expectations are clear:

| Syntax | Notes |
|---|---|
| `group by` | **Deliberately omitted.** Aggregation is whole-table, not grouped |
| `is null` / `is not null` | **Deliberately omitted.** Null semantics are not syntactic sugar |
| `like` | **Deliberately omitted.** Pattern matching is likewise not syntactic sugar |
| `select a alias` (bare column, no `as`) | A bare column may not omit `as`, because `select city c` is indistinguishable from "a column named `city c`" — and such names exist. Expressions may omit it: `select amount * 2 double` |
| `limit m, n` | Argument order is reversed from the standard; use `limit n offset m` |
| `select distinct` / `join` / subqueries / `union` | Not supported |
| Arithmetic without an alias | `select age * 2 from t` fails — the engine has no expression evaluator |

---

## Build from source

No packages are attached to this release. To build your own:

```bash
# 1. Frontend (must come first: the backend build enforces a "dist freshness" guard)
cd tinyquery-frontend && npm install && npm run build && cd ..

# 2. Backend + tests
mvn clean package          # produces target/tinyquery-1.2.0.jar, runs the full suite

# 3. Package the portable app
python packaging/package.ps1          # Windows
bash   packaging/package.sh           # macOS
bash   packaging/package-linux.sh     # Linux
```

**Requirements**: JDK 17, Maven 3.9+, Node.js 18+.

---

## Upgrading

Drop-in replacement. Your data sources (`datasources.json`), saved passwords (OS
credential manager) and preferences carry over unchanged — nothing in the config format
changed. Queries that worked in 1.1.0 keep working; the parser changes only make more
statements parse correctly, they do not remove any.

See [CHANGELOG.md](CHANGELOG.md) for the full history.
