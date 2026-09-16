<template>
  <div class="app">
    <h1>TinyQuery Platform</h1>

    <!-- 数据源管理区 -->
    <el-card shadow="never" class="section">
      <template #header>
        <span>数据源管理</span>
      </template>

      <DataSourceForm :types="types" @registered="handleRegistered" />
    </el-card>

    <el-card shadow="never" class="section">
      <DataSourcePanel ref="panelRef" />
    </el-card>

    <!-- SQL 查询区 -->
    <el-card shadow="never" class="section">
      <template #header>
        <span>SQL 查询</span>
      </template>

      <!-- SQL input -->
      <textarea v-model="sql" placeholder="Enter SQL, e.g. select * from sample.csv"></textarea>

      <!-- 执行与导出。导出走流式接口，不受分页与行数上限影响 -->
      <div class="action-row">
        <button @click="executeQuery(true)">Execute</button>
        <button :disabled="exporting" @click="exportQuery">
          {{ exporting ? '导出中…' : '导出 CSV' }}
        </button>
      </div>

      <!-- 页码信息：后端分页时，点页码会重新请求 -->
      <el-alert
        v-if="!errorMessage && columns.length > 0 && totalRows > 0"
        type="info"
        show-icon
        :closable="false"
        class="result-alert"
        :title="pageSummary"
      />

      <!-- 执行失败提示 -->
      <el-alert
        v-if="errorMessage"
        type="error"
        show-icon
        :closable="false"
        class="result-alert"
        :title="errorMessage"
      />

      <!-- 结果展示：表格为主，原始 JSON 作为备选 -->
      <el-tabs v-if="hasResult" v-model="activeTab" class="result-tabs">
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
            max-height="480"
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
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import axios from 'axios'
import DataSourceForm from './components/DataSourceForm.vue'
import DataSourcePanel from './components/DataSourcePanel.vue'
import { listDataSourceTypes } from './api/datasource'

const sql = ref('select count(*) from huge.csv')

// 结果状态
const columns = ref([])
const data = ref([])
const totalRows = ref(0)
const hasMore = ref(false)
const errorMessage = ref('')
const activeTab = ref('table')

// 后端分页状态
const currentPage = ref(1)
const pageSize = ref(50)

// 导出状态
const exporting = ref(false)

// 后端下发的数据源类型描述，驱动配置表单动态渲染
const types = ref([])
const panelRef = ref(null)

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

// 页码说明。聚合查询只有一行，不显示区间
const pageSummary = computed(() => {
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

// 切页：重新向后端请求。resetToFirstPage 为 true 时（点 Execute）先回到第 1 页
const executeQuery = async (resetToFirstPage = false) => {
  if (resetToFirstPage) {
    currentPage.value = 1
  }
  errorMessage.value = ''

  try {
    const response = await axios.post('/api/query', {
      sql: sql.value,
      page: currentPage.value,
      pageSize: pageSize.value,
    })
    const body = response.data

    if (body.code !== 0) {
      resetResult()
      errorMessage.value = body.message || '查询失败'
      return
    }

    columns.value = Array.isArray(body.columns) ? body.columns : []
    data.value = Array.isArray(body.data) ? body.data : []
    totalRows.value = body.totalRows || 0
    hasMore.value = body.hasMore === true

    // 后端会收窄非法的 pageSize（上限 1000），以响应为准回写
    if (body.pageSize > 0) {
      pageSize.value = body.pageSize
    }
  } catch (error) {
    resetResult()
    errorMessage.value = 'Error: ' + error.message
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
  data.value = []
  totalRows.value = 0
  hasMore.value = false
}

// 导出走独立的流式接口，不经过 JSON 解析，因此不受分页与行数上限影响。
// 用原生 fetch 而非 axios：axios 会把整个响应读进内存，百万行会撑爆浏览器
const exportQuery = async () => {
  exporting.value = true
  errorMessage.value = ''

  try {
    const response = await fetch('/api/query/export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ sql: sql.value }),
    })

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
.app {
  max-width: 1100px;
  margin: 0 auto;
  padding: 20px;
  font-family: sans-serif;
}

.section {
  margin-bottom: 16px;
}

.result-alert {
  margin-top: 10px;
}

.result-tabs {
  margin-top: 10px;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}

.raw-json {
  background: #f5f5f5;
  padding: 15px;
  border-radius: 5px;
  overflow: auto;
  max-height: 480px;
  margin: 0;
}

textarea {
  width: 100%;
  height: 80px;
  font-family: monospace;
  padding: 10px;
  box-sizing: border-box;
}

/* 执行与导出并排 */
.action-row {
  display: flex;
  gap: 8px;
}

button {
  margin-top: 10px;
  padding: 10px 20px;
  cursor: pointer;
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
</style>
