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
import { pushLocale } from '../api/preferences'
import { getBuildInfo } from '../api/datasource'

const { t, locale } = useI18n()

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
 * 拿不到 X-TinyQuery-Locale 头。
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
</style>
