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

/**
 * 尝试关掉当前标签页。
 *
 * 浏览器只允许脚本关闭「没有被导航过」的标签页（history.length 为 1）。
 * 我们是用系统命令直接打开地址的，正常情况下满足条件；
 * 但如果用户是从别的页面点进来的，浏览器会拒绝且不报错 ——
 * 所以调用方必须准备一个兜底界面，不能假设一定关得掉。
 *
 * @returns {boolean} 是否成功发起关闭（不代表真的关掉了）
 */
export function tryCloseTab() {
  try {
    window.close()
    return true
  } catch {
    return false
  }
}
