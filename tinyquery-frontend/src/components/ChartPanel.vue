<template>
  <div class="chart-panel">
    <!-- 还没执行过查询：没有列信息，无法配置图表 -->
    <div v-if="columns.length === 0" class="panel-hint">
      <el-empty :description="t('chart.emptyHint')" :image-size="80" />
    </div>

    <template v-else>
      <!-- 配置区。任何一项变化都会自动重绘，不需要额外的按钮 -->
      <div class="chart-config">
        <div class="cfg-group">
          <span class="cfg-label">{{ t('chart.dimension') }}</span>
          <el-select v-model="dimension" size="small" class="cfg-item">
            <el-option v-for="c in columns" :key="c" :label="c" :value="c" />
          </el-select>
        </div>

        <div class="cfg-group">
          <span class="cfg-label">{{ t('chart.measure1') }}</span>
          <el-select
            v-model="measure"
            size="small"
            class="cfg-item"
            :placeholder="t('chart.noNumericColumn')"
            :disabled="aggregation === 'count'"
          >
            <el-option v-for="c in numericColumns" :key="c" :label="c" :value="c" />
          </el-select>
        </div>

        <!-- 第二个度量：饼图表示整体占比，放不下两个系列；count 模式度量整个不参与 -->
        <div v-if="showSecondMeasure" class="cfg-group">
          <span class="cfg-label">{{ t('chart.measure2') }}</span>
          <el-select
            v-model="measure2"
            size="small"
            class="cfg-item"
            :placeholder="t('common.none')"
            clearable
          >
            <el-option v-for="c in secondMeasureOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </div>

        <div class="cfg-group">
          <span class="cfg-label">{{ t('chart.aggregate') }}</span>
          <el-select v-model="aggregation" size="small" class="cfg-item cfg-narrow">
            <el-option v-for="a in aggregations" :key="a.value" :label="a.label" :value="a.value" />
          </el-select>
        </div>

        <div class="cfg-group">
          <span class="cfg-label">{{ t('chart.type') }}</span>
          <el-select v-model="chartType" size="small" class="cfg-item cfg-narrow">
            <el-option v-for="c in chartTypes" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </div>

        <!-- 配色：一个度量一套。两个度量时先选改哪个，再点色卡 -->
        <div class="cfg-group">
          <span class="cfg-label">{{ t('chart.palette') }}</span>
          <el-select
            v-if="hasTwoSeries"
            v-model="paletteTarget"
            size="small"
            class="cfg-item cfg-narrow"
          >
            <el-option
              v-for="(_, i) in [0, 1]"
              :key="i"
              :label="t('chart.measureN', { n: i + 1 })"
              :value="i"
            />
          </el-select>
          <div class="swatches">
            <button
              v-for="p in presetPalettes"
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
          <!-- 换整套配色会清掉逐扇区的手调颜色（见下方 watch，逻辑有意保留）。
               只在真的调过扇区颜色时提示 —— 没调过就没有会被重置的东西 -->
          <el-tooltip
            v-if="hasManualSectorColors"
            :content="t('chart.paletteResetDetail')"
            placement="top"
          >
            <span class="cfg-note">{{ t('chart.paletteResetHint') }}</span>
          </el-tooltip>
        </div>

        <!-- 突出显示：只在柱状图下有意义 —— 折线没有「一根根」的形态，
             饼图已经有逐扇区取色了 -->
        <el-checkbox v-if="chartType === 'bar'" v-model="highlightMode" size="small" class="cfg-check">
          {{ t('chart.highlight') }}
        </el-checkbox>

        <!-- 点亮了好几根时，一根根点回去太麻烦 -->
        <el-button
          v-if="highlightMode && highlighted.length > 0"
          link
          size="small"
          @click="clearHighlight"
        >
          {{ t('chart.clearHighlight', { count: highlighted.length }) }}
        </el-button>

        <!-- 两个度量时才谈得上「统一」，单度量下开关没有意义 -->
        <el-checkbox v-if="hasTwoSeries" v-model="unifyScale" size="small" class="cfg-check">
          {{ t('chart.unifyScale') }}
        </el-checkbox>

        <el-checkbox v-model="keepFullPrecision" size="small" class="cfg-check">
          {{ t('chart.keepFullPrecision') }}
        </el-checkbox>
      </div>

      <!-- 提示：分桶说明、其他合并、标签隐藏、饼图点击改色、柱子点亮 -->
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
          <el-button link size="small" @click="resetSectorColor">{{ t('common.reset') }}</el-button>
        </div>

        <div v-if="!hasChart && !errorMessage" class="chart-placeholder">
          <el-empty :description="loading ? t('chart.loading') : t('common.empty')" :image-size="60" />
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { queryChart, stripErrorPrefix, downloadChartPdf } from '../api/query'

const { t } = useI18n()

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
  // 当前数据源表名，导出 PDF 时用作报表标题与文件名
  tableName: {
    type: String,
    default: '',
  },
})

// 导出按钮在父组件的卡片头里，能否导出由这里算好再传出去
const emit = defineEmits(['can-export'])

// 正在导出 PDF。要访问后端取数并渲染，比 PNG 慢，需要给按钮一个忙碌态
const exporting = ref(false)

// 聚合方式。value 与后端 normalizeAggregation 的白名单一致。
// label 存词条键 —— 切换语言时要跟着变，所以这里不能是常量数组
const AGGREGATION_VALUES = [
  { value: 'sum', labelKey: 'chart.aggSum' },
  { value: 'avg', labelKey: 'chart.aggAvg' },
  { value: 'count', labelKey: 'chart.aggCount' },
  { value: 'max', labelKey: 'chart.aggMax' },
  { value: 'min', labelKey: 'chart.aggMin' },
]

const aggregations = computed(() =>
  AGGREGATION_VALUES.map((item) => ({ value: item.value, label: t(item.labelKey) })))

// 聚合方式的展示名，供导出时拼标题用。
// 单独建一份是因为上面那个 computed 是数组，按 value 取名字得先 find 一次
const AGGREGATION_LABELS = computed(() =>
  Object.fromEntries(AGGREGATION_VALUES.map((item) => [item.value, t(item.labelKey)])))

const CHART_TYPE_VALUES = [
  { value: 'bar', labelKey: 'chart.typeBar' },
  { value: 'line', labelKey: 'chart.typeLine' },
  { value: 'pie', labelKey: 'chart.typePie' },
]

const chartTypes = computed(() =>
  CHART_TYPE_VALUES.map((item) => ({ value: item.value, label: t(item.labelKey) })))

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
 * 突出显示模式下，未被点亮的柱子用的灰。
 *
 * 不用纯灰 #999 —— 那和坐标轴、数据标签的灰会糊在一起。
 * 比文字灰（{@link MUTED_COLOR} #909399）明显亮一档，
 * 一眼能看出「这是被压下去的柱子」而不是「没画出来的柱子」。
 *
 * 【2026-10-12 去掉紫调】原来是 #c9c5d2（S≈13% 的紫灰），
 * 理由写的是「与整体主题同源」。只有一套紫色主题时成立，
 * 四套主题下不成立 —— 绿色主题的页面上压着一排紫灰柱子。
 * 改成无彩色 #c6c6cd，四套主题下都只是「比文字灰亮一点的灰」。
 * 这是图表画布内的颜色，ECharts 读不到 CSS 变量，只能写死；
 * 写死的代价就是不能带任何色相
 */
const MUTED_BAR_COLOR = '#c6c6cd'

/**
 * 取色器的快捷色板。取色面板底部的预设格，点一下就选中，不用手输色值。
 *
 * 排布刻意不是按色相，而是先色相扫一遍、末尾按明度收拢：
 *
 *   前四色（暖）  16° 橙红 → 51° 金黄 ×2 → 75° 青柠
 *   中两色（冷）  190° 天青 → 211° 雾蓝
 *   末四色（暗）  261° 丁香 → 272° 深紫 → 240° 石墨 → 无彩色 炭黑
 *
 * 最后四个从浅到深（73% → 46% → 32% → 13%）一路降下来。
 * 原来石墨夹在雾蓝与丁香之间（77% → 32% → 73% → 46% → 13%），
 * 明度来回跳两次，一排格子看着是乱的 —— 挪到倒数第二位之后就顺了。
 *
 * 两个已知瑕疵，都是刻意的：
 *   - #f9d308 与 #ffd700 色相只差 0.5°，排完正好挨着，几乎分不出
 *   - #222222 是无彩色（饱和度 0，没有色相），没法参与排序，收在最后
 */
const PRESET_COLORS = [
  '#ff4500', '#f9d308', '#ffd700', '#bfff00', '#00d4ff',
  '#89c2ff', '#b19cd9', '#7b2cbf', '#49495a', '#222222',
]

// 色相环上的黄金角。相邻两色相隔 137.5°，
// 任意前 N 个色的分布都足够散 —— 等分色相的话，
// 前 3~4 色会挤在一个色区里，两条系列看着像同色
const GOLDEN_ANGLE = 137.508

const clamp = (value, min, max) => Math.min(max, Math.max(min, value))

/**
 * 0~1 的 RGB 转 [h, s, l]：h 为 0~360，s / l 为 0~100。
 *
 * 只写这一个方向就够了 —— 派生出的颜色直接用 hsl() 字符串交给 ECharts，
 * 不需要再转回去
 */
const rgbToHsl = (r, g, b) => {
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
 * 把颜色字符串解析成 [h, s, l]。
 *
 * 取色器默认回传十六进制，但这里同时认 rgb / hsl 两种写法 ——
 * 派生出的颜色本身就是 hsl() 字符串，之后可能再被喂回来；
 * 只认十六进制的话，substring 出来的是 "rg" / "hs"，
 * parseInt 得到 NaN，整排颜色全废 —— 而且不报错，只是图变成一片黑
 */
const colorToHsl = (color) => {
  const text = String(color || '').trim()

  // rgb(r, g, b) / rgba(r, g, b, a)：alpha 在这里没有意义 ——
  // 派生出的整排颜色都是不透明的，直接忽略
  const rgbMatch = text.match(/^rgba?\(\s*([\d.]+)[\s,]+([\d.]+)[\s,]+([\d.]+)/i)
  if (rgbMatch) {
    return rgbToHsl(
      Number(rgbMatch[1]) / 255,
      Number(rgbMatch[2]) / 255,
      Number(rgbMatch[3]) / 255,
    )
  }

  // hsl(h, s%, l%)
  const hslMatch = text.match(/^hsla?\(\s*([\d.]+)[\s,]+([\d.]+)%[\s,]+([\d.]+)%/i)
  if (hslMatch) {
    return [Number(hslMatch[1]), Number(hslMatch[2]), Number(hslMatch[3])]
  }

  // #rgb / #rrggbb
  const raw = text.replace('#', '')
  const full = raw.length === 3 ? raw.split('').map((c) => c + c).join('') : raw
  return rgbToHsl(
    parseInt(full.slice(0, 2), 16) / 255,
    parseInt(full.slice(2, 4), 16) / 255,
    parseInt(full.slice(4, 6), 16) / 255,
  )
}

/**
 * 由一个主色派生出整套配色。
 *
 * 主色原样保留在第一位 —— 用户拿品牌色取色，第一根柱子就该是那个色，
 * 被算法改浅改深会显得没生效。
 *
 * 明度卡在 35%~70%：太暗会和深色文字打架，太亮会糊在 #ebeef5 的网格线上。
 * 饱和度下限 35% 是给「用户随手挑的色」兜底的 —— 代价是主色本身很灰时
 * （如 #49495a 只有 10%）派生出的第二个色会明显跳艳
 */
const derivePalette = (primary) => {
  const [hue, saturation, lightness] = colorToHsl(primary)

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

/**
 * 六套预设，取自 {@link PRESET_COLORS} 的后六个 —— 两处是同一批颜色，
 * 改快捷色板时这里跟着走，不必两边各维护一份。
 *
 * 顺序按明度从浅到深：天青 → 雾蓝 → 丁香 → 深紫 → 石墨 → 炭黑。
 * 色卡那一排看过去就是一道由浅入深的渐变，好找。
 *
 * 只存主色，整套 10 色由 derivePalette 现场派生：手排 60 个色值既难维护，
 * 改一个主色还得重排其余九个。
 *
 * 放在 derivePalette 之后是必需的 —— 下面这行 map 在模块求值时就执行，
 * 排到前面会落进暂时性死区
 *
 * id 与 labelKey 的后缀刻意保持一致（sky ↔ palette.sky）。原先 id 与词条名
 * 是错位的（id 'azure' 配 'palette.sky'、id 'mist' 配 'palette.azure'），
 * 读代码时要在两个文件之间来回对，很容易改错一边 —— 顺手理顺
 */
const PRESET_MAIN_COLORS = [
  { id: 'sky', labelKey: 'palette.sky', color: '#00d4ff' },
  { id: 'azure', labelKey: 'palette.azure', color: '#89c2ff' },
  { id: 'clove', labelKey: 'palette.clove', color: '#b19cd9' },
  { id: 'royal', labelKey: 'palette.royal', color: '#7b2cbf' },
  { id: 'graphite', labelKey: 'palette.graphite', color: '#49495a' },
  { id: 'ink', labelKey: 'palette.ink', color: '#222222' },
]

// label 在模板里取，这里只带出 id / color 与词条键 ——
// 色卡名要跟着语言变，做成常量会在切换后停在上一种语言
const presetPalettes = computed(() =>
  PRESET_MAIN_COLORS.map((preset) => ({
    id: preset.id,
    label: t(preset.labelKey),
    color: preset.color,
    colors: derivePalette(preset.color),
  })))

/**
 * 柱子样式。
 *
 * 纯色填充，不做渐变也不做圆角。
 *
 * 试过的两条路都退回来了，记下来免得再走一遍：
 *   - 纵向渐变：柱高不一，渐变被各自的柱高拉伸，矮柱子上几乎被压没，
 *     整排看过去深浅不齐，像渲染出错
 *   - 横向渐变 + 圆角：柱宽只有二十几像素，渐变能提供的视觉信息太少，
 *     圆角在这个宽度下也看不出圆，只是把柱顶削掉两个角
 *
 * 唯一的装饰是描边，且必须纵向淡出 —— ECharts 的柱子描边四边都画、
 * 没有单边配置，纯色描边会在底边留一道白线压在 X 轴上，看着像柱子悬空
 */
const barStyle = (base) => ({
  color: base,
  borderColor: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
    { offset: 0, color: 'rgba(255, 255, 255, 0.7)' },
    { offset: 1, color: 'rgba(255, 255, 255, 0)' },
  ]),
  borderWidth: 1,
})

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
 * 第一个取色相序里的头一张（金黄），第二个走自定义 ——
 * 两套预设的主色如果撞在一起，两个度量会变成同一个颜色
 */
const paletteIds = ref([PRESET_MAIN_COLORS[0].id, 'custom'])
const customColors = ref([DEFAULT_PRIMARY, DEFAULT_SECONDARY])

// 配置区的色卡在改哪个度量。只有两个系列时才需要选
const paletteTarget = ref(0)

// 饼图逐扇区的自定义颜色，键是类别名。
// 用名字而不是下标 —— 换维度后下标全变了，名字还是那个名字
const sectorColors = ref({})

// 扇形取色弹窗。null 表示没开；开了就记着改的是哪个扇区、浮在哪
const sectorPopup = ref(null)

/**
 * 突出显示模式：柱子默认全灰，点中的才恢复彩色。
 *
 * 用途是把注意力压到少数几根柱子上 —— 默认全彩时每根都在喊，
 * 想强调某几个反而没有着力点。
 */
const highlightMode = ref(false)

// 被点亮的类别名。同样用名字而不是下标 —— 换维度后下标全变，名字还是那个名字。
// 用数组而不是 Set：ref 包 Set 时增删不会触发更新，得整个换掉才行，反而更绕
const highlighted = ref([])

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
 * 上一次渲染的结构指纹（图表类型 + 是否统一刻度 + 是否突出显示）。
 *
 * 这三个变化会让 option 的形状改变（双轴 ↔ 单轴、series 类型切换、
 * 柱子从「整条系列一个样式」变成「逐根判定样式」），
 * 必须用 notMerge 重建，否则旧的残留配置会留下来。
 *
 * 逐根点亮的名单不在里面 —— 那个每次只改一根，走 merge 就行，
 * 否则每点一下就整张图重播动画，很吵
 */
let lastRenderKey = ''

// 取某个度量的整套配色。自定义的现场派生，预设的直接取
const paletteOf = (index) => {
  const fallback = derivePalette(PRESET_MAIN_COLORS[0].color)
  const id = paletteIds.value[index] || PRESET_MAIN_COLORS[0].id
  if (id === 'custom') {
    return derivePalette(customColors.value[index] || DEFAULT_PRIMARY)
  }
  const preset = PRESET_MAIN_COLORS.find((p) => p.id === id)
  return preset ? derivePalette(preset.color) : fallback
}

// 配置区正在编辑的那套。色卡的高亮与取色器的值都读它
const activePaletteId = computed({
  get: () => paletteIds.value[paletteTarget.value] || PRESET_MAIN_COLORS[0].id,
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

/*
 * 配色指纹。配色只影响渲染、不影响数据，拼成一串字符串统一观察，
 * 免得 watch 一堆深层对象还得逐个开 deep。
 *
 * ⚠️ 它把三份配色状态揉进了一个值，而这三个来源分别被下面三个 watch 盯着。
 * 改一次配色时，「谁触发了」会变得不直观：
 *
 *     paletteIds / customColors 变化
 *        ├─► watch A  重置 sectorColors
 *        ├─► watch B  重绘（条件：highlightMode 为真）
 *        └─► 本 computed 重算
 *               │
 *               └─► watch C  重绘（条件：chartData 有值）
 *
 * 而 watch A 重置 sectorColors 之后，本 computed 会再算一次，
 * 于是 watch C 可能第二次触发。所以「点一次配色跑 2~3 次 renderChart」
 * 是这个结构的必然结果，不是谁写错了。
 *
 * 代价很小（本地应用、用户主动操作、ECharts setOption 是毫秒级），
 * 所以这里选择保留现状、写清关系，不做合并 —— 三个 watch 的
 * 意图本来就不同，硬合并反而会让以后想单独调一个时无从下手。
 *
 * 改动前请先读下面三个 watch 各自的注释。
 */
const colorKey = computed(() =>
  JSON.stringify([paletteIds.value, customColors.value, sectorColors.value]),
)

// 是否手动调过扇区颜色。只有 true 时才提示"切换配色会重置" ——
// 没调过就没有会被重置的东西，常驻提示只会变成噪音
const hasManualSectorColors = computed(() => Object.keys(sectorColors.value).length > 0)

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

// 点柱子：在突出显示模式下切换它的点亮状态
const toggleHighlight = (name) => {
  highlighted.value = highlighted.value.includes(name)
    ? highlighted.value.filter((item) => item !== name)
    : [...highlighted.value, name]
}

const clearHighlight = () => {
  highlighted.value = []
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
    errorMessage.value = t('chart.noNumericHint')
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
    // 与查询页一致，统一以 "Error: " 开头（api 层已剥掉后端的 "error:" 前缀）
    errorMessage.value = 'Error: ' + stripErrorPrefix(error.message)
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

// 组装提示文案。数据说明几项互斥，饼图改色与柱子点亮的提示可以并存
const buildHint = (body) => {
  const parts = []

  if (body.bucketed) {
    parts.push(t('chart.bucketHint', {
      dimension: dimension.value,
      count: body.categories.length,
    }))
  } else if (body.truncated) {
    const aggLabel =
      aggregations.value.find((a) => a.value === aggregation.value)?.label || aggregation.value
    parts.push(t('chart.truncatedHint', {
      total: body.totalGroups,
      agg: aggLabel,
      shown: body.categories.length - 1,
    }))
  } else if (body.categories.length > LABEL_MAX_CATEGORIES) {
    parts.push(t('chart.densityHint', { count: body.categories.length }))
  }

  // 这两个都是藏起来的功能，不提示没人会去点
  if (chartType.value === 'pie') {
    parts.push(t('chart.pieHint'))
  } else if (chartType.value === 'bar' && highlightMode.value) {
    parts.push(t('chart.barHint'))
  }

  // 中文用全角分号，英文用半角分号加空格 —— 标点各按各的规范
  return parts.join(t('chart.hintSeparator'))
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
 * 组装柱状图的数据。
 *
 * 非突出模式直接返回数值数组 —— 整条系列一个样式，交给 series.itemStyle。
 * 突出模式返回逐项带样式的对象数组：点亮的上该系列的主色，其余压成灰。
 *
 * 两个系列各有各的主色，所以点亮后两条系列的颜色仍然分得开
 */
const barData = (categories, values, seriesIndex) => {
  if (!highlightMode.value) {
    return values
  }

  const litColor = paletteOf(seriesIndex)[0]
  return values.map((value, i) => ({
    value,
    itemStyle: highlighted.value.includes(categories[i])
      ? barStyle(litColor)
      : barStyle(MUTED_BAR_COLOR),
  }))
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

/**
 * 彻底销毁图表实例与尺寸监听。
 *
 * 必须在「容器被 v-if 换掉」和「组件卸载」两条路径上都调用。
 * 只调其中一条会留下悬空引用：chart 指着已经移出 DOM 的旧容器，
 * 而 renderChart 里的 if (!chart) 是首次初始化分支 —— 不清掉的话
 * 新容器永远不会 echarts.init，图永久空白、resize 也失效
 */
const disposeChart = () => {
  if (observer) {
    observer.disconnect()
    observer = null
  }
  if (chart) {
    chart.dispose()
    chart = null
  }
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
    /*
     * 画布上的点击统一在这里分派。只注册一次 —— 图表实例复用，
     * 重绘不需要重新挂，回调里读的都是当前值
     */
    chart.on('click', (params) => {
      if (params.componentType !== 'series') {
        closeSectorPopup()
        return
      }
      if (chartType.value === 'pie') {
        openSectorPopup(params)
        return
      }
      // 柱状图：只在突出显示模式下响应点击。
      // params.name 是类别名，与 highlighted 里的键一致
      if (chartType.value === 'bar' && highlightMode.value) {
        toggleHighlight(params.name)
        return
      }
      closeSectorPopup()
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

  const renderKey = `${chartType.value}|${unifyScale.value}|${highlightMode.value}`
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
      // 柱高已经编码了数值，颜色再编码一遍是冗余的。
      // 这里保留平面色是给图例与 tooltip 圆点用的
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
        // 突出模式下逐根柱子带自己的样式，其余情况就是一个数值数组
        data: barData(categories, nums[i], i),
        // 柱宽上限。40 太粗，28 之后柱子之间透气了，整排看着轻快不少
        barMaxWidth: 28,
        // 只有能点的时候才给手型，免得让人以为点了会有反应
        cursor: highlightMode.value ? 'pointer' : 'default',
        yAxisIndex: dualAxis ? i : 0,
        // 系列级的兜底样式。突出模式下每一项都自带 itemStyle，会盖过这里
        itemStyle: barStyle(paletteOf(i)[0]),
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
  // 配色与点亮名单不在 renderKey 里 —— 它们不改变 option 形状，
  // 走 merge 还能让颜色渐变过去，点一根柱子也不会整张图重播动画
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
 * 轴名用灰色小字标在轴顶端 —— 双轴时不知道哪根轴代表哪个度量，
 * 图就没法读了。align 让文字贴着轴放，不至于飘到绘图区里
 */
const buildYAxis = (series) => {
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

/**
 * 导出图表为 PDF。
 *
 * 与 PNG 的区别在于这里是「矢量」的：服务端拿着与屏幕相同的那份聚合数据
 * 重新画一遍，柱子和弧线都是 PDF 的绘图指令，放大多少倍都是清晰的。
 * PNG 是位图，放进文档里一放大就糊 —— 而那正是需要 PDF 的场景。
 *
 * 数据不在前端重新算，直接把当前图表的配置发给后端，由后端用同一套
 * 聚合规则取数，避免两边算出不一样的结果。
 */
const exportPdf = async (options = {}) => {
  if (!props.executedSql || !dimension.value) {
    return
  }
  exporting.value = true
  try {
    // 饼图只用一个度量，与屏幕上保持一致
    const measures =
      aggregation.value === 'count'
        ? []
        : chartType.value === 'pie'
          ? [measure.value].filter(Boolean)
          : [measure.value, measure2.value].filter(Boolean)

    await downloadChartPdf({
      sql: props.executedSql,
      dimension: dimension.value,
      measures,
      aggregation: aggregation.value,
      topN: chartType.value === 'pie' ? PIE_TOP_N : BAR_TOP_N,
      chartType: chartType.value,
      chartTitle: buildExportTitle(),
      tableName: props.tableName,
      // 默认不附数据表，由导出前的弹框决定
      includeTable: options.includeTable === true,
      // 把界面上的观感原样带上，否则导出的图与屏幕上看到的不是同一张。
      // 配色发的是【已经派生好的整排颜色】而不是主色：派生用的是黄金角
      // 卷积运算，让后端再算一遍只会多一处可能与前端不一致的地方
      palettes: exportPalettes(),
      sectorColors: { ...sectorColors.value },
      highlightMode: highlightMode.value,
      highlighted: [...highlighted.value],
      unifyScale: unifyScale.value,
      keepFullPrecision: keepFullPrecision.value,
    })
    ElMessage.success(t('chart.exported'))
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    exporting.value = false
  }
}

/**
 * 导出时发给后端的配色：每个系列一整排颜色。
 *
 * 直接发 paletteOf 的结果而不是主色，理由见上：派生用到了 HSL 取模，
 * 后端重算一遍就多一处可能不一致的地方，而前端本来就已经算好了。
 * 柱状/折线只用每排第 0 个，饼图用整排，由后端按各自规则取用。
 */
const exportPalettes = () => {
  const count = chartType.value === 'pie' ? 1 : seriesCount.value
  return Array.from({ length: Math.max(1, count) }, (_, i) => [...paletteOf(i)])
}

// 当前图上实际画了几个系列。饼图恒为 1
const seriesCount = computed(() => {
  if (chartType.value === 'pie') {
    return 1
  }
  return [measure.value, measure2.value].filter(Boolean).length || 1
})

// 导出用的图标题：维度 + 聚合方式，让脱离界面看 PDF 的人知道这张图在讲什么
const buildExportTitle = () => {
  const agg = AGGREGATION_LABELS.value[aggregation.value] || aggregation.value
  const m = measure.value ? `${agg}(${measure.value})` : agg
  return dimension.value ? `${dimension.value} — ${m}` : m
}

// 导出按钮在父组件，通过 ref 调过来
// resize 供父组件在面板由隐藏转可见时调用 —— display:none 期间量不到宽高
const resize = () => {
  if (chart) {
    chart.resize()
  }
}

defineExpose({ exportPng, exportPdf, resize })

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
 * 配色 watch ①／③：换整套配色时清掉逐扇区的自定义。
 *
 * 不清的话，选完主题饼图还是花花的，看着像没生效。
 * 自定义是一次点击的事，重做成本低。
 * 只看下标 0 —— 饼图用的是度量1 那套，度量2 的配色动不了饼图。
 *
 * 副作用：清空 sectorColors 会让 colorKey 重算，进而可能多触发一次
 * watch ③。这是可接受的（见 colorKey 处的说明），但别误以为 A 是
 * 「无副作用的状态重置」—— 它是一条渲染链的起点。
 */
watch(
  () => [paletteIds.value[0], customColors.value[0]],
  () => {
    sectorColors.value = {}
  },
)

/**
 * 配色 watch ②／③：换配色或换数据后，点亮的柱子按新配色重绘。
 *
 * 点亮名单本身保留 —— 同一批类别还在的话就该继续亮着。
 *
 * 只在 highlightMode 为真时重绘：没开点亮功能时配色变化由 watch ③
 * 经 colorKey 覆盖，这里再画一次纯属浪费。
 * 换句话说，这个 watch 的存在理由是「点亮态有自己的绘制路径」，
 * 而不是「配色变了要重绘」—— 后者是 watch ③ 的活。
 */
watch([paletteIds, customColors], () => {
  if (chartData.value && highlightMode.value) {
    renderChart()
  }
})

/**
 * 渲染 watch ③／③：这几个开关只影响渲染，不影响数据，不必重新请求 ——
 * 直接重绘，避免白跑一趟后端。
 *
 * 依赖里的 colorKey 是有意放进去的：它让「扇区颜色变了」也能重绘。
 * 但要注意 colorKey 里还包含 paletteIds 与 customColors，
 * 而那两样已经被 watch ② 盯上了 —— 所以改配色时这个 watch 也会跟着触发，
 * 与 watch ② 形成一次重复渲染。这正是 colorKey 注释里说的「2~3 次」。
 *
 * 想消掉重复的话，是把 colorKey 从这里拿掉，而不是去动 watch ②：
 * 但拿掉之前必须先确认「改扇区颜色仍能重绘」有别的路径兜住。
 */
watch([unifyScale, keepFullPrecision, colorKey, highlightMode, highlighted], () => {
  if (chartData.value) {
    renderChart()
  }
})

// 尺寸监听的绑定挪到了 renderChart 里 —— 容器要等 columns 非空才渲染，
// 挂载时 chartRef 还是 null

/*
 * 容器消失时销毁图表实例。
 *
 * 模板是 v-if="columns.length === 0" / v-else 两条分支，查询失败会清空
 * columns，v-else 整个被卸载、chartRef 变 null。此时不销毁的话：
 *   · chart 仍指向已移出 DOM 的旧容器
 *   · 下次查询重建了新容器，但 renderChart 的 if (!chart) 进不去
 *   · 结果就是图永久空白、resize 失效，只能刷新页面
 * 观察 columns（而不是 chartRef）是因为 ref 由 v-if 控制，
 * 变化时机比 DOM 更新更早，能保证在新容器挂载前就把旧实例清干净
 */
watch(
  () => props.columns.length,
  (len) => {
    if (len === 0) {
      disposeChart()
    }
  },
)

onBeforeUnmount(() => {
  if (debounceTimer) {
    clearTimeout(debounceTimer)
  }
  if (animationTimer) {
    clearTimeout(animationTimer)
  }
  document.removeEventListener('mousedown', handleDocumentMouseDown)
  disposeChart()
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
  color: var(--ui-text);
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

/* 「切换配色会重置手调颜色」提示。做成可悬停的一行小字，
   平时不抢注意力，悬停能看完整说明 */
.cfg-note {
  font-size: 12px;
  color: var(--ui-warning);
  white-space: nowrap;
  cursor: help;
  border-bottom: 1px dashed currentColor;
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
  box-shadow: var(--shadow-ring);
}

.swatch.is-active {
  border-color: var(--ui-selected);
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
  border-color: var(--ui-selected);
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
  background: var(--ui-surface);
  border: 1px solid var(--ui-border);
  border-radius: 4px;
  box-shadow: var(--shadow-float);
}

.popup-title {
  max-width: 90px;
  font-size: 12px;
  color: var(--ui-text);
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
  /* 遮挡住下面尚未绘制的画布。这里必须是不透明白 ——
     半透明会让画布上的旧内容透上来，看起来像"图还没换干净" */
  background: var(--pure-white);
}

.panel-hint {
  padding: 20px 0;
}
</style>
