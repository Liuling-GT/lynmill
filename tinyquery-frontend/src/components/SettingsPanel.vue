<template>
  <section class="panel">
    <header class="panel-head">
      <div>
        <h2 class="panel-title">{{ t('settings.title') }}</h2>
        <p class="panel-sub">{{ t('settings.subtitle') }}</p>
      </div>
    </header>

    <div class="settings-body">
      <div class="setting-row">
        <div class="setting-label">
          <span class="setting-name">{{ t('settings.language.label') }}</span>
          <span class="setting-hint">{{ t('settings.language.hint') }}</span>
        </div>
        <el-radio-group :model-value="locale" @change="handleChange">
          <el-radio-button
            v-for="item in SUPPORTED_LOCALES"
            :key="item.value"
            :value="item.value"
          >
            {{ t(item.labelKey) }}
          </el-radio-button>
        </el-radio-group>
      </div>

      <!-- 主题色。与语言并列，是同一层的显示偏好。
           用带色点的按钮而不是下拉框：主题的差别主要就是颜色，
           让用户直接看到色点，比读四个名字再一个个试快得多 -->
      <div class="setting-row">
        <div class="setting-label">
          <span class="setting-name">{{ t('settings.theme.label') }}</span>
          <span class="setting-hint">{{ t('settings.theme.hint') }}</span>
        </div>
        <div class="theme-picker">
          <button
            v-for="item in THEMES"
            :key="item.value"
            type="button"
            class="theme-option"
            :class="{ 'is-active': theme === item.value }"
            :title="t(item.labelKey)"
            :aria-pressed="theme === item.value"
            @click="handleTheme(item.value)"
          >
            <span class="theme-dot" :style="{ background: item.desktopAccent }" />
            <span class="theme-name">{{ t(item.labelKey) }}</span>
          </button>
        </div>
      </div>

      <!-- 构建标识。取不到时（开发态直接跑，没有构建信息）整行不显示 ——
           显示一串占位符比不显示更像坏了 -->
      <div v-if="build.display" class="setting-row">
        <div class="setting-label">
          <span class="setting-name">{{ t('settings.about.label') }}</span>
          <span class="setting-hint">{{ t('settings.about.hint') }}</span>
        </div>
        <!-- 不翻译：版本号、git 哈希、时间都是标识符与数据，
             翻了反而对不上（用户拿它去比对的是构建产物里的原文） -->
        <span class="build-id">{{ build.display }}</span>
      </div>
    </div>
  </section>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { SUPPORTED_LOCALES, setLocale } from '../locales'
import { SUPPORTED_THEMES, currentTheme, setTheme } from '../theme'
import { pushLocale } from '../api/preferences'
import { getBuildInfo } from '../api/datasource'

const { t, locale } = useI18n()

/*
 * 主题。色值不在这里 —— 只有标识与色点。
 *
 * 用 ref(currentTheme()) 而不是 ref(loadTheme())：loadTheme() 读的是
 * 用户的本地偏好，在"本地没有选择、主题来自服务端"时返回默认主题。
 * 那样会出现「页面已经是咖啡色、设置页却显示选中紫罗兰」——
 * 用户点一下咖啡色以为在切换，其实什么都没变。
 * currentTheme() 给的是本页实际生效的那一套，与屏幕上的颜色一致。
 *
 * 用 ref 而不是直接读：切换后要触发重渲染，让选中态跟着走。
 */
const THEMES = SUPPORTED_THEMES
const theme = ref(currentTheme())

/**
 * 切主题。
 *
 * 与切语言不同，这里先本地生效再异步同步后端的顺序是反的 ——
 * 不需要 setTheme 之外的任何处理：setTheme 内部先应用（用户立刻看到颜色变了）
 * 再推给后端，推失败只记警告不回滚。理由与语言一致：网页上的主题
 * 本来就该立刻生效，不该因为后端没通而卡住用户。
 *
 * 同步给后端是为了桌面外壳（托盘与控制窗口）—— 它们是 Swing 画的，
 * 读不到浏览器的 CSS 变量。
 */
const handleTheme = (value) => {
  theme.value = value
  setTheme(value)
}

/*
 * 构建标识。默认空 —— 接口没回来或取不到值时整行不显示，
 * 而不是先显示一个空框再填，避免界面闪一下。
 *
 * 失败静默：这只是一个诊断信息，取不到不该影响设置页的其它功能。
 */
const build = ref({ version: '', commit: '', time: '', display: '' })

onMounted(async () => {
  try {
    const data = await getBuildInfo()
    // 后端保证字段是空串而不是 null，这里只做一次防御性判断
    if (data && typeof data.display === 'string') {
      build.value = data
    }
  } catch {
    // 保留空值，整行不显示
  }
})

/**
 * 切语言。
 *
 * 先在本地生效（用户立刻看到界面变了），再异步同步给后端 ——
 * 后端那一份是给桌面外壳（托盘菜单、控制窗口）用的，它们的文案不在请求线程上取，
 * 拿不到 X-LynMill-Locale 头。
 *
 * 同步失败只记警告，不回滚本地选择：网页上的语言本来就该立刻生效，
 * 不该因为后端没通而卡住用户。最坏情况是桌面端这一份没跟上。
 */
const handleChange = async (value) => {
  setLocale(value)

  try {
    await pushLocale(value)
  } catch (error) {
    console.warn('语言已在本页生效，但同步到后端失败，桌面端可能未更新', error)
  }
}
</script>

<style scoped>
.settings-body {
  padding: 4px 0;
}

.setting-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 16px 0;
  border-bottom: 1px solid var(--line);
}

.setting-row:last-child {
  border-bottom: none;
}

.setting-label {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.setting-name {
  font-size: 14px;
  color: var(--ink);
}

/* 说明文字比正文灰一档，与其它面板的 .panel-sub 同一层次 */
.setting-hint {
  font-size: 12px;
  color: var(--ink-3);
}

/* 构建标识用等宽字体：里面是哈希与时间，等宽更容易逐字比对 */
.build-id {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  color: var(--ink-2);
  /* 窄窗口下别撑破行 */
  word-break: break-all;
  text-align: right;
}

/* ==================== 主题色选择 ==================== */

/* 允许换行：窄窗口下四个选项排不下时折行，而不是把右侧撑出面板 */
.theme-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: flex-end;
}

/*
 * 每个选项自带色点，所以不用 el-radio-button —— 那个只能给文字上色，
 * 塞不进一个小圆点。自己画成按钮，选中态用强调色描边。
 */
.theme-option {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px 5px 8px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface);
  color: var(--ink-2);
  font-size: 13px;
  font-family: inherit;
  line-height: 1.2;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background-color 0.15s;
}

.theme-option:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--ink);
}

/* 选中态：强调色描边 + 浅底。两处都用令牌，换主题时自己就跟着变 */
.theme-option.is-active {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--ink);
}

/* 色点。白色内描边让浅色主题（咖啡色的浅黄、竹青的亮青）在白卡片上也有边界 */
.theme-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  box-shadow: inset 0 0 0 1px rgba(0, 0, 0, 0.12);
  flex: none;
}

.theme-name {
  white-space: nowrap;
}
</style>
