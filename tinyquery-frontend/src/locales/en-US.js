/*
 * English messages.
 *
 * Translation rules (see locales/GLOSSARY.md):
 *   · concise, professional, neutral — no filler, no paraphrase, no polish
 *   · "请" is dropped: 「请先执行一次查询」→ "Run a query first"
 *   · "的" chains collapse into noun phrases: 「数值列」→ "Numeric column"
 *   · no second person: 「你取消了授权」→ "Authorization cancelled"
 *   · half-width punctuation only; a colon is followed by one space
 *   · keys mirror zh-CN.js exactly — a missing key falls back to Chinese
 */
export default {
  // ==================== Common ====================
  common: {
    confirm: 'Confirm',
    cancel: 'Cancel',
    reset: 'Reset',
    delete: 'Delete',
    refresh: 'Refresh',
    loading: 'Loading...',
    empty: 'No data',
    none: '(None)',
  },

  // ==================== App shell ====================
  app: {
    title: 'TinyQuery',
    subtitle: 'Local Query Engine · V1.1.0',
    nav: {
      query: 'Query',
      datasource: 'Data Source',
      result: 'Result',
      chart: 'Chart',
      settings: 'Settings',
    },
    exited: {
      title: 'TinyQuery has exited',
      text: 'The background service has stopped. This page no longer responds to any action.',
      text2: 'You can close this tab now.',
      close: 'Close Tab',
      shortcut: 'Ctrl + W also closes it',
      shortcutHint: 'The browser does not allow a page to close its own tab. Press {key} to close it',
    },
  },

  // ==================== Settings ====================
  settings: {
    title: 'Settings',
    subtitle: 'Adjust interface language and display preferences',
    language: {
      label: 'Interface Language',
      hint: 'Takes effect immediately and persists across restarts',
      zh: '中文',
      en: 'English',
    },
    // Theme names are plain colour names, not character names from any work
    theme: {
      label: 'Theme Color',
      hint: 'Takes effect immediately and applies to the tray and control window too',
      violet: 'Violet',
      indigo: 'Indigo',
      bamboo: 'Bamboo',
      coffee: 'Coffee',
    },
    // Build identity. Version and git hash are identifiers, untranslated in both locales
    about: {
      label: 'Version',
      hint: 'Include this line when reporting a problem so we can tell which build you are running',
    },
  },

  // ==================== Query ====================
  query: {
    title: 'Query',
    subtitle: 'Supports SELECT projection, WHERE conditions, multi-column ORDER BY sorting and LIMIT / OFFSET',
    placeholder: 'Enter SQL, e.g. select * from sample.csv',
    run: 'Run',
    shortcut: 'Ctrl + Enter to run',
    changedTitle: 'SQL Modified',
    changedText: 'The SQL was modified but not executed. Run the new SQL?',
    runNew: 'Run New SQL',
    keepOld: 'Keep Previous Result',
  },

  // ==================== Data source ====================
  datasource: {
    title: 'Data Source',
    subtitle: 'Register a CSV file or database connection, then reference it by name in the SQL FROM clause',
    registered: 'Registered Data Sources',
    empty: 'No data sources',
    refresh: 'Refresh List',
    column: {
      name: 'Name',
      action: 'Action',
      config: 'Configuration',
    },
    deleteConfirm: 'Delete data source {name}?',
    deleted: 'Deleted: {name}',
    reloaded: 'Reloaded: {name}',
    prompt: 'Notice',
  },

  // ==================== Data source form ====================
  form: {
    register: 'Register',
    testConnection: 'Test Connection',
    reset: 'Reset',
    typeLabel: 'Data Source Type',
    nameLabel: 'Data Source Name',
    namePlaceholder: 'Referenced in the SQL FROM clause, e.g. users',
    selectPlaceholder: 'Select',
    savePassword: 'Save Password',
    savePasswordHint: 'Save password and reconnect automatically on next launch',
    savePasswordNote: 'The password is stored in the system Credential Manager, not in the config file',
    testPassed: 'Test Passed',
    testFailed: 'Test Failed',
    registered: 'Data source registered: {name}',
    error: {
      typeRequired: 'Select a data source type',
      nameRequired: 'Enter a data source name',
      nameNoDot: 'Name cannot contain a period',
      nameTaken: 'This name is already taken',
      checkIncomplete: 'Validation incomplete, try again',
      fieldRequired: 'Enter {field}',
      numberRequired: 'Enter a number',
      savePasswordUnsupported: 'Saving passwords is not supported on this system',
      savePasswordUnknown: 'Cannot determine whether this system supports saving passwords',
    },
  },

  // ==================== Result ====================
  result: {
    title: 'Result',
    emptyHint: 'Row data appears here after a query runs',
    noResult: 'No result yet. Run a SQL statement in Query first',
    tabTable: 'Table',
    tabTableWithRows: 'Table ({count} rows)',
    tabJson: 'Raw JSON',
    rows: '{count} rows',
    summary: '{total} rows total, showing {from}-{to}',
    placeholderColumn: 'Column {n}',
    export: 'Export CSV',
    exporting: 'Exporting...',
    exportFailed: 'Export failed',
    exportHttpError: 'Export failed: server returned {status}',
  },

  // ==================== Chart ====================
  chart: {
    title: 'Chart',
    subtitle: 'Aggregated by dimension over the full result set, unaffected by table pagination',
    exportPng: 'Export PNG',
    emptyHint: 'Run a query first; the chart is drawn from that result',
    loading: 'Aggregating...',
    dimension: 'Dimension',
    measure: 'Measure',
    measure1: 'Measure 1',
    measure2: 'Measure 2',
    measureN: 'Measure {n}',
    unifyScale: 'Unify Measure Units',
    type: 'Type',
    typeBar: 'Bar Chart',
    typeLine: 'Line Chart',
    typePie: 'Pie Chart',
    aggregate: 'Aggregate',
    aggSum: 'Sum',
    aggAvg: 'Average',
    aggCount: 'Count',
    aggMax: 'Maximum',
    aggMin: 'Minimum',
    palette: 'Palette',
    highlight: 'Highlight',
    clearHighlight: 'Clear Highlight ({count})',
    keepFullPrecision: 'Keep Full Precision',
    noNumericColumn: 'No numeric column',
    noNumericHint: 'The current result set has no numeric column to use as a measure',
    paletteResetHint: 'Switching palette resets manual colors',
    paletteResetDetail: 'Switching palette or custom primary color resets manual sector colors',
    pieHint: 'Click a sector to adjust its color individually',
    barHint: 'Click a bar to toggle highlight, click again to clear',
    bucketHint: '{dimension} is numeric and was split into {count} buckets (X axis shows ranges)',
    truncatedHint: '{total} groups total, showing the top {shown} by {agg} descending; the rest are merged into "Other"',
    other: 'Other',
    densityHint: '{count} groups; values are hidden on bars, hover to see them',
    hintSeparator: '; ',
  },

  // ==================== Palette names ====================
  // Keys match the ids in ChartPanel.vue's PRESET_MAIN_COLORS.
  // The six cards run light to dark: Sky → Mist Blue → Clove → Deep Purple → Graphite → Charcoal
  palette: {
    sky: 'Sky',
    azure: 'Mist Blue',
    clove: 'Clove',
    royal: 'Deep Purple',
    graphite: 'Graphite',
    ink: 'Charcoal',
  },

  // ==================== API layer ====================
  api: {
    networkFailed: 'Network request failed',
    requestFailed: 'Request failed',
    operationFailed: 'Operation failed',
  },
}
