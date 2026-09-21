<template>
  <div class="chart-panel">
    <!-- 还没执行过查询：没有列信息，无法配置图表 -->
    <div v-if="columns.length === 0" class="panel-hint">
      <el-empty description="请先执行一次查询，图表将基于该次查询的结果绘制" :image-size="80" />
    </div>

    <template v-else>
      <!-- 配置区。任何一项变化都会自动重绘，不需要额外的按钮 -->
      <div class="chart-config">
        <div class="cfg-group">
          <span class="cfg-label">维度</span>
          <el-select v-model="dimension" size="small" class="cfg-item">
            <el-option v-for="c in columns" :key="c" :label="c" :value="c" />
          </el-select>
        </div>

        <div class="cfg-group">
          <span class="cfg-label">度量1</span>
          <el-select
            v-model="measure"
            size="small"
            class="cfg-item"
            placeholder="无数值列"
            :disabled="aggregation === 'count'"
          >
            <el-option v-for="c in numericColumns" :key="c" :label="c" :value="c" />
          </el-select>
        </div>

        <!-- 第二个度量：饼图表示整体占比，放不下两个系列；count 模式度量整个不参与 -->
        <div v-if="showSecondMeasure" class="cfg-group">
          <span class="cfg-label">度量2</span>
          <el-select
            v-model="measure2"
            size="small"
            class="cfg-item"
            placeholder="（无）"
            clearable
          >
            <el-option v-for="c in secondMeasureOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </div>

        <div class="cfg-group">
          <span class="cfg-label">聚合</span>
          <el-select v-model="aggregation" size="small" class="cfg-item cfg-narrow">
            <el-option v-for="a in AGGREGATIONS" :key="a.value" :label="a.label" :value="a.value" />
          </el-select>
        </div>

        <div class="cfg-group">
          <span class="cfg-label">类型</span>
          <el-select v-model="chartType" size="small" class="cfg-item cfg-narrow">
            <el-option v-for="t in CHART_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </div>

        <!-- 配色：一个度量一套。两个度量时先选改哪个，再点色卡 -->
        <div class="cfg-group">
          <span class="cfg-label">配色</span>
          <el-select
            v-if="hasTwoSeries"
            v-model="paletteTarget"
            size="small"
            class="cfg-item cfg-narrow"
          >
            <el-option
              v-for="(_, i) in [0, 1]"
              :key="i"
              :label="`度量${i + 1}`"
              :value="i"
            />
          </el-select>
          <div class="swatches">
            <button
              v-for="p in PRESET_PALETTES"
              :key="p.id"
              type="button"
              class="swatch"
              :class="{ 'is-active': activePaletteId === p.id }"
              :title="p.label"
              :style="swatchStyle(p)"
              @click="activePaletteId = p.id"
            />
            <span class="swatch-custom" :class="{ 'is-active': activePaletteId === 'custom' }">
                <el-color-picker
                  v-model="activeCustomColor"
                  size="small"
                  :predefine="PRESET_COLORS"
                  @change="activePaletteId = 'custom'"
                />
              </span>
          </div>
        </div>

        <!-- 两个度量时才谈得上「统一」，单度量下开关没有意义 -->
        <el-checkbox v-if="hasTwoSeries" v-model="unifyScale" size="small" class="cfg-check">
          统一度量单位
        </el-checkbox>

        <el-checkbox v-model="keepFullPrecision" size="small" class="cfg-check">
          保留全部小数
        </el-checkbox>
      </div>

      <!-- 提示：分桶说明、其他合并、标签隐藏、饼图点击改色 -->
      <el-alert
        v-if="hint && !errorMessage"
        type="info"
        show-icon
        :closable="false"
        class="chart-alert"
        :title="hint"
      />

      <el-alert
        v-if="errorMessage"
        type="error"
        show-icon
        :closable="false"
        class="chart-alert"
        :title="errorMessage"
      />

      <!-- 容器始终保留，否则 el-empty 遮挡时 echarts 量不到宽高 -->
      <div class="chart-wrap" v-loading="loading">
        <div ref="chartRef" class="chart-box"></div>

        <!-- 扇形取色。点中某个扇区后浮在画布上，改完即时生效 -->
        <div
          v-if="sectorPopup"
          class="sector-popup"
          :style="{ left: `${sectorPopup.left}px`, top: `${sectorPopup.top}px` }"
        >
          <span class="popup-title" :title="sectorPopup.name">{{ sectorPopup.name }}</span>
          <el-color-picker
            v-model="sectorColor"
            size="small"
            :predefine="PRESET_COLORS"
            :teleported="false"
          />
          <el-button link size="small" @click="resetSectorColor">重置</el-button>
        </div>

        <div v-if="!hasChart && !errorMessage" class="chart-placeholder">
          <el-empty :description="loading ? '正在聚合…' : '暂无数据'" :image-size="60" />
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { queryChart } from '../api/query'

const props = defineProps({
  // 上次执行成功的 SQL。图表用的是这个，不是文本框里的实时内容 ——
  // 否则改了 SQL 没点 Execute 时，图表会先于表格变化
  executedSql: {
    type: String,
    default: '',
  },
  // 表格返回的列名与类型，用来填充下拉框
  columns: {
    type: Array,
    default: () => [],
  },
  columnTypes: {
    type: Array,
    default: () => [],
  },
})

// 导出按钮在父组件的卡片头里，能否导出由这里算好再传出去
const emit = defineEmits(['can-export'])

// 聚合方式。value 与后端 normalizeAggregation 的白名单一致
const AGGREGATIONS = [
  { value: 'sum', label: '求和' },
  { value: 'avg', label: '平均值' },
  { value: 'count', label: '计数' },
  { value: 'max', label: '最大值' },
  { value: 'min', label: '最小值' },
]

const CHART_TYPES = [
  { value: 'bar', label: '柱状图' },
  { value: 'line', label: '折线图' },
  { value: 'pie', label: '饼图' },
]

// 饼图扇区超过这个数就没法读了，让后端提前合并成「其他」。
// 柱状图可以多放一些，横向滚动还能看
const PIE_TOP_N = 10
const BAR_TOP_N = 50

// 改配置后延迟多久才发请求。连改几个下拉框时，
// 250ms 内的多次改动会合并成一次请求
const DEBOUNCE_MS = 250

// 动画兜底时长。finished 事件没触发时靠它恢复导出按钮
const ANIMATION_FALLBACK_MS = 1500

/**
 * 数据标签的显示阈值。
 *
 * 超过这个类别数就不标数字了 —— 50 根柱子每根都标必然糊成一团，
 * 那种情况下看 tooltip 更实际。这是硬开关，不做自适应
 */
const LABEL_MAX_CATEGORIES = 12

// 数值默认保留的小数位数。勾上「保留全部小数」后按原值显示
const DECIMAL_PLACES = 2

// 灰色文字。坐标轴名与数据标签共用，保证视觉统一
const MUTED_COLOR = '#909399'

// 坐标轴线与网格线。用户口中的「数据分割线」实际指的是网格线，
// 两个都画上，与 BI 工具的观感一致。
// 放在模块级而不是 renderChart 内部 —— buildYAxis 也要用，
// 而它是个独立的顶层函数，看不到另一个函数里的局部变量
const AXIS_STYLE = {
  axisLine: { lineStyle: { color: MUTED_COLOR } },
  splitLine: { lineStyle: { color: '#ebeef5' } },
}

// 每套配色要给足 10 色 —— 饼图最多 10 个扇区，少一个就会循环回第一个色，
// 首尾两个扇区撞色比少一个色更难分辨
const PALETTE_SIZE = 10

// 自定义配色的初始主色，与 ECharts 原生调色板的头两个色一致
const DEFAULT_PRIMARY = '#5470c6'
const DEFAULT_SECONDARY = '#91cc75'

// 扇形取色弹窗的预估宽度。点得太靠右时靠它把弹窗往回收，
// 免得顶出画布。不实测宽度是因为那要等弹窗渲染完再挪一次，会看到跳动
const SECTOR_POPUP_WIDTH = 230

/**
 * 预设配色。
 *
 * 每套都是手排的：主色打头，后面按色相拉开。没有用算法生成 ——
 * 预设的意义就是「开箱好看」，交给算法就失去意义了
 */
const PRESET_PALETTES = [
  {
    id: 'default',
    label: '默认',
    colors: [
      '#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de',
      '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc', '#c4ccd3',
    ],
  },
  {
    id: 'indigo',
    label: '靛蓝',
    colors: [
      '#2f54eb', '#40a9ff', '#13c2c2', '#52c41a', '#faad14',
      '#f5222d', '#722ed1', '#eb2f96', '#597ef7', '#36cfc9',
    ],
  },
  {
    id: 'cyan',
    label: '青绿',
    colors: [
      '#13c2c2', '#36cfc9', '#5cdbd3', '#52c41a', '#95de64',
      '#faad14', '#ff7a45', '#f5222d', '#2f54eb', '#9254de',
    ],
  },
  {
    id: 'amber',
    label: '暖橙',
    colors: [
      '#fa8c16', '#faad14', '#ffc53d', '#ff7a45', '#f5222d',
      '#eb2f96', '#722ed1', '#2f54eb', '#13c2c2', '#52c41a',
    ],
  },
  {
    id: 'violet',
    label: '紫罗兰',
    colors: [
      '#722ed1', '#9254de', '#b37feb', '#eb2f96', '#f5222d',
      '#fa8c16', '#faad14', '#52c41a', '#13c2c2', '#2f54eb',
    ],
  },
  {
    id: 'morandi',
    label: '莫兰迪',
    colors: [
      '#8c9ea8', '#a8b5bd', '#b8a99a', '#c9b8a8', '#9a9b8f',
      '#a3ad9c', '#b5a3a3', '#8f9aad', '#b0a5b8', '#c4bcae',
    ],
  },
]

// 取色器的快捷色板。取的是各预设的主色，方便在自定义时直接点
const PRESET_COLORS = [
  '#5470c6', '#2f54eb', '#13c2c2', '#52c41a', '#fa8c16',
  '#f5222d', '#722ed1', '#eb2f96', '#8c9ea8', '#faad14',
]

// 色相环上的黄金角。相邻两色相隔 137.5°，
// 任意前 N 个色的分布都足够散 —— 等分色相的话，
// 前 3~4 色会挤在一个色区里，两条系列看着像同色
const GOLDEN_ANGLE = 137.508

const clamp = (value, min, max) => Math.min(max, Math.max(min, value))

/**
 * #rgb / #rrggbb 转 HSL。
 *
 * 只写这一个方向就够了 —— 派生出的颜色直接用 hsl() 字符串交给 ECharts，
 * 不需要再转回 hex
 */
const hexToHsl = (hex) => {
  const raw = hex.replace('#', '')
  const full = raw.length === 3 ? raw.split('').map((c) => c + c).join('') : raw

  const r = parseInt(full.slice(0, 2), 16) / 255
  const g = parseInt(full.slice(2, 4), 16) / 255
  const b = parseInt(full.slice(4, 6), 16) / 255

  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const delta = max - min
  const lightness = (max + min) / 2

  // 灰色没有色相可言，饱和度为 0，色相取什么都一样
  if (delta === 0) {
    return [0, 0, lightness * 100]
  }

  const saturation = lightness > 0.5 ? delta / (2 - max - min) : delta / (max + min)

  let hue
  if (max === r) {
    hue = ((g - b) / delta + (g < b ? 6 : 0)) / 6
  } else if (max === g) {
    hue = ((b - r) / delta + 2) / 6
  } else {
    hue = ((r - g) / delta + 4) / 6
  }

  return [hue * 360, saturation * 100, lightness * 100]
}

/**
 * 由一个主色派生出整套配色。
 *
 * 主色原样保留在第一位 —— 用户拿品牌色取色，第一根柱子就该是那个色，
 * 被算法改浅改深会显得没生效。
 *
 * 明度卡在 35%~70%：太暗会和深色文字打架，太亮会糊在 #ebeef5 的网格线上
 */
const derivePalette = (primary) => {
  const [hue, saturation, lightness] = hexToHsl(primary)

  return Array.from({ length: PALETTE_SIZE }, (_, i) => {
    if (i === 0) {
      return primary
    }

    const nextHue = (hue + i * GOLDEN_ANGLE) % 360
    // 饱和度与明度各做一点起伏，否则整排颜色像同一个色块复制出来的
    const nextSat = clamp(saturation + ((i % 3) - 1) * 8, 35, 90)
    const nextLig = clamp(lightness + (i % 2 === 0 ? 6 : -6), 35, 70)

    return `hsl(${nextHue.toFixed(1)}, ${nextSat.toFixed(1)}%, ${nextLig.toFixed(1)}%)`
  })
}

const chartRef = ref(null)
const dimension = ref('')
const measure = ref('')
const measure2 = ref('')
const aggregation = ref('sum')
const chartType = ref('bar')

// 两个度量共用一个 Y 轴。开启时柱高可直接比较，
// 代价是量纲差异大时小的那个几乎贴着底边；关掉才各占一根轴
const unifyScale = ref(true)

// 数值是否按原样显示。关闭时一律保留两位小数
const keepFullPrecision = ref(false)

/**
 * 逐度量的配色。下标就是度量序号，所以柱/折线图里
 * 度量1 与度量2 各拿各的，不再共用一个颜色。
 *
 * 默认第二个走自定义而不是「默认」预设 —— 两套预设的主色都是同一个蓝，
 * 两个度量会撞成一样的颜色
 */
const paletteIds = ref(['default', 'custom'])
const customColors = ref([DEFAULT_PRIMARY, DEFAULT_SECONDARY])

// 配置区的色卡在改哪个度量。只有两个系列时才需要选
const paletteTarget = ref(0)

// 饼图逐扇区的自定义颜色，键是类别名。
// 用名字而不是下标 —— 换维度后下标全变了，名字还是那个名字
const sectorColors = ref({})

// 扇形取色弹窗。null 表示没开；开了就记着改的是哪个扇区、浮在哪
const sectorPopup = ref(null)

const loading = ref(false)
const animating = ref(false)
const chartData = ref(null)
const hint = ref('')
const errorMessage = ref('')

let chart = null
let observer = null
let debounceTimer = null
let animationTimer = null

// 请求序号。自动重绘下会有多个请求同时在飞，
// 只接受最新一次的响应，否则旧响应会覆盖新数据
let requestSeq = 0

/**
 * 上一次渲染的结构指纹（图表类型 + 是否统一刻度）。
 *
 * 这两个变化会让 option 的形状改变（双轴 ↔ 单轴、series 类型切换），
 * 必须用 notMerge 重建，否则旧的那根轴配置会残留下来
 */
let lastRenderKey = ''

// 取某个度量的整套配色。自定义的现场派生，预设的直接取
const paletteOf = (index) => {
  const id = paletteIds.value[index] || 'default'
  if (id === 'custom') {
    return derivePalette(customColors.value[index] || DEFAULT_PRIMARY)
  }
  const preset = PRESET_PALETTES.find((p) => p.id === id)
  return preset ? preset.colors : PRESET_PALETTES[0].colors
}

// 配置区正在编辑的那套。色卡的高亮与取色器的值都读它
const activePaletteId = computed({
  get: () => paletteIds.value[paletteTarget.value] || 'default',
  // 下拉框或取色器把它改成 'custom' 时，颜色本身由 activeCustomColor 落盘
  set: (id) => {
    paletteIds.value[paletteTarget.value] = id
  },
})

const activeCustomColor = computed({
  get: () => customColors.value[paletteTarget.value] || DEFAULT_PRIMARY,
  set: (color) => {
    // 取色器点「清空」会回传 null，这时保持原值，否则色卡会空掉
    if (color) {
      customColors.value[paletteTarget.value] = color
    }
  },
})

// 饼图每个扇区的最终颜色：自定义优先，否则按主题色顺序取
const pieSectorColors = computed(() => {
  const base = paletteOf(0)
  const categories = chartData.value?.categories || []
  return categories.map((name, i) => sectorColors.value[name] || base[i % base.length])
})

// 配色只影响渲染，不影响数据。拼成指纹统一观察，
// 免得 watch 一堆深层对象还得逐个开 deep
const colorKey = computed(() =>
  JSON.stringify([paletteIds.value, customColors.value, sectorColors.value]),
)

// 数值列才能当度量
const numericColumns = computed(() =>
  props.columns.filter((_, i) => props.columnTypes[i] === 'number'),
)

// 饼图放不下第二个系列，count 模式下度量整个不参与，两种都不显示
const showSecondMeasure = computed(
  () => chartType.value !== 'pie' && aggregation.value !== 'count',
)

// 第二个度量的候选：排除已选中的第一个，避免两条系列完全重合
const secondMeasureOptions = computed(() =>
  numericColumns.value.filter((c) => c !== measure.value),
)

// 真正会画出两条系列。只有此时「统一度量单位」与配色选择框才有意义
const hasTwoSeries = computed(() => showSecondMeasure.value && !!measure2.value)

const hasChart = computed(() => chartData.value && chartData.value.categories.length > 0)

// 动画期间不能导出 —— 会截到半张图
const canExport = computed(() => hasChart.value && !animating.value)

watch(canExport, (value) => emit('can-export', value), { immediate: true })

/**
 * 色卡的示意图形。
 *
 * 柱/折线图一个度量只用得到一个颜色，就展示主色本身；
 * 饼图会把整排列都用上，展示成一道渐变才看得出整体调性
 */
const swatchStyle = (preset) => ({
  background:
    chartType.value === 'pie'
      ? `linear-gradient(135deg, ${preset.colors.slice(0, 4).join(', ')})`
      : preset.colors[0],
})

/**
 * 初始化默认选项：第一个文本列当维度，第一个数值列当度量。
 *
 * 没有文本列时退回第一列 —— 数值列也能当维度（会走分桶）
 */
const applyDefaults = () => {
  const firstText = props.columns.find((_, i) => props.columnTypes[i] === 'string')
  dimension.value = firstText || props.columns[0] || ''
  measure.value = numericColumns.value[0] || ''
  measure2.value = ''
}

// 以下各项的顺序不能调换。
// watch 带 immediate，回调在模块求值时就同步执行，里面会走到
// scheduleLoad() → setTimeout(loadChart, ...) —— 这一行要立刻求值
// loadChart 这个引用。任何一个排到 watch 后面，都会落进暂时性死区报
// ReferenceError，而 setup 抛错会让整个应用白屏
let lastRenderedSql = ''

const loadChart = async () => {
  // 自动触发，条件不满足时静默返回，不弹提示打扰用户
  if (!props.executedSql || !dimension.value) {
    return
  }
  // count 不需要度量列，其余情况没有数值列就没法画
  if (aggregation.value !== 'count' && !measure.value) {
    chartData.value = null
    errorMessage.value = '当前结果集里没有数值列，无法作为度量'
    return
  }

  const seq = ++requestSeq
  loading.value = true
  errorMessage.value = ''

  // 组装度量列表。饼图只取第一个 —— 后端也会截，但前端先截一道能让
  // 请求和界面保持一致，不至于传了个用不上的列
  const measures = []
  if (aggregation.value !== 'count' && measure.value) {
    measures.push(measure.value)
    if (chartType.value !== 'pie' && measure2.value) {
      measures.push(measure2.value)
    }
  }

  try {
    const body = await queryChart({
      sql: props.executedSql,
      dimension: dimension.value,
      measures,
      aggregation: aggregation.value,
      topN: chartType.value === 'pie' ? PIE_TOP_N : BAR_TOP_N,
    })

    // 已经有更新的请求发出（用户连改了几个下拉框），丢弃这次的响应。
    // 不做这层判断的话，旧数据会覆盖新数据，界面选的维度和图会对不上
    if (seq !== requestSeq) {
      return
    }

    chartData.value = body
    hint.value = buildHint(body)

    await nextTick()
    renderChart()
  } catch (error) {
    if (seq !== requestSeq) {
      return
    }
    chartData.value = null
    errorMessage.value = error.message
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

const scheduleLoad = () => {
  if (debounceTimer) {
    clearTimeout(debounceTimer)
  }
  debounceTimer = setTimeout(loadChart, DEBOUNCE_MS)
}

// 所有会影响数据本身的输入放在一个 watcher 里统一观察，
// 连改多个下拉框时由 debounce 合并成一次请求
watch(
  () => [
    props.executedSql,
    props.columns,
    dimension.value,
    measure.value,
    measure2.value,
    aggregation.value,
    chartType.value,
  ],
  () => {
    // 新查询来了，旧图先清掉，避免看到上一份 SQL 的图
    if (props.executedSql !== lastRenderedSql) {
      chartData.value = null
      hint.value = ''
      applyDefaults()
      lastRenderedSql = props.executedSql
    }
    scheduleLoad()
  },
  { immediate: true },
)

// 组装提示文案。数据说明几项互斥，饼图的改色提示可以并存
const buildHint = (body) => {
  const parts = []

  if (body.bucketed) {
    parts.push(`${dimension.value} 是数值列，已自动分为 ${body.categories.length} 段（X 轴为区间）`)
  } else if (body.truncated) {
    const aggLabel =
      AGGREGATIONS.find((a) => a.value === aggregation.value)?.label || aggregation.value
    parts.push(
      `共 ${body.totalGroups} 个分组，按${aggLabel}降序仅展示前 ` +
      `${body.categories.length - 1} 个，其余已合并为「其他」`,
    )
  } else if (body.categories.length > LABEL_MAX_CATEGORIES) {
    parts.push(`共 ${body.categories.length} 个分组，柱子上不显示数值，鼠标悬停可查看`)
  }

  // 点扇区改色是个藏起来的功能，不提示没人会去点
  if (chartType.value === 'pie') {
    parts.push('点击任意扇区可单独调整它的颜色')
  }

  return parts.join('；')
}

/**
 * 数值显示格式。
 *
 * 后端返回的是 BigDecimal，JSON 里可能已被解析成 number。
 * 默认保留两位小数；勾上「保留全部小数」后按原值显示
 */
const formatValue = (value) => {
  const num = Number(value)
  if (!Number.isFinite(num)) {
    return value == null ? '' : String(value)
  }
  return keepFullPrecision.value ? String(num) : num.toFixed(DECIMAL_PLACES)
}

/**
 * 入场动画配置。
 *
 * 换图表类型或切刻度模式时播完整动画；只改维度/度量时用较短的形变过渡 ——
 * 同一张图上柱子长高，比整张图重新长出来更自然
 */
const buildAnimation = (structuralChanged) => {
  if (!structuralChanged) {
    return { animationDuration: 350, animationDurationUpdate: 350 }
  }
  if (chartType.value === 'bar') {
    // 一根根往上拔。延迟设上限，否则 50 根柱子要等 1.5 秒
    return {
      animationDuration: 600,
      animationEasing: 'cubicOut',
      animationDelay: (idx) => Math.min(idx, 15) * 25,
    }
  }
  if (chartType.value === 'pie') {
    // 扇区从 12 点方向起，顺时针依次从中心冒出
    return {
      animationType: 'expansion',
      animationDuration: 700,
      animationEasing: 'cubicOut',
      animationDelay: (idx) => idx * 60,
    }
  }
  // 折线：ECharts 原生就是从左侧画出来的，不需要额外配置
  return { animationDuration: 1000, animationEasing: 'cubicOut' }
}

/**
 * 建立尺寸监听。
 *
 * 用 ResizeObserver 而不是监听 window：卡片折叠、滚动条出现等
 * 都会改变容器宽度，窗口事件覆盖不到。每次重绘都重绑一次 ——
 * 容器节点可能因 v-if 分支切换而重建，旧监听会指向已卸载的节点
 */
const setupResizeObserver = () => {
  if (typeof ResizeObserver === 'undefined' || !chartRef.value) {
    return
  }
  if (observer) {
    observer.disconnect()
  }
  observer = new ResizeObserver(() => {
    if (chart) {
      chart.resize()
    }
  })
  observer.observe(chartRef.value)
}

const closeSectorPopup = () => {
  sectorPopup.value = null
  document.removeEventListener('mousedown', handleDocumentMouseDown)
}

/**
 * 点弹窗以外的地方就收起。
 *
 * 挂 mousedown 而不是 click：ECharts 的 click 在原生 click 之后才派发，
 * 此刻这次交互的 mousedown 早就过去了，不会刚开就被自己关掉。
 *
 * 取色面板会被 teleport 到 body 上，所以不能只判断弹窗自身
 */
const handleDocumentMouseDown = (event) => {
  const target = event.target
  if (target && target.closest && target.closest('.sector-popup, .el-color-picker__panel, .el-popper')) {
    return
  }
  closeSectorPopup()
}

// 点中扇区，把取色弹窗浮在点击处
const openSectorPopup = (params) => {
  const native = params.event && params.event.event
  const rect = chartRef.value ? chartRef.value.getBoundingClientRect() : null
  if (!native || !rect) {
    return
  }

  // 以点击处为弹窗中心，再收进画布范围内，免得顶出去
  const centered = native.clientX - rect.left - SECTOR_POPUP_WIDTH / 2
  sectorPopup.value = {
    name: params.name,
    left: Math.round(clamp(centered, 0, Math.max(0, rect.width - SECTOR_POPUP_WIDTH))),
    top: Math.round(clamp(native.clientY - rect.top + 12, 0, Math.max(0, rect.height - 60))),
  }

  // 同一时刻只留一个监听，重复点扇区不会叠加
  document.removeEventListener('mousedown', handleDocumentMouseDown)
  document.addEventListener('mousedown', handleDocumentMouseDown)
}

// 弹窗里取色器绑的值。自定义过就用自定义的，否则回落到主题色 ——
// 这样一打开弹窗看到的就是扇区当前的颜色，而不是空
const sectorColor = computed({
  get: () => {
    if (!sectorPopup.value) {
      return DEFAULT_PRIMARY
    }
    const { name } = sectorPopup.value
    if (sectorColors.value[name]) {
      return sectorColors.value[name]
    }
    const index = (chartData.value?.categories || []).indexOf(name)
    const base = paletteOf(0)
    return base[index >= 0 ? index % base.length : 0]
  },
  set: (color) => {
    if (!sectorPopup.value) {
      return
    }
    const { name } = sectorPopup.value
    const next = { ...sectorColors.value }
    if (color) {
      next[name] = color
    } else {
      // 取色器里点「清空」等于取消这个扇区的自定义
      delete next[name]
    }
    sectorColors.value = next
  },
})

const resetSectorColor = () => {
  if (!sectorPopup.value) {
    return
  }
  const next = { ...sectorColors.value }
  delete next[sectorPopup.value.name]
  sectorColors.value = next
}

const renderChart = () => {
  if (!chartRef.value || !chartData.value) {
    return
  }
  if (!chart) {
    chart = echarts.init(chartRef.value)
    // 动画结束后解除导出按钮的禁用
    chart.on('finished', () => {
      animating.value = false
    })
    // 点扇区弹取色框。只注册一次 —— 图表实例复用，
    // 重绘不需要重新挂，回调里读的都是当前值
    chart.on('click', (params) => {
      if (chartType.value !== 'pie' || params.componentType !== 'series') {
        closeSectorPopup()
        return
      }
      openSectorPopup(params)
    })
    // 容器在 columns 非空时才渲染，组件挂载那会儿它还不存在，
    // 所以监听只能等首次真正绘制时再绑
    setupResizeObserver()
  }

  const { categories, series } = chartData.value
  // 后端返回的是 BigDecimal 的字符串形式，转成数字给 ECharts
  const nums = series.map((s) => s.values.map((v) => Number(v)))

  // 两个系列各占一根轴。统一刻度时共用一个，柱高才可直接比较
  const dualAxis = series.length === 2 && !unifyScale.value

  const renderKey = `${chartType.value}|${unifyScale.value}`
  const structuralChanged = renderKey !== lastRenderKey
  const animation = buildAnimation(structuralChanged)

  // 类别太多时标签会重叠成一团，直接不显示，靠 tooltip
  const showLabels = categories.length <= LABEL_MAX_CATEGORIES

  // 数据标签：灰色小字，标在柱子顶部 / 折线点上方
  const dataLabel = {
    show: showLabels,
    color: MUTED_COLOR,
    fontSize: 11,
    formatter: (p) => formatValue(p.value),
  }

  let option

  if (chartType.value === 'pie') {
    option = {
      ...animation,
      tooltip: {
        trigger: 'item',
        formatter: (p) => `${p.name}: ${formatValue(p.value)} (${p.percent}%)`,
      },
      legend: { type: 'scroll', bottom: 0 },
      series: [
        {
          type: 'pie',
          radius: ['35%', '65%'],
          center: ['50%', '45%'],
          // 第一个扇区从 12 点方向开始，配合 animationDelay 形成顺时针展开
          startAngle: 90,
          avoidLabelOverlap: true,
          // 提示这玩意儿能点
          cursor: 'pointer',
          // 扇区本来就少（后端已合并到 10 个以内），标签一律显示
          label: { formatter: '{b}\n{d}%', color: MUTED_COLOR, fontSize: 11 },
          // 逐扇区上色，所以不用顶层的 color 数组 —— 那个是按序取，
          // 没法表达「某一个扇区被单独改过」
          data: categories.map((name, i) => ({
            name,
            value: nums[0][i],
            itemStyle: { color: pieSectorColors.value[i] },
          })),
        },
      ],
    }
  } else if (chartType.value === 'line') {
    option = {
      ...animation,
      tooltip: { trigger: 'axis', formatter: axisTooltipFormatter },
      legend: { bottom: 0 },
      grid: { left: 20, right: 30, top: 48, bottom: 48, containLabel: true },
      // 一个度量一个色。取各套配色的主色 ——
      // 一条折线只用得到一个颜色，整排列铺上去也是浪费
      color: series.map((_, i) => paletteOf(i)[0]),
      xAxis: {
        type: 'category',
        data: categories,
        // 类别多时标签会重叠，交给 ECharts 自动抽稀
        axisLabel: { interval: 'auto', rotate: categories.length > 12 ? 30 : 0 },
        ...AXIS_STYLE,
      },
      yAxis: buildYAxis(series),
      series: series.map((s, i) => ({
        name: s.name,
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data: nums[i],
        // 类别多时显示全部数据点会糊成一片
        showSymbol: categories.length <= 30,
        // 双轴时每条线绑到自己的轴
        yAxisIndex: dualAxis ? i : 0,
        label: { ...dataLabel, position: 'top' },
      })),
    }
  } else {
    option = {
      ...animation,
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: axisTooltipFormatter },
      legend: { bottom: 0 },
      grid: { left: 20, right: 30, top: 48, bottom: 48, containLabel: true },
      // 一个度量一个色，同度量内所有柱子同色 ——
      // 柱高已经编码了数值，颜色再编码一遍是冗余的
      color: series.map((_, i) => paletteOf(i)[0]),
      xAxis: {
        type: 'category',
        data: categories,
        axisLabel: { interval: 0, rotate: categories.length > 12 ? 30 : 0 },
        ...AXIS_STYLE,
      },
      yAxis: buildYAxis(series),
      series: series.map((s, i) => ({
        name: s.name,
        type: 'bar',
        data: nums[i],
        barMaxWidth: 40,
        yAxisIndex: dualAxis ? i : 0,
        label: { ...dataLabel, position: 'top' },
      })),
    }
  }

  // 动画期间禁用导出，避免截到半张图
  animating.value = true
  if (animationTimer) {
    clearTimeout(animationTimer)
  }
  // 兜底：个别情况下 finished 不触发，按钮不能一直禁用
  animationTimer = setTimeout(() => {
    animating.value = false
  }, ANIMATION_FALLBACK_MS)

  // notMerge 只在结构变化时用：
  // 换类型或切单双轴要清掉上一种形态的残留配置，只换数据时合并才能走形变过渡。
  // 配色不在 renderKey 里 —— 换色不改变 option 形状，走 merge 还能让颜色渐变过去
  chart.setOption(option, structuralChanged)
  lastRenderKey = renderKey

  chart.resize()
}

// 直角坐标系的 tooltip：逐系列列出，数值走同一套小数格式
const axisTooltipFormatter = (params) => {
  if (!Array.isArray(params) || params.length === 0) {
    return ''
  }
  const lines = [params[0].axisValue]
  for (const p of params) {
    lines.push(`${p.marker}${p.seriesName}: ${formatValue(p.value)}`)
  }
  return lines.join('<br/>')
}

/**
 * 构造 Y 轴配置。
 *
 * 两个度量默认共用一个轴 —— 量纲不同时各自铺满画布会让柱高失去可比性
 * （点赞数可能画得比浏览量还高）。关掉「统一度量单位」才切成双轴
 */
const buildYAxis = (series) => {
  /**
   * align 决定轴名以锚点为中心往哪边铺。
   *
   * 锚点在轴顶端，'left' 会让整串字向右越过轴线压进绘图区，
   * 'right' 又整个退到轴外、离得远。取两者的中点 'center' ——
   * 文字盒以轴顶为心左右各半，落在两版中间的位置
   */
  const axis = (name, align) => ({
    type: 'value',
    // 轴名用灰色小字，与数据标签同一套配色
    name,
    nameTextStyle: { color: MUTED_COLOR, fontSize: 11, align },
    // 名字放到轴顶端，不与刻度数字挤在一起
    nameLocation: 'end',
    nameGap: 12,
    ...AXIS_STYLE,
  })

  if (series.length === 2) {
    if (unifyScale.value) {
      // 共用一根轴，轴名把两个度量都写上，否则不知道它代表谁
      return [axis(`${series[0].name} / ${series[1].name}`, 'center')]
    }
    return [axis(series[0].name, 'center'), axis(series[1].name, 'left')]
  }
  return [axis(series.length === 1 ? series[0].name : '', 'center')]
}

const exportPng = () => {
  if (!chart) {
    return
  }
  // pixelRatio 2 保证高分屏下不糊；补白色背景，否则 PNG 是透明的
  const url = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
  const link = document.createElement('a')
  link.href = url
  const stamp = new Date().toISOString().slice(0, 19).replace(/[:T]/g, '')
  link.download = `chart_${stamp}.png`
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

// 导出按钮在父组件的卡片头里，通过 ref 调过来
defineExpose({ exportPng })

// 从柱状图切到饼图时第二个度量不再渲染，但 ref 里还留着旧值，
// 会让 loadChart 继续把它发出去。这里主动清掉。
// 放在主 watch 之后是安全的 —— 它没有 immediate，不会在求值期触发
watch(showSecondMeasure, (visible) => {
  if (!visible) {
    measure2.value = ''
  }
})

// 度量2 被清掉后配色选择框要退回度量1，
// 否则它会指着一个不存在的系列，改色改了个寂寞
watch(hasTwoSeries, (value) => {
  if (!value) {
    paletteTarget.value = 0
  }
})

// 切到别的图型、或换了一份数据之后，弹窗里那个扇区可能已经不存在了
watch([chartType, chartData], () => {
  if (chartType.value !== 'pie') {
    closeSectorPopup()
    return
  }
  const names = chartData.value?.categories || []
  if (sectorPopup.value && !names.includes(sectorPopup.value.name)) {
    closeSectorPopup()
  }
})

/**
 * 换整套配色时清掉逐扇区的自定义。
 *
 * 不清的话，选完主题饼图还是花花的，看着像没生效。
 * 自定义是一次点击的事，重做成本低。
 * 只看下标 0 —— 饼图用的是度量1 那套，度量2 的配色动不了饼图
 */
watch(
  () => [paletteIds.value[0], customColors.value[0]],
  () => {
    sectorColors.value = {}
  },
)

// 这几个开关只影响渲染，不影响数据，不必重新请求 ——
// 直接重绘，避免白跑一趟后端
watch([unifyScale, keepFullPrecision, colorKey], () => {
  if (chartData.value) {
    renderChart()
  }
})

// 尺寸监听的绑定挪到了 renderChart 里 —— 容器要等 columns 非空才渲染，
// 挂载时 chartRef 还是 null

onBeforeUnmount(() => {
  if (debounceTimer) {
    clearTimeout(debounceTimer)
  }
  if (animationTimer) {
    clearTimeout(animationTimer)
  }
  if (observer) {
    observer.disconnect()
    observer = null
  }
  document.removeEventListener('mousedown', handleDocumentMouseDown)
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style scoped>
.chart-config {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 18px;
  margin-bottom: 12px;
}

/* label 与它的下拉框贴紧成一组，组与组之间留大间距 ——
   否则「度量1」看起来像是属于左边那一组的 */
.cfg-group {
  display: flex;
  align-items: center;
  gap: 4px;
}

.cfg-label {
  font-size: 13px;
  color: #606266;
  white-space: nowrap;
}

.cfg-item {
  width: 150px;
}

.cfg-narrow {
  width: 110px;
}

.cfg-check {
  margin-right: 0;
}

.swatches {
  display: flex;
  align-items: center;
  gap: 6px;
}

/* 色卡是 button，App.vue 里有一条全局 button 样式会加内边距和外边距，
   这里显式清掉。scoped 选择器带属性选择器，优先级够压过那条 */
.swatch {
  width: 24px;
  height: 24px;
  margin-top: 0;
  padding: 0;
  border: 2px solid transparent;
  border-radius: 4px;
  cursor: pointer;
  /* 浅色主题在白色卡片上会糊掉，用内阴影勾一道边 */
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.08);
}

.swatch.is-active {
  border-color: #409eff;
}

/* 取色器触发按钮自带边框，套一层只为了给它加选中态 */
.swatch-custom {
  display: inline-flex;
  align-items: center;
  padding: 1px;
  border: 2px solid transparent;
  border-radius: 4px;
}

.swatch-custom.is-active {
  border-color: #409eff;
}

.chart-alert {
  margin-bottom: 12px;
}

/* 容器保持固定高度，el-empty 用绝对定位盖在上面 ——
   echarts 在 display:none 的容器上量不到宽高，初始化会得到 0 尺寸画布 */
.chart-wrap {
  position: relative;
}

.chart-box {
  width: 100%;
  height: 420px;
}

/* 扇形取色弹窗。绝对定位在画布之上，点扇区时出现 */
.sector-popup {
  position: absolute;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  background: #ffffff;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.12);
}

.popup-title {
  max-width: 90px;
  font-size: 12px;
  color: #606266;
  /* 类别名可能很长，截断而不是把弹窗撑宽 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 遮挡住下面尚未绘制的画布 */
  background: #ffffff;
}

.panel-hint {
  padding: 20px 0;
}
</style>
