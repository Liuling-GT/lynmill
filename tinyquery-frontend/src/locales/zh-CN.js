/*
 * 中文词条。
 *
 * 这是「唯一事实来源」：新增文案先写在这里，再到 zh-CN 与 en-US 同步。
 * 结构按界面区域分组，不按组件 —— 同一个词（如「数据源」）在多个组件里
 * 出现，放同一处才能保证译法一致。
 *
 * 术语以 locales/GLOSSARY.md 为准，改词条前先看那张表。
 */
export default {
  // ==================== 通用 ====================
  common: {
    confirm: '确定',
    cancel: '取消',
    reset: '重置',
    delete: '删除',
    refresh: '刷新',
    loading: '加载中…',
    empty: '暂无数据',
    none: '(无)',
  },

  // ==================== 应用外壳 ====================
  app: {
    title: 'TinyQuery',
    subtitle: '本地查询引擎 · V1.1.0',
    nav: {
      query: '查询',
      datasource: '数据源',
      result: '结果',
      chart: '图表',
      settings: '设置',
    },
    exited: {
      title: 'TinyQuery 已退出',
      text: '后台程序已经关闭，这个页面不会再响应任何操作。',
      text2: '现在可以关闭这个标签页了。',
      close: '关闭本页',
      shortcut: '按 Ctrl + W 也可以关闭',
      shortcutHint: '浏览器不允许网页自己关闭标签页，请按 {key} 关闭',
    },
  },

  // ==================== 设置 ====================
  settings: {
    title: '设置',
    subtitle: '调整界面语言与显示偏好',
    language: {
      label: '界面语言',
      hint: '切换后立即生效，下次启动保持该设置',
      zh: '中文',
      en: 'English',
    },
    // 主题名一律用普通色名，不用作品里的角色名 —— 用户看的是「这是什么颜色」
    theme: {
      label: '主题色',
      hint: '切换后立即生效，桌面托盘与控制窗口一并跟随',
      violet: '紫罗兰',
      indigo: '靛蓝',
      bamboo: '竹青',
      coffee: '咖啡色',
    },
    // 构建标识。版本号与 git 哈希是标识符，两种语言都不翻译
    about: {
      label: '版本',
      hint: '反馈问题时请附上这一行，用来确认你运行的是哪一版',
    },
  },

  // ==================== 查询 ====================
  query: {
    title: '查询',
    subtitle: '支持 SELECT 投影、WHERE 条件、ORDER BY 多列排序与 LIMIT / OFFSET',
    placeholder: '输入 SQL，例如 select * from sample.csv',
    run: '执行',
    shortcut: 'Ctrl + Enter 执行',
    changedTitle: 'SQL 已修改',
    changedText: 'SQL 已修改但尚未执行，是否先执行新的 SQL？',
    runNew: '执行新 SQL',
    keepOld: '沿用上次结果',
  },

  // ==================== 数据源 ====================
  datasource: {
    title: '数据源',
    subtitle: '注册 CSV 文件或数据库连接，注册后可在 SQL 的 FROM 中按名称引用',
    registered: '已注册数据源',
    empty: '暂无数据源',
    refresh: '刷新列表',
    column: {
      name: '名称',
      action: '操作',
      config: '配置',
    },
    deleteConfirm: '确定删除数据源 {name}？',
    deleted: '已删除：{name}',
    reloaded: '已重新加载：{name}',
    prompt: '提示',
  },

  // ==================== 数据源表单 ====================
  form: {
    register: '注册',
    testConnection: '连接测试',
    reset: '重置',
    typeLabel: '数据源类型',
    nameLabel: '数据源名称',
    namePlaceholder: '供 SQL 中 FROM 引用，如 users',
    selectPlaceholder: '请选择',
    savePassword: '保存密码',
    savePasswordHint: '保存密码，下次启动自动连接',
    savePasswordNote: '密码存入系统凭据管理器，不会写进配置文件',
    testPassed: '测试通过',
    testFailed: '测试未通过',
    registered: '数据源注册成功：{name}',
    error: {
      typeRequired: '请选择数据源类型',
      nameRequired: '请填写数据源名称',
      nameNoDot: '名称不能包含英文句点',
      nameTaken: '该名称已被占用',
      checkIncomplete: '校验未完成，请重试',
      fieldRequired: '请填写{field}',
      numberRequired: '请输入数字',
      savePasswordUnsupported: '当前系统不支持保存密码',
      savePasswordUnknown: '无法确认当前系统是否支持保存密码',
    },
  },

  // ==================== 结果 ====================
  result: {
    title: '结果',
    emptyHint: '执行查询后，明细数据会显示在这里',
    noResult: '还没有查询结果，先在「查询」里执行一条 SQL',
    tabTable: '表格',
    tabTableWithRows: '表格（{count} 行）',
    tabJson: '原始 JSON',
    rows: '{count} 行',
    summary: '共 {total} 行，当前显示第 {from}-{to} 行',
    placeholderColumn: '第 {n} 列',
    export: '导出 CSV',
    exporting: '导出中…',
    exportFailed: '导出失败',
    exportHttpError: '导出失败：服务端返回 {status}',
  },

  // ==================== 图表 ====================
  chart: {
    title: '图表',
    subtitle: '按维度聚合，基于全量数据，不受表格分页影响',
    exportPng: '导出 PNG',
    emptyHint: '请先执行一次查询，图表将基于该次查询的结果绘制',
    loading: '正在聚合…',
    dimension: '维度',
    measure: '度量',
    measure1: '度量1',
    measure2: '度量2',
    measureN: '度量{n}',
    unifyScale: '统一度量单位',
    type: '类型',
    typeBar: '柱状图',
    typeLine: '折线图',
    typePie: '饼图',
    aggregate: '聚合',
    aggSum: '求和',
    aggAvg: '平均值',
    aggCount: '计数',
    aggMax: '最大值',
    aggMin: '最小值',
    palette: '配色',
    highlight: '突出显示',
    clearHighlight: '清除点亮（{count}）',
    keepFullPrecision: '保留全部小数',
    noNumericColumn: '无数值列',
    noNumericHint: '当前结果集里没有数值列，无法作为度量',
    paletteResetHint: '切换配色会重置手调颜色',
    paletteResetDetail: '切换配色或自定义主色会重置扇区的手调颜色',
    pieHint: '点击任意扇区可单独调整它的颜色',
    barHint: '点击柱子可切换点亮，再点一次取消',
    bucketHint: '{dimension} 是数值列，已自动分为 {count} 段（X 轴为区间）',
    truncatedHint: '共 {total} 个分组，按{agg}降序仅展示前 {shown} 个，其余已合并为「其他」',
    other: '其他',
    densityHint: '共 {count} 个分组，柱子上不显示数值，鼠标悬停可查看',
    hintSeparator: '；',
  },

  // ==================== 调色板名称 ====================
  // 键名与 ChartPanel.vue 里 PRESET_MAIN_COLORS 的 id 一一对应。
  // 六个色卡按明度由浅到深排：天青 → 雾蓝 → 丁香 → 深紫 → 石墨 → 炭黑
  palette: {
    sky: '天青',
    azure: '雾蓝',
    clove: '丁香',
    royal: '深紫',
    graphite: '石墨',
    ink: '炭黑',
  },

  // ==================== API 层通用错误 ====================
  api: {
    networkFailed: '网络请求失败',
    requestFailed: '请求失败',
    operationFailed: '操作失败',
  },
}
