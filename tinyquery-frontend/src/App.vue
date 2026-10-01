<template>
  <div class="shell">
    <!-- ==================== 左侧功能栏 ==================== -->
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-dot" />
        <span class="brand-name">TinyQuery</span>
      </div>

      <nav class="nav">
        <button
          v-for="item in NAV_ITEMS"
          :key="item.key"
          type="button"
          class="nav-item"
          :class="{ 'is-active': activeNav === item.key }"
          @click="activeNav = item.key"
        >
          <el-icon class="nav-icon"><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <div class="sidebar-foot">本地查询引擎 · V1.0</div>
    </aside>

    <!-- ==================== 右侧内容区 ==================== -->
    <main class="main">
      <!-- ---------- 数据源 ---------- -->
      <section v-show="activeNav === 'datasource'" class="panel">
        <header class="panel-head">
          <div>
            <h2 class="panel-title">数据源</h2>
            <p class="panel-sub">注册 CSV 文件或数据库连接，注册后可在 SQL 的 FROM 中按名称引用</p>
          </div>
        </header>

        <DataSourceForm :types="types" @registered="handleRegistered" />
        <DataSourcePanel ref="panelRef" />
      </section>

      <!-- ---------- 查询 ---------- -->
      <section v-show="activeNav === 'query'" class="panel">
        <header class="panel-head">
          <div>
            <h2 class="panel-title">查询</h2>
            <p class="panel-sub">支持 SELECT 投影、WHERE 条件、ORDER BY 单列排序与 LIMIT</p>
          </div>
        </header>

        <textarea
          v-model="sql"
          class="sql-input"
          spellcheck="false"
          placeholder="输入 SQL，例如 select * from sample.csv"
          @keydown.ctrl.enter="executeQuery(true)"
          @keydown.meta.enter="executeQuery(true)"
        />

        <!-- 导出按钮不在这里 —— 它导出的是结果，放在「结果」页更顺 -->
        <div class="action-row">
          <el-button type="primary" :loading="querying" @click="executeQuery(true)">
            执行
          </el-button>
          <span class="action-hint">Ctrl + Enter 执行</span>
        </div>

        <!-- 执行失败发生在本页，提示留在这里 -->
        <el-alert
          v-if="errorMessage"
          type="error"
          show-icon
          :closable="false"
          class="panel-alert"
          :title="errorMessage"
        />
      </section>

      <!-- ---------- 结果 ---------- -->
      <section v-show="activeNav === 'result'" class="panel">
        <header class="panel-head">
          <div>
            <h2 class="panel-title">结果</h2>
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
            {{ exporting ? '导出中…' : '导出 CSV' }}
          </el-button>
        </header>

        <el-empty
          v-if="!hasResult"
          description="还没有查询结果，先在「查询」里执行一条 SQL"
          :image-size="90"
        />

        <el-tabs v-else v-model="activeTab">
          <el-tab-pane name="table">
            <template #label>
              <span>表格（{{ returnedRows }} 行）</span>
            </template>

            <!-- 表格按索引取值（c0 / c1 ...）而非按列名，避免列名重名或含特殊字符时取值失败 -->
            <el-table
              :data="rows"
              border
              stripe
              size="small"
              max-height="520"
              empty-text="无数据"
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
              <span>原始 JSON</span>
            </template>
            <pre class="raw-json">{{ rawJson }}</pre>
          </el-tab-pane>
        </el-tabs>
      </section>

      <!-- ---------- 图表 ---------- -->
      <section v-show="activeNav === 'chart'" class="panel">
        <header class="panel-head">
          <div>
            <h2 class="panel-title">图表</h2>
            <p class="panel-sub">按维度聚合，基于全量数据，不受表格分页影响</p>
          </div>

          <!-- 能否导出由 ChartPanel 算好后传上来 —— 动画途中会截到半张图 -->
          <el-button size="small" :disabled="!chartCanExport" @click="handleExportChart">
            导出 PNG
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
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { Coin, EditPen, Grid, TrendCharts } from '@element-plus/icons-vue'
import DataSourceForm from './components/DataSourceForm.vue'
import DataSourcePanel from './components/DataSourcePanel.vue'
import ChartPanel from './components/ChartPanel.vue'
import { listDataSourceTypes } from './api/datasource'
import { queryTable } from './api/query'
import { tokenHeaders } from './api/token'

// 左侧功能项。icon 是组件本身，模板里用 <component :is> 渲染
const NAV_ITEMS = [
  { key: 'datasource', label: '数据源', icon: Coin },
  { key: 'query', label: '查询', icon: EditPen },
  { key: 'result', label: '结果', icon: Grid },
  { key: 'chart', label: '图表', icon: TrendCharts },
]

// 默认停在「数据源」而不是「查询」：
// 没有数据源时查询什么都查不了，直接甩一个 SQL 界面只会让人困惑「该填什么」。
// 而且真实使用顺序本来就是先加数据源、再写 SQL，界面的初始位置应当与它一致
const activeNav = ref('datasource')

const sql = ref('select count(*) from huge.csv')

// 结果状态
const columns = ref([])
const columnTypes = ref([])
const data = ref([])
const totalRows = ref(0)
const hasMore = ref(false)
const errorMessage = ref('')
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

// 后端下发的数据源类型描述，驱动配置表单动态渲染
const types = ref([])
const panelRef = ref(null)

// 图表实例的引用。导出按钮在面板头，得跨组件调 ChartPanel 的方法
const chartPanelRef = ref(null)

// 图表能否导出（有图且不在动画中）。由 ChartPanel 算好后传上来
const chartCanExport = ref(false)

// 本次实际返回的行数
const returnedRows = computed(() => data.value.length)

// 是否有可展示的结果
const hasResult = computed(
  () => errorMessage.value === '' && (columns.value.length > 0 || data.value.length > 0),
)

// 表头：后端未返回列名时按列数生成占位表头，保证表格仍可读
const displayColumns = computed(() => {
  if (columns.value.length > 0) {
    return columns.value
  }
  const width = data.value.length > 0 ? data.value[0].length : 0
  return Array.from({ length: width }, (_, i) => `第 ${i + 1} 列`)
})

// 结果页的副标题：有结果时当分页说明用，没有时给一句引导
const resultSummary = computed(() => {
  if (!hasResult.value) {
    return '执行查询后，明细数据会显示在这里'
  }
  if (totalRows.value <= pageSize.value) {
    return `共 ${totalRows.value} 行`
  }
  const from = (currentPage.value - 1) * pageSize.value + 1
  const to = Math.min(currentPage.value * pageSize.value, totalRows.value)
  return `共 ${totalRows.value} 行，当前显示第 ${from}-${to} 行`
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

onMounted(async () => {
  try {
    types.value = await listDataSourceTypes()
  } catch (error) {
    errorMessage.value = error.message
  }
})

// 注册成功后刷新数据源列表
const handleRegistered = () => {
  if (panelRef.value) {
    panelRef.value.load()
  }
}

// 切页：重新向后端请求。resetToFirstPage 为 true 时（点执行）先回到第 1 页
const executeQuery = async (resetToFirstPage = false) => {
  if (resetToFirstPage) {
    currentPage.value = 1
  }
  errorMessage.value = ''
  querying.value = true

  // 记下这次请求的 SQL，成功后才写进 executedSql ——
  // 失败时保留上一次成功的值，图表不会突然变成空
  const requestSql = sql.value

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
    errorMessage.value = 'Error: ' + error.message
  } finally {
    querying.value = false
  }
}

// 切页
const handlePageChange = (page) => {
  currentPage.value = page
  executeQuery()
}

// 改每页条数：回到第一页重新请求
const handleSizeChange = (size) => {
  pageSize.value = size
  currentPage.value = 1
  executeQuery()
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
  errorMessage.value = ''

  try {
    const response = await fetch('/api/query/export', {
      method: 'POST',
      // 导出走原生 fetch 而不是 axios，所以令牌得自己带上 ——
      // 这是三处 API 调用里最容易漏掉的一处
      headers: { 'Content-Type': 'application/json', ...tokenHeaders() },
      body: JSON.stringify({ sql: targetSql }),
    })

    // 网关或容器返回的 4xx/5xx 不带 CSV 的 Content-Type，也不会走后端的
    // JSON 错误体分支（后端失败时回的也是 200）。不拦的话会被下面的
    // blob() 当成数据，下载成一个内容是错误页的 .csv
    if (!response.ok) {
      errorMessage.value = `导出失败：服务端返回 ${response.status}`
      return
    }

    // 后端在解析或过滤阶段失败时，会用 JSON 错误体回包而不是 CSV
    const contentType = response.headers.get('Content-Type') || ''
    if (contentType.includes('application/json')) {
      const body = await response.json()
      errorMessage.value = body.message || '导出失败'
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
    errorMessage.value = '导出失败：' + error.message
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
  background: linear-gradient(140deg, #c4b5fd, var(--accent) 55%, var(--accent-deep));
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
  background: rgba(255, 255, 255, 0.85);
  color: var(--ink);
}

/* 选中态：深紫药丸。全站最大的一块重色，落点明确 */
.nav-item.is-active {
  background: var(--plum);
  color: #fff;
  box-shadow:
    0 6px 18px rgba(36, 26, 51, 0.2),
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
</style>
