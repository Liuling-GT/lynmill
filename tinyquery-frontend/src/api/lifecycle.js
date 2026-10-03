import axios from 'axios'
import { TOKEN_HEADER, getToken } from './token'

/*
  存活探测。

  桌面外壳退出时（控制窗口的「退出」、托盘菜单的「退出」、窗口右上角 ✕），
  后台 java 进程会结束，但浏览器里这个页面不会自己关掉 —— 它会停在原处，
  之后任何操作都失败，看起来像程序坏了。

  页面要察觉「服务没了」只能主动去问，所以这里定时打一个最轻的接口。
*/

// 超时必须很短：这个请求每 2 秒发一次，它的"失败"就是"程序已退出"的信号。
// 超时给长了，退出检测就会变得迟钝
const request = axios.create({
  baseURL: '/api',
  timeout: 1500,
})

request.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers[TOKEN_HEADER] = token
  }
  return config
})

/**
 * 探测后台是否还活着。
 *
 * @returns {Promise<boolean>} true = 还活着
 */
export async function ping() {
  try {
    await request.get('/ping')
    return true
  } catch (error) {
    // 关键区分：只有"连不上"才算退出。
    // 服务器有回应但状态码不对（例如 401）说明它活得好好的，不能判成退出，
    // 否则令牌一旦出问题，页面会立刻误报"程序已退出"
    return Boolean(error.response)
  }
}

/**
 * 盯着后台是否退出。确认退出后回调一次，然后自动停止轮询。
 *
 * 为什么要"连续失败若干次"而不是一次就下结论：一次请求可能因为机器卡顿、
 * 后端正在处理大查询等原因超时，单次失败就关页面太草率了。
 *
 * @param {Function} onShutdown 确认退出时调用，只会被调用一次
 * @param {object} [options]
 * @param {number} [options.intervalMs] 轮询间隔
 * @param {number} [options.failuresNeeded] 连续失败几次才判定退出
 * @returns {Function} 停止监视
 */
export function watchForShutdown(onShutdown, options = {}) {
  const intervalMs = options.intervalMs ?? 2000
  const failuresNeeded = options.failuresNeeded ?? 2

  let failures = 0
  let stopped = false
  let busy = false

  const timer = setInterval(async () => {
    // 上一次还没回来就跳过这一轮：超时 1.5 秒、间隔 2 秒，
    // 正常情况下不会重叠，但机器卡顿时会，重叠会让失败计数虚高
    if (stopped || busy) {
      return
    }
    busy = true
    try {
      const alive = await ping()
      if (stopped) {
        return
      }
      if (alive) {
        failures = 0
        return
      }
      failures += 1
      if (failures >= failuresNeeded) {
        stopped = true
        clearInterval(timer)
        onShutdown()
      }
    } finally {
      busy = false
    }
  }, intervalMs)

  return () => {
    stopped = true
    clearInterval(timer)
  }
}

/*
  关闭标签页的快捷键文案。

  只在「网页关不掉自己」时才用得上，所以文案必须让用户能照着按。
  macOS 的关闭快捷键是 ⌘W，其它平台是 Ctrl+W。
*/
export function closeShortcutHint() {
  const platform = navigator.platform || navigator.userAgent || ''
  return /mac/i.test(platform) ? '⌘W' : 'Ctrl+W'
}

/**
 * 尝试关掉当前标签页；关不掉时回调一次，供调用方给出手动关闭的指引。
 *
 * <p>按 MDN 对 Window.close() 的规则：窗口是网页自己创建的、或者**没有被导航过**
 * （history 长度仍为 1）时，浏览器才允许脚本关闭它。本页是应用用系统命令从外部
 * 打开的标签页，属于后者 —— 从自动打开进来的全新标签页关得掉；
 * 用户手动在浏览器里输地址进来、或标签页里本来就有别的页面时会被拒。</p>
 *
 * <p>{@code window.close()} 被拒时**不会有任何提示**，所以这里不靠它的返回值判断，
 * 而是延迟一点回头查 {@code window.closed} —— 那才是唯一可信的结果。
 * 延迟是给浏览器留出真正执行关闭的时间。</p>
 *
 * @param {Function} [onStillOpen] 标签页仍然开着时调用（说明需要用户手动关）
 * @returns {boolean} 是否成功发起了关闭动作（不代表真的关掉了）
 */
export function tryCloseTab(onStillOpen) {
  let threw = false
  try {
    window.close()
  } catch {
    // 个别浏览器会在这里抛异常，但更常见的是「什么都不做、也不报错」，
    // 所以下面统一用 window.closed 判断结果，不依赖这里的分支
    threw = true
  }

  if (typeof onStillOpen === 'function') {
    setTimeout(() => {
      if (!window.closed) {
        onStillOpen()
      }
    }, 400)
  }

  return !threw
}
