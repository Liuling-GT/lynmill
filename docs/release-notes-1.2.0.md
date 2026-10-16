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

---

## 中文摘要

**LynMill 1.2.0 有两条主线：导出不再只有 CSV，以及 SQL 解析器重建。**

> ⚠️ **本版不附安装包。** 版本号已提升、源码已打 tag，需要安装包请按上面的
> 「Build from source」自行构建。任何 `.zip` / `.dmg` / `.tar.gz` / `.deb` / `.rpm`
> 都没有上传。

### 导出：不止 CSV

1.1.0 只能导出 CSV。本版新增三种格式，都在程序内直接生成，下载体积不因此变大。

- **导出 Excel（`.xlsx`）**：不依赖 Apache POI 自行生成，保持单 jar；
  **数字按数值写**而不是文本，Excel 里能直接求和排序；表头加粗并冻结。
- **导出 PDF**：A4 自动分页，**每页重复表头**；**中文字体内嵌在程序里**，
  换一台没装中文字体的电脑打开也不缺字（这是"把报表发给别人"的场景，
  没有内嵌字体对方只会看到方框）；可先**预览布局**再下载。
- **图表导出 PDF**：折线 / 柱状 / 饼图的配色、**Y 轴范围**、图下数据表都与界面一致；
  图下数据表**默认不附**。

### SQL 解析器：重建

解析器改造不在原计划内，它是**必要的前置**：旧解析器用 `indexOf` 和正则切分 SQL，
普通语句（列名含 `from`、带引号的标识符）会被解析错，在上面做导出等于在沙地上盖楼。

**新增支持**：

| 语法 | 例子 |
|---|---|
| **列别名** | `select 城市 as c from t`；表达式可省略 `as`：`select sum(x) 总额` |
| **`in` / `not in`** | `where c in ('a','b')`，解析期改写成 `or` / `and` |
| **`between` / `not between`** | `where n between 1 and 10`，改写成 `>=` / `<=` |
| **定界标识符** | `` `x` `` `"x"` `[x]`，转义规则对齐 SQLite（实测核对） |
| **关键字作列名** | 列名真的叫 `from` / `order` / `limit` / `and` 时不再被当成语法 |

裸定界符（`` select a`b ``）现在**报错**而不是静默匹配，与 SQLite 一致。

**结构**：解析器拆成**三层**，旧 `SQLParser` 退化为薄编排 ——
词法层（`lex.Tokenizer`，唯一接触字符的组件）/ 语法层（`Parser`，只看 token）/
语义层（`SemanticAnalyzer`）。解析期校验集中到 `ParserChecks`（18 处）。

### 修复

**584 条对拍从 147 条不符降到 0。** 除解析器本身外还暴露：

- **一个"查询成功但取错列"的问题**：定界标识符内部的引号让它提前收尾，
  列名为 `'''` 的列被解析成了 `'` 列。根因是执行层的 `unquote` 把定界标识符
  当成字符串常量剥引号。**这是最危险的一类 bug —— 不报错，但数据是错的。**
- 方括号内的 `]]` 提前收尾。
- **Excel 数据源把数值列误判成文本**；图表导出三处观感问题。
- **跨域没暴露 `Content-Disposition`**，前端读不到导出文件名。
- **报错文案中英混用**：`ColumnStore` 硬编码英文，早先的 i18n 只清了解析层、
  漏了执行层；同批又查出 5 处单向漏翻。
  新增**扫描源码的测试**防再漏 —— 因为实测几条已知文案抓不住这一类，
  漏网的永远是没想到的那条。
- **语料与测试数据自身 5 处错误**（重复列名导致 35 列并成 33 列、
  `csv.writer` 把列名里真实的引号又转义一遍等）。

### 验证

| 项目 | 结果 |
|---|---|
| 单元测试 | **921 项通过**（1.1.0 时 911） |
| 584 条对拍 | **584 / 584 一致**，0 条不符 |
| 真实应用验收 | **27 / 27 通过** |
| 包自检 | 17/17，runtime 29 模块 |

### 仍然不支持

本版未改变，列出以免预期落空：`group by`、`is null` / `is not null`、`like`、
`select a alias`（裸列名省略 `as`）、`limit m, n`、
`select distinct` / `join` / 子查询 / `union`、无别名的算术表达式（`select age * 2`）。

### 升级

**直接替换即可。** 数据源（`datasources.json`）、保存的密码（系统凭据管理器）
与偏好设置都原样保留 —— 配置格式没有任何改动。1.1.0 能跑的查询继续能跑；
解析器的改动只是让更多语句能正确解析，没有移除任何东西。
