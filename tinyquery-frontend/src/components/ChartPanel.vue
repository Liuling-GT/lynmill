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

        <!-- 两个度量时才谈得上「统一」，单度量下开关没有意义 -->
        <el-checkbox v-if="hasTwoSeries" v-model="unifyScale" size="small" class="cfg-check">
          统一度量单位
        </el-checkbox>

        <el-checkbox v-model="keepFullPrecision" size="small" class="cfg-check">
          保留全部小数
        </el-checkbox>
      </div>

      <!-- 提示：分桶说明、其他合并、标签隐藏 -->
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

// ECharts 默认调色板，与 Element Plus 的配色比较协调
const PALETTE = [
  '#5470c6', '#91cc75', '#fac858', '#ee6666', '#73c0de',
  '#3ba272', '#fc8452', '#9a60b4', '#ea7ccc',
]

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

// 真正会画出两条系列。只有此时「统一度量单位」才有意义
const hasTwoSeries = computed(() => showSecondMeasure.value && !!measure2.value)

const hasChart = computed(() => chartData.value && chartData.value.categories.length > 0)

// 动画期间不能导出 —— 会截到半张图
const canExport = computed(() => hasChart.value && !animating.value)

watch(canExport, (value) => emit('can-export', value), { immediate: true })

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

// 组装提示文案。三种情况互斥，优先级：分桶 > 其他合并 > 标签隐藏
const buildHint = (body) => {
  if (body.bucketed) {
    return `${dimension.value} 是数值列，已自动分为 ${body.categories.length} 段（X 轴为区间）`
  }
  if (body.truncated) {
    const aggLabel =
      AGGREGATIONS.find((a) => a.value === aggregation.value)?.label || aggregation.value
    return (
      `共 ${body.totalGroups} 个分组，按${aggLabel}降序仅展示前 ` +
      `${body.categories.length - 1} 个，其余已合并为「其他」`
    )
  }
  if (body.categories.length > LABEL_MAX_CATEGORIES) {
    return `共 ${body.categories.length} 个分组，柱子上不显示数值，鼠标悬停可查看`
  }
  return ''
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
      color: PALETTE,
      series: [
        {
          type: 'pie',
          radius: ['35%', '65%'],
          center: ['50%', '45%'],
          // 第一个扇区从 12 点方向开始，配合 animationDelay 形成顺时针展开
          startAngle: 90,
          avoidLabelOverlap: true,
          // 扇区本来就少（后端已合并到 10 个以内），标签一律显示
          label: { formatter: '{b}\n{d}%', color: MUTED_COLOR, fontSize: 11 },
          data: categories.map((name, i) => ({ name, value: nums[0][i] })),
        },
      ],
    }
  } else if (chartType.value === 'line') {
    option = {
      ...animation,
      tooltip: { trigger: 'axis', formatter: axisTooltipFormatter },
      legend: { bottom: 0 },
      grid: { left: 20, right: 30, top: 48, bottom: 48, containLabel: true },
      color: PALETTE,
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
      color: PALETTE,
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
  // 换类型或切单双轴要清掉上一种形态的残留配置，只换数据时合并才能走形变过渡
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

// 这两个开关只影响渲染，不影响数据，不必重新请求 ——
// 直接重绘，避免白跑一趟后端
watch([unifyScale, keepFullPrecision], () => {
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
