# UI preference consistency regression (real browser)

The rules aligning the web app's locale **and theme color** with the server's, and behaviors such
as "switching locale must refetch server-provided strings",
**depend on three things ordinary unit tests cannot provide**: the real state of `localStorage`,
the real startup sequence (`reconcileLocale` / `reconcileTheme` run before mount), and real HTTP
round trips with Vue reactive updates.

Mock those away with jsdom and you are testing the mock, not the product — this project has
already been burned once by "a fixture that cannot distinguish implementations → false green"
(issue 25). So this harness uses a **real browser + real CDP**.

## How to run

```
mvn package -DskipTests          # package first; the script runs the real artifacts inside the jar
python tools/locale-e2e/run_all.py
```

Exit codes: `0` all passed / `1` one or more scenarios failed / `2` environment not satisfied.

A full run takes about **2–3 minutes** (each scenario starts a server and a headless browser).

## When to run

Before a release, and after changing any of the following:

| What changed | Why |
|---|---|
| `tinyquery-frontend/src/locales/**` | The alignment rules themselves |
| `tinyquery-frontend/src/theme/**` | The theme alignment rules themselves |
| `tinyquery-frontend/src/assets/tokens.css` theme blocks | The colors these scenarios read back from `getComputedStyle` |
| Locale- and `types`-related logic in `tinyquery-frontend/src/App.vue` | This is where defect 26 lives |
| The startup order in `tinyquery-frontend/src/main.js` | `reconcileLocale` / `reconcileTheme` must run before mount |
| `PreferencesController` / `DesktopSyncService` / `DesktopLocale` / `DesktopTheme` | The server-side preference |
| Entries served by `Messages` based on `X-TinyQuery-Locale` | Type names and field descriptions |

## The seven scenarios

| Scenario | Assertion | What it guards against |
|---|---|---|
| **s1** | No local choice → follow the server, and **do not** write to `localStorage` | Reconcile writing the server locale in as a "local choice", after which server locale changes can never catch up |
| **s2** | A local choice exists → the local choice wins, and is pushed to the server | Reconcile instead overwriting the user's explicit choice with the server locale |
| **s3** | Across two startups: after the server locale changes, the web app catches up on its next open | The **closed-loop check** for s1. s1 only proves "nothing was persisted the first time"; s3 proves "and therefore it catches up the second time" |
| **s4** | After a locale switch, server-provided type metadata is refetched too | The real defect behind issue 26: the shell changes but the type dropdown and field descriptions stay in the previous locale |
| **s5** | No Chinese appears in the UI in English mode | Missing English entries when new strings are added |
| **s6** | Theme follows the same rules as locale (no local choice → server wins and nothing is persisted; local choice → local wins and is pushed), and the computed `--accent` matches | The theme path losing the server's choice at startup, or failing to push a local choice to the desktop shell |
| **s7** | Clicking through every theme: the computed color equals the color the server publishes on `/api/preferences/themes`, and exactly one option is selected | A picker that advertises one color and renders another |

### s4 is the main reason this harness exists

Defect 26 **only surfaces in one direction**: acceptance testing only covered "no Chinese in
English mode" (which passed) and never checked "Chinese in Chinese mode", so it slipped through.
It also shows up in a "half new, half old" state — `documentElement.lang` is already `zh`, but
the type dropdown still reads `CSV File`. **Checking only the shell locale cannot detect this.**

### s6 caught a real defect on its first run

The theme module originally had no state for "which theme is currently applied". `reconcileTheme`
deliberately does **not** write `localStorage` when it adopts the server's theme, so the later
`syncTheme()` call re-derived the theme from `loadTheme()` — which read the intentionally empty
`localStorage`, fell back to the default theme, and **erased the theme just adopted from the
server**. Symptom: on a fresh browser, a server-set theme other than the default never appeared
until the user clicked it manually.

The locale path never had this bug because `i18n.global.locale.value` *is* state — `syncHtmlLang`
reads it rather than re-deriving. The fix gives the theme module the same thing (`currentTheme`),
so "which theme is applied right now" has exactly one source.

## Verified: injected defects turn red

A test suite that cannot turn red is no test suite. Every injection below was verified for real:

| Injected defect | Result |
|---|---|
| `App.vue`'s `watch(locale)` no longer refetches `types` (the original defect 26) | **Only s4 turns red**, reporting "type dropdown inconsistent with Chinese entries: missing=['CSV 文件'…] extra=['CSV File'…]" |
| `reconcileLocale` changed to `{ persist: true }` | **s1 and s3 turn red** (s3 reports "still Chinese on run 2 — meaning run 1 persisted the locale as a local choice") |
| `syncTheme()` reverted to deriving from `loadTheme()` (the defect s6 found) | **s6 turns red** on the "no local choice" case |
| `applyTheme()` no longer sets the `data-theme` attribute | **s6 and s7 turn red** |
| `reconcileTheme()` persists when adopting the server theme | **s6 turns red** on the "nothing was written" assertion |
| `setTheme()` no longer pushes to the server | **s6 turns red** on the server-side value |
| `setTheme()` no longer persists the local choice | **s6 turns red** on `localStorage` |
| A theme's `--accent` changed in `tokens.css` | **s7 turns red** (computed color ≠ published color) |
| A theme block renamed so it no longer matches `data-theme` | **s7 turns red** |
| The server's `/themes` publishes the wrong `accent` | **s7 turns red** on the swatch dots |
| The theme options rendered in a different order | **s7 turns red** (clicking index *i* selects the wrong theme) |
| A theme's fill override token name misspelled so the override never applies | **s7 turns red** on "the fill is not lighter than the accent" |
| The fill override written globally (`:root` instead of `:root[data-theme=…]`) | **s7 turns red** (the other themes' buttons are repainted too) |
| The rules pinning text colour back to `--accent` deleted | **s7 turns red** (static check; the live probe covers the cascade) |

Each injection turned **only the scenarios that should turn red** red, leaving the rest green —
the scenarios do not interfere with one another.

### The two assertions that were not good enough at first

Worth recording, because both looked fine and both were blind:

1. **The expected fill colour was read from the very file under test.** Parsing `tokens.css`
   kept the expectation in sync automatically — and made the check blind: when the override
   token was misspelled it disappeared from the file, so the expectation silently fell back to
   `--accent`, and "the button is `--accent`" passed. The fix is to pin the *intent* instead
   (`FILL_SHOULD_BE_LIGHTER`: which theme, which direction) and assert the fill is strictly
   lighter than `--accent`. Colour values can change freely; a broken override cannot pass.

2. **A browser check that could never run.** The two places Element uses the primary token as
   *text* colour only render on the query/chart pages, and the driver sits on the settings page,
   so `querySelector` always returned `null` and the check was skipped every single time.
   It now **builds a probe element** with the same class names, appends it to `body`, and reads
   the computed colour — which also proves our rule wins the specificity contest against
   Element's own rule. That was the only real risk in that CSS, and reading the stylesheet
   text cannot prove it.

## Environment variables (all have defaults; only needed on another machine or in CI)

| Variable | Default | Description |
|---|---|---|
| `TINYQUERY_JAVA` | `D:\JDK\jdk-17\bin\java.exe` | The JDK 17 java |
| `TINYQUERY_NODE` | `D:\Node.js\node.exe` | Node 18+ (uses its built-in `WebSocket`; no `npm install` needed) |
| `TINYQUERY_BROWSER` | Auto-detects Edge/Chrome | Browser executable |
| `TINYQUERY_ROOT` | Inferred from the script location | Project root |
| `TINYQUERY_E2E_WORK` | System temp directory | Working directory (profile, logs, fake HOME) |

**No Python packages are required**; only the standard library.

## Four pitfalls we hit (all documented in code comments)

### 1. Do not use fixed ports

Scenarios originally hard-coded ports 18060–18065. It turned out 18063 was held by a **browser
tab left over from an earlier session**, and that page replayed
`POST /api/preferences/locale {"locale":"en"}` every few seconds, changing the locale of the
newly started server. The symptom looked like "the server changed zh to en by itself", and took
a long time to track down.

Now each run allocates ports dynamically via `free_ports()`, so it never collides with a stray
page. The script also ships `assert_locale_settled()` / `assert_theme_settled()`: scenarios that
depend on the server preference confirm it actually settled at the expected value first, and fail
loudly instead of letting later assertions produce misleading results.

### 2. Teardown must kill browser child processes too

Edge / Chrome are multi-process; `terminate`-ing only the main process **leaves child processes
alive**, and the pages inside them keep running — and keep sending requests to the backend.
`Browser.stop()` now kills the whole process tree by profile path (only this tool's profile, never
the user's own browser).

### 3. A released port can be handed straight back out

Pitfall 2 is not fully solved by teardown alone. A scenario that starts several server+browser
pairs in a row (s2 starts two, s6 starts four) releases a port before the next pair asks for one,
and **the OS happily reuses it**. If a renderer from the previous pair is still alive for a few
hundred milliseconds, its page pushes a theme to *the next pair's* freshly started server — and
the symptom is "the server theme is inexplicably `bamboo`", which looks exactly like a product
defect.

This was hit for real while adding s6 (same code, sometimes red, sometimes green). `free_ports()`
now remembers every port it has handed out in this process and never repeats one, which cuts the
"stray page → new server" path entirely. `_run()` also kills strays before starting each pair.

### 4. An interrupted injection run leaves the source tree modified

The injection scripts edit the source under test in place and restore it in a `finally`. A run
killed mid-flight (timeout, `job_kill`, Ctrl-C) does not always reach that block, so the tree is
left holding an injected defect — and every later result is measured against broken code.

This was hit for real: after killing a run, `theme/index.js` sat at `persist: true`, and the next
run's anchor self-check reported "anchor not found" for an injection that was perfectly fine. It
read as a coverage gap when the actual cause was a polluted baseline.

Both scripts now write an `inflight.json` recording the file and its backup, and
`recover_if_interrupted()` restores it on the next start and says so. If you ever see that warning,
re-run the anchor self-check before trusting anything.

## What this does not do

- **No CI integration**: the entire value lies in a real browser and real startup timing; wiring it
  into CI would mean provisioning a headless browser and a JDK, and the payoff does not justify the cost.
- **No Vitest / jsdom**: the branch logic in `reconcileLocale` looks unit-testable, but the defect
  actually proven here (issue 26) was precisely a "reactive update" that jsdom cannot reach. Rather
  than build infrastructure that can only test mocks, make these real-browser scenarios solid.
  (The theme module's `currentTheme` defect is the same story: it only appears when
  `reconcileTheme` and `syncTheme` run in their real startup order.)
- **No full panel sweep**: s5 only reads the first screen. A full sweep would have to handle each
  panel's async loading, and the criterion would drift from "is the translation complete" to "did the
  sweep cover everything", easily becoming a flaky assertion.
- **No pixel comparison**: s7 asserts computed CSS values and element geometry, not screenshots.
  Screenshot diffing would need a reference image per theme and would go red on any font or
  antialiasing change.

## Known legitimate exceptions

Places where Chinese is allowed in English mode:

- The 「中文」 button in the locale selector — a language's name should always be written in that
  language, whatever the current locale
- **User data**: file paths, table names, column names, data source names, and literals users write
  themselves in SQL
- **Proper nouns**: `MySQL / MariaDB`, `PostgreSQL`, `SQLite`, `CSV`, `Excel` are product names
  written this way in both locales — they **should contain Latin letters by design**

We tripped over the last one: s4's first criterion was "3 or more letters counts as English", which
flagged `MySQL / MariaDB` as "not refreshed". **The criterion was wrong because it treated proper
nouns as untranslated text.** It now compares against the **expected values** of the Chinese entries,
item by item, instead of guessing.
