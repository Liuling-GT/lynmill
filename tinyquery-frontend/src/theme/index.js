/*
 * 主题的注册、持久化与切换。
 *
 * 结构刻意与 locales/index.js 对齐 —— 两者都是「用户偏好 + localStorage 为主
 * + 同步给桌面外壳」，行为一致才不会一处记得一处忘。
 *
 * ==================== 色值写在哪 ====================
 *
 * 本文件【不存色值】，只存主题标识。真正的颜色在 assets/tokens.css 的
 * :root[data-theme="..."] 块里，切换只改 <html data-theme>。
 * 这样色值仍然只有 tokens.css 一个来源，改色不用碰 JS。
 *
 * 唯一的例外是 desktopAccent（见下）—— 桌面外壳是 Swing，读不到 CSS。
 */
import { fetchTheme, pushTheme } from '../api/preferences'

const STORAGE_KEY = 'tinyquery.theme'

/** 发版改这里即可切换默认主题 */
export const DEFAULT_THEME = 'violet'

/**
 * 支持的主题。
 *
 * <p>顺序即设置页里的展示顺序：默认主题在最前，其余按「与默认主题的观感距离」
 * 排 —— 靛蓝最近、咖啡最远。用户从前往后试，差异是渐进的。</p>
 *
 * <p>desktopAccent 供后端同步桌面外壳用（控制窗口的状态色）。
 * 它必须与 tokens.css 里同名的 --accent 一致 —— ThemeTokensTest 会逐个断言，
 * 改色时漏改一边会红。</p>
 */
export const SUPPORTED_THEMES = [
  { value: 'violet', labelKey: 'settings.theme.violet', desktopAccent: '#8b5cf6' },
  { value: 'indigo', labelKey: 'settings.theme.indigo', desktopAccent: '#6366f1' },
  { value: 'bamboo', labelKey: 'settings.theme.bamboo', desktopAccent: '#08965e' },
  { value: 'coffee', labelKey: 'settings.theme.coffee', desktopAccent: '#b06b05' },
]

// 读回上次选择。拿不到、或值不认识时一律回落默认主题 ——
// 用户手改过 localStorage 不该让界面变成没上色的样子
export function loadTheme() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    if (saved && SUPPORTED_THEMES.some((item) => item.value === saved)) {
      return saved
    }
  } catch {
    // 隐私模式下 localStorage 可能抛异常，忽略即可
  }
  return DEFAULT_THEME
}

export function saveTheme(theme) {
  try {
    localStorage.setItem(STORAGE_KEY, theme)
  } catch {
    // 存不下就算了，本次会话内仍然生效
  }
}

/**
 * 判断本地是否已有明确选择。
 *
 * <p>与 hasStoredLocale 同理：用来区分「用户选过」和「只是回落到默认值」，
 * 这个区别决定了启动时该以谁为准。</p>
 */
export function hasStoredTheme() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY)
    return Boolean(saved) && SUPPORTED_THEMES.some((item) => item.value === saved)
  } catch {
    return false
  }
}

/**
 * 本页当前已应用的主题。
 *
 * <p>【为什么需要这个状态】{@link reconcileTheme} 在「本地没有选择」时会采纳
 * 服务端的主题，但有意不写 localStorage（写了就再也跟不上服务端了）。
 * 如果 {@link syncTheme} 这时再按 {@link loadTheme} 重新推导一遍，
 * 读到的仍然是「本地没有选择」→ 回落默认主题 → 把刚采纳的服务端主题抹掉。
 * 症状：换台机器（或清过站点数据）打开，服务端设的咖啡色不生效，
 * 得手动去设置页点一次才变色。</p>
 *
 * <p>语言那边没有这个问题，因为 {@code i18n.global.locale.value} 本身就是状态，
 * {@code syncHtmlLang} 读的是它而不是重新推导。主题缺的正是这样一个状态，
 * 这里补上，让「本页现在用的是哪套主题」只有一个来源。</p>
 */
let appliedTheme = DEFAULT_THEME

/**
 * 本页当前实际生效的主题。
 *
 * <p>与 {@link loadTheme} 的区别：后者读的是用户的本地偏好，
 * 在"本地没有选择、主题来自服务端"时会返回默认主题。
 * 要问"现在屏幕上是什么颜色"，必须用这个 —— 设置页的选中态就靠它，
 * 否则会出现「页面是咖啡色、设置页却显示选中紫罗兰」。</p>
 */
export function currentTheme() {
  return appliedTheme
}

/**
 * 应用主题但不做持久化。
 *
 * <p>只改 <html data-theme>：tokens.css 里的覆盖块靠这个属性选择器生效，
 * 整站颜色跟着走。默认主题不写属性 —— 让 :root 那一份直接生效，
 * 少一个属性选择器，也避免「属性值写错时整个页面没上色」这种失败方式。</p>
 */
function applyTheme(theme, { persist }) {
  if (!SUPPORTED_THEMES.some((item) => item.value === theme)) {
    return
  }
  appliedTheme = theme
  if (theme === DEFAULT_THEME) {
    document.documentElement.removeAttribute('data-theme')
  } else {
    document.documentElement.setAttribute('data-theme', theme)
  }
  if (persist) {
    saveTheme(theme)
  }
}

/**
 * 启动时对齐「网页」与「服务端」两份主题设置。
 *
 * <p>规则与语言完全一致：本地有选择则以本地为准；本地没有则以服务端为准。
 * 前者是用户的显式意愿，后者只是"没得选"时的兜底。</p>
 *
 * <p>无论走哪条分支，返回时本页都已经有一套确定的主题 —— 第一句先按本地
 * （或默认）定下来，后面才可能被服务端的值改写。这样 {@link syncTheme}
 * 就不会推回一个未定义的状态。</p>
 *
 * <p>失败不阻断启动 —— 后端没起来时保持本地判断即可，主题不该成为
 * 打不开页面的理由。</p>
 */
export async function reconcileTheme() {
  // 先按本地选择（没有就是默认主题）应用一次。
  // 【为什么要先做这一步】后面三条分支里有两条会直接 return，
  // 不先定下来，那两条路径下 appliedTheme 就会停在默认值上，
  // 把用户本地存着的主题丢掉
  applyTheme(loadTheme(), { persist: false })

  try {
    const serverTheme = await fetchTheme()
    if (!SUPPORTED_THEMES.some((item) => item.value === serverTheme)) {
      // 服务端给了个不认识的值，保持本地
      return
    }

    if (hasStoredTheme()) {
      // 本地有明确选择：以本地为准，把它推给服务端（桌面外壳要跟着变）
      if (loadTheme() !== serverTheme) {
        await pushTheme(loadTheme())
      }
      return
    }

    // 本地是空的（换机器 / 清过站点数据）：采纳服务端的选择，
    // 但不写 localStorage —— 保持"本地没有选择"这个状态，
    // 否则下次服务端改了主题，这里会因为"本地有选择"而反过来覆盖它
    applyTheme(serverTheme, { persist: false })

  } catch {
    // 后端不可用：保持本地（或默认）主题，不阻断启动
  }
}

/**
 * 只把当前主题同步到 <html data-theme>，不写 localStorage。
 *
 * <p>与 syncHtmlLang 同理：启动时用它而不是 setTheme —— 后者会持久化，
 * 会把「本地没有选择」这个状态抹掉。</p>
 *
 * <p>推的是 {@link currentTheme}（本页实际生效的那一套），
 * 不是 {@link loadTheme} 重新推导的值 —— 后者读的是 localStorage，
 * 在"本地没有选择、主题来自服务端"时会推成默认主题，把服务端的选择抹掉。
 * 这一条踩过，见 appliedTheme 的注释。</p>
 */
export function syncTheme() {
  applyTheme(appliedTheme, { persist: false })
}

/**
 * 切换主题并持久化。
 *
 * <p>顺带把主题告诉后端：控制窗口的「运行中」状态色是桌面外壳自己画的，
 * 它读不到浏览器的 CSS，只能由后端记着。同步失败不回滚本地选择 ——
 * 网页上的主题本来就该立刻生效。</p>
 */
export async function setTheme(theme) {
  applyTheme(theme, { persist: true })

  try {
    await pushTheme(theme)
  } catch (error) {
    console.warn('主题已在本页生效，但同步到后端失败，桌面外壳可能未更新', error)
  }
}
