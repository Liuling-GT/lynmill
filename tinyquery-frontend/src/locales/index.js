/*
 * i18n 实例与语言持久化。
 *
 * 默认语言是 zh（中文）—— 开发与调试期以中文为准，与现有界面一致。
 * 发版时若要改成英文，只改 DEFAULT_LOCALE 一处。
 *
 * 语言仍以 localStorage 为主：它是首屏就能用的，不依赖后端是否已就绪。
 * 但切换时会额外同步一份到后端 —— 桌面外壳（托盘菜单、控制窗口）的文案
 * 不在请求线程上取，拿不到 X-TinyQuery-Locale 头，只能靠后端记着。
 * 详见 api/preferences.js。
 */
import { createI18n } from 'vue-i18n'
import zhCN from './zh-CN'
import enUS from './en-US'
import { fetchLocale, pushLocale } from '../api/preferences'

const STORAGE_KEY = 'tinyquery.locale'

// 发版改这里即可切换默认语言
export const DEFAULT_LOCALE = 'zh'

export const SUPPORTED_LOCALES = [
  { value: 'zh', labelKey: 'settings.language.zh' },
  { value: 'en', labelKey: 'settings.language.en' },
]

// 读回上次选择。拿不到、或值不认识时一律回落默认语言 ——
// 用户手改过 localStorage 不该让界面变成空白
export function loadLocale() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved && SUPPORTED_LOCALES.some((item) => item.value === saved)) {
      return saved
    }
  } catch {
    // 隐私模式下 localStorage 可能抛异常，忽略即可
  }
  return DEFAULT_LOCALE
}

export function saveLocale(locale) {
  try {
    localStorage.setItem(STORAGE_KEY, locale)
  } catch {
    // 存不下就算了，本次会话内仍然生效
  }
}

/**
 * 判断本地是否已有明确选择。
 *
 * <p>用来区分「用户选过」和「只是回落到默认值」—— 这个区别决定了
 * 启动时该以谁为准（见 {@link reconcileLocale}）。</p>
 */
export function hasStoredLocale() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return Boolean(saved) && SUPPORTED_LOCALES.some((item) => item.value === saved)
  } catch {
    return false
  }
}

/**
 * 启动时对齐「网页」与「服务端」两份语言设置。
 *
 * <h2>为什么需要对齐</h2>
 *
 * <p>网页的语言存在 localStorage，服务端那一份存在 locale.txt（供托盘菜单等
 * 桌面外壳读取）。多数时候两者一致，但会分叉：</p>
 *
 * <ul>
 *   <li>换了浏览器、换了机器、清了站点数据 → 本地是空的，回落到中文，
 *       而服务端可能记着英文。此时界面显示中文、托盘显示英文，
 *       一个程序两套语言。</li>
 *   <li>本地有明确选择 → 用户就是想要这个，应当以本地为准推给服务端。</li>
 * </ul>
 *
 * <p>所以规则是：<b>本地有选择则以本地为准；本地没有则以服务端为准。</b>
 * 前者是用户的显式意愿，后者只是"没得选"时的兜底。</p>
 *
 * <h2>失败不阻断启动</h2>
 *
 * <p>后端没起来、或接口出错时，保持本地判断即可 —— 语言不该成为
 * 打不开页面的理由。这也是把整段包在 try 里的原因。</p>
 */
export async function reconcileLocale() {
  try {
    const serverLocale = await fetchLocale()
    if (!SUPPORTED_LOCALES.some((item) => item.value === serverLocale)) {
      // 服务端给了个不认识的值，不动本地
      return
    }

    if (hasStoredLocale()) {
      // 本地有明确选择：以本地为准，把它推给服务端（托盘等要跟着变）
      if (loadLocale() !== serverLocale) {
        await pushLocale(loadLocale())
      }
      return
    }

    // 本地是空的（换机器 / 清过站点数据）：采纳服务端的选择，
    // 但**不写 localStorage** —— 保持"本地没有选择"这个状态，
    // 否则下次服务端改了语言，这里会因为"本地有选择"而反过来覆盖它
    applyLocale(serverLocale, { persist: false })

  } catch {
    // 后端不可用：保持本地（或默认）语言，不阻断启动
  }
}

export const i18n = createI18n({
  // Composition API 模式。关掉 legacy 才能用 useI18n()
  legacy: false,
  locale: loadLocale(),
  // 找不到的键回落中文，而不是显示键名 ——
  // 漏翻时用户看到的是中文，不是 "chart.dimension" 这种内部标识
  fallbackLocale: 'zh',
  messages: {
    zh: zhCN,
    en: enUS,
  },
})

/**
 * 应用语言但不做持久化。供"以服务端为准"与"仅同步 <html lang>"的场景使用。
 *
 * <p>不写 localStorage 是有意的：见 {@link reconcileLocale} —— 保持
 * 「本地没有选择」这个状态，下次才会继续以服务端为准。</p>
 */
function applyLocale(locale, { persist }) {
  if (!SUPPORTED_LOCALES.some((item) => item.value === locale)) {
    return
  }
  i18n.global.locale.value = locale
  if (persist) {
    saveLocale(locale)
  }
  // 同步 <html lang>，影响字体回退与断词规则
  document.documentElement.setAttribute('lang', locale === 'en' ? 'en' : 'zh-CN')
}

/**
 * 只把当前语言同步到 {@code <html lang>}，不写 localStorage。
 *
 * <p>启动时用它而不是 {@link setLocale}：后者会持久化，会把
 * 「本地没有选择」这个状态抹掉（曾经踩过 —— reconcile 刚采纳服务端语言，
 * 紧接着这一步就把它写成了本地选择，于是下次服务端改语言不再生效）。</p>
 */
export function syncHtmlLang() {
  applyLocale(i18n.global.locale.value, { persist: false })
}

/** 切换语言并持久化。Element Plus 的语言包由 main.js 单独同步 */
export function setLocale(locale) {
  applyLocale(locale, { persist: true })
}
