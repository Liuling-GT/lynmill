import { requestHeaders } from './token'

/**
 * 界面偏好设置。目前是语言与主题。
 *
 * 语言本来只存 localStorage，服务端不需要知道 —— 每个请求都带
 * X-TinyQuery-Locale 头，服务端照着回就行。但桌面外壳（托盘菜单、控制窗口）
 * 的文案不在请求线程上取，拿不到那个头，只能在启动时读一次配置。
 * 于是「网页切了语言，托盘还是老语言」，同一个程序出现两套语言设置。
 * 所以切语言时额外调一次后端，让它记下来并通知桌面外壳刷新，这样重启之后也记得。
 *
 * 主题同理，但更硬：控制窗口上「● 运行中」那行字用的是强调色，
 * 而那是 Swing 画的，读不到浏览器的 CSS 变量。后端不记着，
 * 桌面外壳就只能是默认紫色。见 DesktopTheme。
 *
 * 同步失败不阻断切换：网页上的偏好是本地立刻生效的，不该因为后端没通就卡住用户。
 * 最坏情况是桌面端这一份没跟上，下次重启程序时会以本地选择为准再同步一次。
 */

const BASE = '/api/preferences'

/** 读取服务端当前语言。页面加载时用于判断要不要把本地选择同步过去 */
export async function fetchLocale() {
  const res = await fetch(`${BASE}/locale`, { headers: requestHeaders() })
  if (!res.ok) {
    throw new Error(`fetchLocale failed: ${res.status}`)
  }
  const body = await res.json()
  return body.data.locale
}

/**
 * 把语言同步给服务端，并让它通知桌面外壳。
 *
 * @param {string} locale 'zh' | 'en'
 */
export async function pushLocale(locale) {
  const res = await fetch(`${BASE}/locale`, {
    method: 'POST',
    headers: requestHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ locale }),
  })
  if (!res.ok) {
    throw new Error(`pushLocale failed: ${res.status}`)
  }
  const body = await res.json()
  return body.data.locale
}

/** 读取服务端当前主题。页面加载时用于判断要不要把本地选择同步过去 */
export async function fetchTheme() {
  const res = await fetch(`${BASE}/theme`, { headers: requestHeaders() })
  if (!res.ok) {
    throw new Error(`fetchTheme failed: ${res.status}`)
  }
  const body = await res.json()
  return body.data.theme
}

/**
 * 把主题同步给服务端，并让它通知桌面外壳换色。
 *
 * @param {string} theme 'violet' | 'indigo' | 'bamboo' | 'coffee'
 */
export async function pushTheme(theme) {
  const res = await fetch(`${BASE}/theme`, {
    method: 'POST',
    headers: requestHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ theme }),
  })
  if (!res.ok) {
    throw new Error(`pushTheme failed: ${res.status}`)
  }
  const body = await res.json()
  return body.data.theme
}
