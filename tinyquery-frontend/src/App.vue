<template>
  <!-- ConfigProvider 驱动 Element Plus 自带文案的语言。
       必须走这个组件才能响应式切换 —— app.use(ElementPlus, { locale })
       是静态注入，切语言时不会跟着变 -->
  <el-config-provider :locale="elementLocale">
    <div class="shell">
      <!-- ==================== 左侧功能栏 ==================== -->
      <aside class="sidebar">
        <div class="brand">
          <span class="brand-dot" />
          <span class="brand-name">TinyQuery</span>
        </div>

        <nav class="nav">
          <button
            v-for="item in navItems"
            :key="item.key"
            type="button"
            class="nav-item"
            :class="{ 'is-active': activeNav === item.key }"
            @click="activeNav = item.key"
          >
            <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
            <span>{{ t(item.labelKey) }}</span>
          </button>
        </nav>

        <div class="sidebar-foot">{{ t('app.subtitle') }}</div>
      </aside>

      <!-- ==================== 右侧内容区 ==================== -->
      <main class="main">
        <!-- ---------- 设置 ---------- -->
        <SettingsPanel v-show="activeNav === 'settings'" />

        <!-- ---------- 数据源 ---------- -->
        <section v-show="activeNav === 'datasource'" class="panel">
          <header class="panel-head">
            <div>
              <h2 class="panel-title">{{ t('datasource.title') }}</h2>
              <p class="panel-sub">{{ t('datasource.subtitle') }}</p>
            </div>
          </header>

          <DataSourceForm :types="types" @registered="handleRegistered" />
          <DataSourcePanel ref="panelRef" :types="types" />
        </section>

        <!-- ---------- 查询 ---------- -->
        <section v-show="activeNav === 'query'" class="panel">
          <header class="panel-head">
            <div>
              <h2 class="panel-title">{{ t('query.title') }}</h2>
              <p class="panel-sub">{{ t('query.subtitle') }}</p>
            </div>
          </header>

          <textarea
            v-model="sql"
            class="sql-input"
            spellcheck="false"
            :placeholder="t('query.placeholder')"
            @input="editedSql = true"
            @keydown.ctrl.enter="executeQuery(true)"
            @keydown.meta.enter="executeQuery(true)"
          />

        <!-- 导出按钮不在这里 —— 它导出的是结果，放在「结果」页更顺 -->
        <div class="action-row">
          <el-button type="primary" :loading="querying" @click="executeQuery(true)">
            {{ t('query.run') }}
          </el-button>
          <span class="action-hint">{{ t('query.shortcut') }}</span>
        </div>

        <!-- 执行失败发生在本页，提示留在这里 -->
        <el-alert
          v-if="queryError"
          type="error"
          show-icon
          :closable="false"
          class="panel-alert"
          :title="queryError"
        />

        <!--
          补充提示：与报错一上一下并列，报错在上。
          典型场景是列名/数据源名只差大小写 —— 报错说「找不到」，
          这行紫字告诉用户「你可能想写 Salary」。借 success 配色，
          颜色在 tokens.css 的 .hint-alert 里统一给
        -->
        <el-alert
          v-if="errorHint"
          type="success"
          show-icon
          :closable="false"
          class="panel-alert hint-alert"
          :title="errorHint"
        />
      </section>

      <!-- ---------- 结果 ---------- -->
      <section v-show="activeNav === 'result'" class="panel">
        <header class="panel-head">
          <div>
            <h2 class="panel-title">{{ t('result.title') }}</h2>
            <p class="panel-sub">{{ resultSummary }}</p>
          </div>

          <!--
            导出放这里而不是「查询」页：它导出的是结果集，与执行入口分开放才对得上。
            导的是 executedSql（上次执行成功的那条），不是输入框里的实时内容 ——
            否则改了 SQL 没执行时，看到的和导出的会是两份数据
          -->
          <el-button
            size="small"
            :disabled="!executedSql"
            :loading="exporting"
            @click="exportQuery"
          >
            {{ exporting ? t('result.exporting') : t('result.export') }}
          </el-button>
        </header>

        <el-empty
          v-if="!hasResult"
          :description="t('result.noResult')"
          :image-size="90"
        />

        <el-tabs v-else v-model="activeTab">
          <el-tab-pane name="table">
            <template #label>
              <span>{{ t('result.tabTableWithRows', { count: returnedRows }) }}</span>
            </template>

            <!-- 表格按索引取值（c0 / c1 ...）而非按列名，避免列名重名或含特殊字符时取值失败 -->
            <el-table
              :data="rows"
              border
              stripe
              size="small"
              max-height="520"
              :empty-text="t('common.empty')"
            >
              <el-table-column
                v-for="(name, i) in displayColumns"
                :key="i"
                :prop="`c${i}`"
                :label="name"
                min-width="120"
                show-overflow-tooltip
              />
            </el-table>

            <!-- 后端分页：切页会重新请求，不再本地切片 -->
            <el-pagination
              v-if="totalRows > 0"
              :current-page="currentPage"
              :page-size="pageSize"
              :page-sizes="[20, 50, 100, 200]"
              :total="totalRows"
              layout="total, sizes, prev, pager, next, jumper"
              class="pager"
              @current-change="handlePageChange"
              @size-change="handleSizeChange"
            />
          </el-tab-pane>

          <el-tab-pane name="json">
            <template #label>
              <span>{{ t('result.tabJson') }}</span>
            </template>
            <pre class="raw-json">{{ rawJson }}</pre>
          </el-tab-pane>
        </el-tabs>
      </section>

      <!-- ---------- 图表 ---------- -->
      <section v-show="activeNav === 'chart'" class="panel">
        <header class="panel-head">
          <div>
            <h2 class="panel-title">{{ t('chart.title') }}</h2>
            <p class="panel-sub">{{ t('chart.subtitle') }}</p>
          </div>

          <!-- 能否导出由 ChartPanel 算好后传上来 —— 动画途中会截到半张图 -->
          <el-button size="small" :disabled="!chartCanExport" @click="handleExportChart">
            {{ t('chart.exportPng') }}
          </el-button>
        </header>

        <ChartPanel
          ref="chartPanelRef"
          :executed-sql="executedSql"
          :columns="columns"
          :column-types="columnTypes"
          @can-export="chartCanExport = $event"
        />
      </section>
    </main>

    <!-- ==================== 已退出遮罩 ==================== -->
    <!--
      后台进程结束后盖住整个界面：这时页面上任何按钮都点不动了，
      继续显示原界面只会让人以为是程序坏了。

      正常情况下上面已经调用 window.close() 把标签页关掉了，用户看不到这一层；
      这一层只在「关不掉」时出现。

      按 MDN 对 Window.close() 的规则，窗口是网页自己创建的、或者**没有被导航过**
      （history 长度仍为 1）时，浏览器才允许脚本关闭它。本页是应用用系统命令
      从外部打开的标签页，属于后者：
        · 自动打开 → 全新标签页，history 长度为 1 → 关得掉，看不到这一层
        · 用户手动在浏览器里输过地址、或这个标签页里本来就有别的页面
          → history 长度大于 1 → 被拒，而且**拒绝时不报错**
    -->
    <div v-if="appExited" class="exited-mask">
      <div class="exited-card">
        <div class="exited-title">{{ t('app.exited.title') }}</div>
        <p class="exited-text">
          {{ t('app.exited.text') }}<br />
          {{ t('app.exited.text2') }}
        </p>
        <el-button type="primary" @click="handleCloseTab">{{ t('app.exited.close') }}</el-button>
        <!--
          按钮点了没反应时把快捷键写出来。直接用 @click="tryCloseTab" 是不行的：
          那样点击事件对象会被当作回调参数传进去
        -->
        <p v-if="closeHintVisible" class="exited-hint">
          {{ t('app.exited.shortcutHint', { key: closeShortcut }) }}
        </p>
      </div>
    </div>
    </div>
  </el-config-provider>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { Coin, EditPen, Grid, Setting, TrendCharts } from '@element-plus/icons-vue'
// 导出失败的提示走气泡：它是一次性动作的结果，不该常驻，
// 更不该把已经查出来的结果表格顶掉
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import elZhCn from 'element-plus/es/locale/lang/zh-cn'
import elEnUs from 'element-plus/es/locale/lang/en'
import DataSourceForm from './components/DataSourceForm.vue'
import DataSourcePanel from './components/DataSourcePanel.vue'
import ChartPanel from './components/ChartPanel.vue'
import SettingsPanel from './components/SettingsPanel.vue'
import { listDataSourceTypes, listDataSources } from './api/datasource'
import { queryTable, stripErrorPrefix } from './api/query'
import { requestHeaders } from './api/token'
import { closeShortcutHint, tryCloseTab, watchForShutdown } from './api/lifecycle'

const { t, locale } = useI18n()

// Element Plus 自带文案的语言包。跟着 i18n 的 locale 走，
// 否则会出现「按钮是中文、分页是英文」的半截状态
const elementLocale = computed(() => (locale.value === 'en' ? elEnUs : elZhCn))

// 左侧功能项。icon 是组件本身，模板里用 <component :is> 渲染。
// label 存的是词条键而不是文案 —— 切换语言时要跟着变
const navItems = computed(() => [
  { key: 'datasource', labelKey: 'app.nav.datasource', icon: Coin },
  { key: 'query', labelKey: 'app.nav.query', icon: EditPen },
  { key: 'result', labelKey: 'app.nav.result', icon: Grid },
  { key: 'chart', labelKey: 'app.nav.chart', icon: TrendCharts },
  // 设置放最底部：低频入口，不打断「数据源 → 查询 → 结果 → 图表」的主流程
  { key: 'settings', labelKey: 'app.nav.settings', icon: Setting },
])

// 默认停在「数据源」而不是「查询」：
// 没有数据源时查询什么都查不了，直接甩一个 SQL 界面只会让人困惑「该填什么」。
// 而且真实使用顺序本来就是先加数据源、再写 SQL，界面的初始位置应当与它一致
const activeNav = ref('datasource')

// 初始留空，等 onMounted 拉到数据源后填成「最新那个数据源」的全表查询。
// 一个数据源都没有时保持为空 —— 那时写什么都查不了，空白反而更诚实
const sql = ref('')

// 用户是否动过输入框。动过之后就不再自动改写默认 SQL
const editedSql = ref(false)

// 结果状态
const columns = ref([])
const columnTypes = ref([])
const data = ref([])
const totalRows = ref(0)
// 后端返回的 hasMore。这里只做「存下来 + 在 JSON 标签页里原样透出」，
// 不驱动任何按钮逻辑 —— 翻页靠 totalRows 与 pageSize 算，翻过头也只是拿到空数组。
// 不是漏接了逻辑，见 QueryResponse.java 里 hasMore / elapsed 那段的说明
const hasMore = ref(false)

/*
 * 查询侧的错误。只有它参与 hasResult 判定 —— 查询失败时确实没有数据可展示。
 *
 * 导出错误不写这里：导出失败时数据好端端在，把它写进来会让 hasResult
 * 变成 false，整张表连同数据一起消失，用户会以为查询结果丢了。
 * 见下方的 exportError
 */
const queryError = ref('')

// 报错下方的紫色补充提示，如「您可能想要访问 "Salary"」。没有时为 ''
const errorHint = ref('')
const activeTab = ref('table')

// 上次执行成功的 SQL。图表用它聚合，导出也用它 ——
// 避免文本框改动后图表、表格、导出三者对不上
const executedSql = ref('')

// 后端分页状态
const currentPage = ref(1)
const pageSize = ref(50)

// 执行中 / 导出中
const querying = ref(false)
const exporting = ref(false)

// 后端下发的数据源类型描述，驱动配置表单动态渲染。
//
// 【里面的文案是会变的】类型名、字段名、字段说明都由后端按请求头
// X-TinyQuery-Locale 下发，所以切换语言后必须重新取一次 —— 否则界面外壳
// 变成英文了，这四处却还停在上一份（见下方的 watch）。
// 曾经就是这样：切到英文再切回中文，配置项名字、问号提示、类型下拉、
// 已注册数据源的类型名会一直显示英文。
const types = ref([])
const panelRef = ref(null)

// 图表实例的引用。导出按钮在面板头，得跨组件调 ChartPanel 的方法
const chartPanelRef = ref(null)

// 图表能否导出（有图且不在动画中）。由 ChartPanel 算好后传上来
const chartCanExport = ref(false)

// 本次实际返回的行数
const returnedRows = computed(() => data.value.length)

// 是否有可展示的结果。只看查询侧的错误 ——
// 导出失败不该让已经查出来的数据消失
const hasResult = computed(
  () => queryError.value === '' && (columns.value.length > 0 || data.value.length > 0),
)

// 表头：后端未返回列名时按列数生成占位表头，保证表格仍可读
const displayColumns = computed(() => {
  if (columns.value.length > 0) {
    return columns.value
  }
  const width = data.value.length > 0 ? data.value[0].length : 0
  return Array.from({ length: width }, (_, i) =>
    t('result.placeholderColumn', { n: i + 1 }))
})

// 结果页的副标题：有结果时当分页说明用，没有时给一句引导
const resultSummary = computed(() => {
  if (!hasResult.value) {
    return t('result.emptyHint')
  }
  if (totalRows.value <= pageSize.value) {
    return t('result.rows', { count: totalRows.value })
  }
  const from = (currentPage.value - 1) * pageSize.value + 1
  const to = Math.min(currentPage.value * pageSize.value, totalRows.value)
  return t('result.summary', {
    total: totalRows.value,
    from,
    to,
  })
})

// 把后端的「数组的行」转成 el-table 需要的「对象的行」。
// 键固定用 c0 / c1 ...，与列名内容无关
const rows = computed(() =>
  data.value.map((row) => {
    const item = {}
    row.forEach((value, i) => {
      item[`c${i}`] = value
    })
    return item
  }),
)

// 原始 JSON 只在切到该标签页时才序列化，避免无谓的大字符串构造
const rawJson = computed(() => {
  if (activeTab.value !== 'json') {
    return ''
  }
  return JSON.stringify(
    {
      code: 0,
      message: 'success',
      columns: columns.value,
      columnTypes: columnTypes.value,
      data: data.value,
      hasMore: hasMore.value,
      totalRows: totalRows.value,
      page: currentPage.value,
      pageSize: pageSize.value,
    },
    null,
    2,
  )
})

// 后台程序是否已经退出。为 true 时盖一层遮罩，见模板末尾
const appExited = ref(false)

// window.close() 被浏览器拒绝时置为 true，用来显示「请按 Ctrl+W」的提示。
// 什么时候会被拒：标签页**被导航过**（history 长度大于 1）时。
// 从自动打开进来的全新标签页 history 长度为 1，不会被拒 —— 所以这个提示平时看不到，
// 它是给「用户自己在浏览器里输地址进来」这种兜底场景准备的
const closeHintVisible = ref(false)

// 关闭快捷键的文案随平台变化（macOS 是 ⌘W）。进页面时算一次即可
const closeShortcut = closeShortcutHint()

// 停止存活监视的函数，组件卸载时调用，避免定时器泄漏
let stopWatchingShutdown = null

// 统一的「关标签页」入口：关不掉就把快捷键提示显出来。
// 不把提示写死在模板里，是因为正常情况（自动打开的全新标签页）窗口真的会关掉，
// 用户根本看不到这一层
const requestCloseTab = () => {
  tryCloseTab(() => {
    closeHintVisible.value = true
  })
}

// 按钮的点击处理。不能直接写 @click="tryCloseTab" ——
// 那样点击事件对象会被当成「关不掉时的回调」参数传进去
const handleCloseTab = () => {
  requestCloseTab()
}

// 确认后台已退出：先试着直接关掉标签页，关不掉才靠遮罩兜底 + 给快捷键指引。
// 正常路径下 window.close() 会成功，用户看不到遮罩；
// 只有被导航过的标签页会被拒，那时才需要这一层
const handleAppExited = () => {
  appExited.value = true
  requestCloseTab()
}

/*
 * 默认 SQL 跟随最新注册的数据源。
 *
 * 早先是个写死的 'select count(*) from huge.csv' —— 那名字只在本机凑巧存在，
 * 换个环境就是一句必然报错的 SQL，用户第一眼看到的就是红字。
 * 现在由已注册的数据源生成 select * from <名字>，开箱即可执行。
 *
 * 「最新」按 registeredAt 判定，不靠数组顺序：后端返回的列表是按
 * 「类型 + 名称」排过序的，顺序里读不出谁先谁后。老数据源没有这个字段时为 0，
 * 自然排在最后 —— 此时退回列表末尾那个，与旧行为一致。
 *
 * 只在用户还没动过输入框时替换：用户敲了一半的语句不能被覆盖掉
 */
const applyDefaultSql = (items) => {
  if (editedSql.value || !Array.isArray(items) || items.length === 0) {
    return
  }

  // 兼容后端将来直接下发字符串数组的形态
  const names = items
    .map((item) => (typeof item === 'string' ? { name: item, registeredAt: 0 } : item))
    .filter((item) => item && typeof item.name === 'string' && item.name !== '')

  if (names.length === 0) {
    return
  }

  let latest = names[0]
  for (const item of names) {
    if ((item.registeredAt || 0) > (latest.registeredAt || 0)) {
      latest = item
    }
  }

  sql.value = `select * from ${latest.name}`
}

const refreshDataSources = async () => {
  try {
    const list = await listDataSources()
    applyDefaultSql(list)
  } catch {
    // 取不到就保持原样（此时输入框为空），不打扰用户
  }
}

onMounted(async () => {
  // 存活监视要尽早开始，且不能被下面那个请求的成败影响
  stopWatchingShutdown = watchForShutdown(handleAppExited)

  try {
    types.value = await listDataSourceTypes()
  } catch (error) {
    queryError.value = 'Error: ' + error.message
  }

  // 与 types 分开 try：上面失败不该连累默认 SQL
  await refreshDataSources()
})

onUnmounted(() => {
  if (stopWatchingShutdown) {
    stopWatchingShutdown()
  }
})

/*
 * 语言一变就重新取一次类型元数据。
 *
 * types 里的文案（类型名、字段名、字段说明）是后端按语言下发的，
 * 不是前端词条 —— 所以切语言时它不会自己变，必须重取。
 *
 * 不重取的后果实测过：从中文切到英文再切回中文，界面上「数据源类型」
 * 下拉、配置项名字、名字右边的问号提示、以及已注册数据源分组标题
 * 会一直停留在英文 —— 因为那四处都读同一份 types。
 *
 * 放在 watch 里而不是切语言的按钮里：语言可能从多处改变
 * （设置面板、启动时的 reconcileLocale 与服务端对齐），
 * 挂在 locale 上才能全都覆盖到。
 *
 * 失败不打扰用户：保留原有 types，界面顶多是语言没跟上，
 * 不影响已经注册的数据源与查询
 */
watch(locale, async () => {
  try {
    types.value = await listDataSourceTypes()
  } catch {
    // 保留旧值
  }
})

// 注册成功后刷新数据源列表，并让默认 SQL 跟上新注册的那个
const handleRegistered = () => {
  if (panelRef.value) {
    panelRef.value.load()
  }
  refreshDataSources()
}

/*
 * 执行查询。
 *
 * @param sqlOverride       显式指定要执行的 SQL。翻页时传 executedSql，
 *                          保证翻的是当前结果集；不传则取输入框内容（点「执行」）
 * @param resetToFirstPage  是否先回到第 1 页。点「执行」和切换每页条数时为 true
 *
 * 两种调用方式不能合并成一个布尔参数：翻页要「用旧 SQL + 保持页码」，
 * 点执行要「用新 SQL + 回到第 1 页」，是两组独立的意图
 */
const executeQuery = async (sqlOverrideOrReset = false) => {
  let requestSql
  let resetToFirstPage

  if (typeof sqlOverrideOrReset === 'string') {
    requestSql = sqlOverrideOrReset
    resetToFirstPage = false
  } else {
    requestSql = sql.value
    resetToFirstPage = sqlOverrideOrReset === true
  }

  if (resetToFirstPage) {
    currentPage.value = 1
  }
  queryError.value = ''
  errorHint.value = ''
  querying.value = true

  try {
    const body = await queryTable({
      sql: requestSql,
      page: currentPage.value,
      pageSize: pageSize.value,
    })

    columns.value = Array.isArray(body.columns) ? body.columns : []
    columnTypes.value = Array.isArray(body.columnTypes) ? body.columnTypes : []
    data.value = Array.isArray(body.data) ? body.data : []
    totalRows.value = body.totalRows || 0
    hasMore.value = body.hasMore === true
    executedSql.value = requestSql

    // 后端会收窄非法的 pageSize（上限 1000），以响应为准回写
    if (body.pageSize > 0) {
      pageSize.value = body.pageSize
    }

    // 执行成功就跳到结果页 —— 否则点了执行还得手动切一次标签才看得到东西。
    // 失败时不跳，报错留在查询页，改完 SQL 就地重试
    activeNav.value = 'result'
  } catch (error) {
    resetResult()
    // 后端可能带 "error:" 前缀（SQLParser 那组）也可能不带（数据源那组），
    // api 层已把前缀剥掉，这里统一补上 "Error: "，界面风格才一致
    queryError.value = 'Error: ' + error.message
    errorHint.value = error.hint || ''
  } finally {
    querying.value = false
  }
}

/*
 * 切页。
 *
 * 必须用 executedSql（上次执行成功的那条），不能用输入框里的实时内容。
 * 否则「执行了 A、把输入框改成 B、点下一页」会让表格切到 B 的数据，
 * 而图表和导出还在用 A —— 同一屏上三份数据对不上。
 *
 * 输入框改了但没执行时，先问一句再决定，不静默用哪一个
 */
const handlePageChange = async (page) => {
  if (sql.value !== executedSql.value) {
    try {
      await ElMessageBox.confirm(
        t('query.changedText'),
        t('query.changedTitle'),
        {
          type: 'warning',
          confirmButtonText: t('query.runNew'),
          cancelButtonText: t('query.keepOld'),
        },
      )
    } catch {
      // 选「沿用上次结果」：在当前这条 executedSql 的结果集里翻页
      currentPage.value = page
      executeQuery(executedSql.value)
      return
    }
    // 选「执行新 SQL」：回到第一页重新查
    executeQuery(true)
    return
  }

  currentPage.value = page
  executeQuery(executedSql.value)
}

// 改每页条数：回到第一页重新请求。
// 与翻页同理，改的是分页参数、不是 SQL —— 必须沿用 executedSql，
// 否则输入框里改了没执行的内容会把结果集换掉
const handleSizeChange = (size) => {
  pageSize.value = size
  currentPage.value = 1
  executeQuery(executedSql.value)
}

// 清空上一批结果，避免失败时旧数据残留造成误读
const resetResult = () => {
  columns.value = []
  columnTypes.value = []
  data.value = []
  totalRows.value = 0
  hasMore.value = false
}

// 导出图表 PNG。真正的绘制在 ChartPanel 里，这里只负责转发点击
const handleExportChart = () => {
  if (chartPanelRef.value) {
    chartPanelRef.value.exportPng()
  }
}

/*
 * 面板用 v-show 隐藏，而不是 v-if 卸载。
 *
 * 卸载会连带销毁 ChartPanel 里的图表实例与配色状态 —— 切走再切回来，
 * 逐扇区取的颜色、点亮的柱子全没了，还得重新请求一次。
 *
 * 但 display:none 期间容器量不到宽高，若图表恰好在隐藏时初始化，
 * canvas 会是 0×0。所以切回来时显式让它重新量一次
 */
watch(activeNav, async (value) => {
  if (value !== 'chart') {
    return
  }
  await nextTick()
  if (chartPanelRef.value) {
    chartPanelRef.value.resize()
  }
})

// 导出走独立的流式接口，不经过 JSON 解析，因此不受分页与行数上限影响。
// 用原生 fetch 而非 axios：axios 会把整个响应读进内存，百万行会撑爆浏览器
const exportQuery = async () => {
  // 导的是上次执行成功的那条 SQL，不是输入框里的实时内容 ——
  // 按钮已经搬到结果页，导出的必须就是页面上这份结果
  const targetSql = executedSql.value
  if (!targetSql) {
    return
  }

  exporting.value = true
  // 导出错误一律走右上角气泡，不写进 queryError ——
  // 写进去会让 hasResult 变 false，整张表连同已查出的数据一起消失
  errorHint.value = ''

  try {
    const response = await fetch('/api/query/export', {
      method: 'POST',
      // 导出走原生 fetch 而不是 axios，所以令牌得自己带上 ——
      // 这是三处 API 调用里最容易漏掉的一处
      headers: requestHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ sql: targetSql }),
    })

    // 网关或容器返回的 4xx/5xx 不带 CSV 的 Content-Type，也不会走后端的
    // JSON 错误体分支（后端失败时回的也是 200）。不拦的话会被下面的
    // blob() 当成数据，下载成一个内容是错误页的 .csv
    if (!response.ok) {
      ElMessage.error(t('result.exportHttpError', { status: response.status }))
      return
    }

    // 后端在解析或过滤阶段失败时，会用 JSON 错误体回包而不是 CSV。
    // 导出走原生 fetch，不经过 api 层的拦截器，所以前缀得在这里自己剥
    const contentType = response.headers.get('Content-Type') || ''
    if (contentType.includes('application/json')) {
      const body = await response.json()
      ElMessage.error('Error: ' + stripErrorPrefix(body.message || t('result.exportFailed')))
      return
    }

    // 取文件名：优先用后端下发的中文名，取不到时退回默认名
    const fileName = resolveFileName(response.headers.get('Content-Disposition'))

    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error('Error: ' + stripErrorPrefix(error.message))
  } finally {
    exporting.value = false
  }
}

// 从 Content-Disposition 里取 filename*（UTF-8 编码的中文名），取不到就用 filename
const resolveFileName = (disposition) => {
  if (!disposition) {
    return 'export.csv'
  }

  const utf8 = disposition.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8) {
    try {
      return decodeURIComponent(utf8[1])
    } catch {
      // 解码失败则退回下面那条
    }
  }

  const plain = disposition.match(/filename="?([^";]+)"?/i)
  return plain ? plain[1] : 'export.csv'
}
</script>

<style>
/* 应用外壳。这里不用 scoped —— 根布局需要穿透到 Element Plus 的内部结构
   （拍平嵌套卡片），且 .shell / .panel 这些类名只在本文件出现 */
.shell {
  display: flex;
  min-height: 100vh;
}

/* ==================== 侧栏 ==================== */
.sidebar {
  position: sticky;
  top: 0;
  flex: 0 0 var(--sidebar-w);
  width: var(--sidebar-w);
  height: 100vh;
  display: flex;
  flex-direction: column;
  padding: var(--sp-4) var(--sp-3);
  /*
   * 浅灰紫实底。
   *
   * 刻意不用毛玻璃：半透明 + saturate 时，最终颜色是
   * 「填的色 × 背后是什么 × 放大倍数」三者相乘，这正是前几轮颜色
   * 反复出乎意料的原因。不透明填色 = 填什么就是什么。
   *
   * 与页面底色的区分来自灰度而非明度 —— 两者明度只差 3%，
   * 不形成明暗断层；饱和度差 16%，一眼能分出来
   */
  background: var(--sidebar-bg);
  border-right: 1px solid var(--sidebar-line);
}

.brand {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  /* 字号放大后不再固定行高，交给内容撑开，避免文字被裁 */
  min-height: 44px;
  padding: 0 var(--sp-2);
  margin-bottom: var(--sp-5);
}

/* 品牌标记：一颗小水晶，用渐变 + 内高光做出切面感 */
.brand-dot {
  flex: none;
  width: 13px;
  height: 13px;
  border-radius: 4px;
  background: linear-gradient(140deg, var(--accent-bright), var(--accent) 55%, var(--accent-deep));
  box-shadow:
    0 2px 8px var(--accent-glow),
    inset 0 1px 0 rgba(255, 255, 255, 0.7);
}

/* 字标。大字号的字距要往回收一点 ——
   同样的 letter-spacing 在 15px 上正好，在 26px 上就会显得松散 */
.brand-name {
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.015em;
  line-height: 1.1;
  color: var(--ink);
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
}

/* 导航项是 button，但样式全在这里定义 ——
   旧版有一条全局 button 规则会塞进内边距和外边距，已随本次改版移除 */
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 40px;
  padding: 0 var(--sp-3);
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  font-family: inherit;
  font-size: 13.5px;
  color: var(--ink-2);
  text-align: left;
  cursor: pointer;
  transition:
    background 0.18s ease,
    color 0.18s ease;
}

/* 悬停提亮、选中压深 —— 两档方向相反，切换时不会分不清是哪一种状态 */
.nav-item:hover {
  background: var(--surface-raised);
  color: var(--ink);
}

/* 选中态：深紫药丸。全站最大的一块重色，落点明确 */
.nav-item.is-active {
  background: var(--plum);
  color: var(--pure-white);
  box-shadow:
    var(--shadow-plum),
    inset 0 1px 0 rgba(255, 255, 255, 0.12);
}

.nav-icon {
  font-size: 16px;
}

/* 页脚。margin-bottom 把这条分隔线从底边往上提一档，
   不然它贴着视口底部，显得被挤住了 */
.sidebar-foot {
  margin-bottom: var(--sp-3);
  padding: var(--sp-3) var(--sp-3) 0;
  border-top: 1px solid var(--sidebar-line);
  font-size: 11.5px;
  letter-spacing: 0.03em;
  color: var(--ink-4);
}

/* ==================== 内容区 ==================== */
.main {
  flex: 1;
  /* flex 子项默认 min-width:auto，表格会把布局撑破，必须归零 */
  min-width: 0;
  padding: var(--sp-5) var(--sp-6) var(--sp-6);
}

.panel {
  background: var(--glass-bg-strong);
  backdrop-filter: var(--glass-filter-light);
  -webkit-backdrop-filter: var(--glass-filter-light);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-crystal);
  padding: var(--sp-5);
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
  margin-bottom: var(--sp-5);
}

.panel-title {
  margin: 0;
  font-size: 17px;
  font-weight: 650;
  letter-spacing: 0.01em;
  color: var(--ink);
}

.panel-sub {
  margin: 4px 0 0;
  font-size: 12.5px;
  line-height: 1.5;
  color: var(--ink-3);
}

/* 面板本身就是卡片，里面的 el-card 再描一层边就重了 —— 拍平成纯内容 */
.panel .el-card {
  border: none;
  background: transparent;
  box-shadow: none;
}

.panel .el-card__header {
  padding: 0 0 var(--sp-3);
  border-bottom: 1px solid var(--line);
}

.panel .el-card__body {
  padding: var(--sp-4) 0 0;
}

.panel-alert {
  margin-top: var(--sp-4);
}

/* ==================== 查询 ==================== */
.sql-input {
  width: 100%;
  height: 130px;
  padding: var(--sp-3) var(--sp-4);
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  /* 输入框是纯内容区，不叠玻璃 —— 半透明底会让输入的文字发虚 */
  background: var(--surface);
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, 'Courier New', monospace;
  font-size: 14px;
  line-height: 1.7;
  color: var(--ink);
  resize: vertical;
  outline: none;
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease;
}

.sql-input::placeholder {
  color: var(--ink-4);
}

.sql-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-glow);
}

.action-row {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-3);
}

/* Element Plus 给相邻按钮加了 margin-left，和 gap 叠在一起会变成双倍间距 */
.action-row .el-button + .el-button {
  margin-left: 0;
}

.action-hint {
  margin-left: auto;
  font-size: 12px;
  color: var(--ink-4);
}

/* ==================== 结果 ==================== */
.pager {
  margin-top: var(--sp-4);
  justify-content: flex-end;
}

.raw-json {
  margin: 0;
  padding: var(--sp-4);
  max-height: 520px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
  background: var(--surface);
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--ink-2);
}

/* ==================== 兜底 ==================== */
/* 不支持毛玻璃就退回实心。侧栏已是实底，不在列 */
@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px))) {
  .panel {
    background: var(--surface);
  }
}

@media (prefers-reduced-motion: reduce) {
  .nav-item {
    transition: none;
  }
}

/* 窄屏：侧栏改成顶部横条，不再占一列 */
@media (max-width: 860px) {
  .shell {
    flex-direction: column;
  }

  .sidebar {
    position: static;
    flex: none;
    width: 100%;
    height: auto;
    flex-direction: row;
    align-items: center;
    gap: var(--sp-3);
    padding: var(--sp-2) var(--sp-3);
    border-right: none;
    border-bottom: 1px solid var(--sidebar-line);
  }

  .brand {
    min-height: auto;
    margin-bottom: 0;
    padding: 0;
  }

  /* 窄屏横条放不下 26px 字标，缩回一档 */
  .brand-name {
    font-size: 18px;
  }

  .nav {
    flex-direction: row;
    flex: none;
  }

  .nav-item {
    width: auto;
    padding: 0 var(--sp-3);
  }

  .sidebar-foot {
    display: none;
  }

  .main {
    padding: var(--sp-4);
  }
}

/* ==================== 已退出遮罩 ==================== */
/*
  后台进程结束后盖住整个界面。用 fixed 而不是跟着 .shell 走 ——
  页面可能已经滚动了，遮罩必须始终铺满视口。
  z-index 高于 Element Plus 的弹层（默认 2000），否则对话框会浮在遮罩上面
*/
.exited-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--scrim);
  backdrop-filter: var(--glass-filter-light);
}

.exited-card {
  max-width: 380px;
  padding: var(--sp-6);
  text-align: center;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  box-shadow: var(--shadow-crystal);
}

.exited-title {
  margin-bottom: var(--sp-3);
  font-size: 17px;
  font-weight: 600;
  color: var(--ink);
}

.exited-text {
  margin: 0 0 var(--sp-5);
  font-size: 13px;
  line-height: 1.7;
  color: var(--ink-2);
}

/*
  浏览器拒绝关闭标签页时出现的提示。刻意放在按钮下方 ——
  用户是先点按钮、发现没反应，才会低头看这行字
*/
.exited-hint {
  margin: var(--sp-4) 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ink-3);
}

.exited-hint kbd {
  display: inline-block;
  padding: 1px 6px;
  margin: 0 2px;
  font-family: inherit;
  font-size: 12px;
  color: var(--ink);
  background: var(--el-fill-color-light);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
}
</style>
