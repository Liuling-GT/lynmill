import { requestHeaders } from './token'

/**
 * 界面偏好设置。目前只有语言。
 *
 * <h2>为什么语言要通知后端</h2>
 *
 * <p>语言本来只存 localStorage，服务端不需要知道 —— 每个请求都带
 * {@code X-TinyQuery-Locale} 头，服务端照着回就行。</p>
 *
 * <p>但桌面外壳（托盘菜单、控制窗口）的文案不在请求线程上取，拿不到那个头，
 * 只能在启动时读一次配置。于是「网页切了语言，托盘还是老语言」——
 * 同一个程序两套语言设置。</p>
 *
 * <p>所以切语言时额外调一次后端，让它记下来并通知桌面外壳刷新。
 * 这样重启之后也记得。</p>
 *
 * <p><b>同步失败不阻断切换</b>：网页上的语言是本地立刻生效的，
 * 不该因为后端没通就卡住用户。最坏情况是桌面端这一份没跟上，
 * 下次重启程序时会以本地选择为准再同步一次。</p>
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
