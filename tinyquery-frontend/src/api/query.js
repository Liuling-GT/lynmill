import axios from 'axios'
import { i18n } from '../locales'
import { requestHeaders } from './token'

// 查询接口封装。后端统一返回 { code, message, ... }，code === 0 为成功。
// 这里返回整个响应体而不是只返回 data —— 表格需要 columnTypes 与分页字段
const request = axios.create({
  baseURL: '/api',
  // 百万行聚合可能较慢，给足超时
  timeout: 120000,
})

// 每个请求都带上访问令牌与当前语言
request.interceptors.request.use((config) => {
  Object.assign(config.headers, requestHeaders())
  return config
})

// 后端的报错文案自带 "error:" 前缀（SQLParser / QueryEngine 里几十处都这么写），
// 而数据源那一组异常（DataSourceException）不带。界面统一以 "Error: " 开头，
// 所以在这里把后端前缀剥掉，两种来源在界面上就一致了。
// 不区分大小写，避免将来后端写成 "Error:" 时剥不掉。
//
// 导出接口走原生 fetch，不经过本文件的拦截器，需自行调用本函数
export const stripErrorPrefix = (message) =>
  typeof message === 'string' ? message.replace(/^\s*error:\s*/i, '') : message

// 按统一格式包装后端报错：message 去掉自带前缀，hint 原样带上（紫色那条）
const toFailure = (message, hint) => {
  const failure = new Error(stripErrorPrefix(message) || i18n.global.t('api.requestFailed'))
  if (hint) {
    failure.hint = hint
  }
  return failure
}

request.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code === 'number') {
      if (body.code === 0) {
        return body
      }
      // hint 挂到 error 上一起带走 —— 后端的补充提示（紫色那条）
      // 与 message 是两回事，丢掉就没法并排展示了
      return Promise.reject(toFailure(body.message, body.hint))
    }
    return body
  },
  (error) => {
    const body = error.response && error.response.data
    const message = (body && body.message) || error.message || i18n.global.t('api.networkFailed')
    return Promise.reject(toFailure(message, body && body.hint))
  },
)

// 执行查询，返回分页后的明细
export const queryTable = (payload) => request.post('/query', payload)

// 按维度聚合，供图表使用。不返回明细行，只返回类别与值
export const queryChart = (payload) => request.post('/query/chart', payload)

/**
 * 结果导出的下载。
 *
 * 走原生 fetch 而不是 axios：响应体是二进制文件，交给拦截器反而要额外
 * 处理 blob 分支。失败时后端回 JSON 错误体（HTTP 仍是 200），
 * 所以要把两种响应分开判断，不能直接 blob() —— 否则会把错误信息
 * 当成文件存下来，用户打开才发现是个坏文件。
 *
 * @param path    接口路径，如 '/query/export/pdf'
 * @param payload 请求体
 * @param onDone  文件名回调，交给调用方决定是否提示
 */
const downloadFile = async (path, payload, onDone) => {
  const response = await fetch(`/api${path}`, {
    method: 'POST',
    headers: requestHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(payload),
  })

  if (!response.ok) {
    throw new Error(i18n.global.t('result.exportHttpError', { status: response.status }))
  }

  const contentType = response.headers.get('Content-Type') || ''
  if (contentType.includes('application/json')) {
    const body = await response.json()
    throw new Error(stripErrorPrefix(body.message) || i18n.global.t('result.exportFailed'))
  }

  const fileName = resolveFileName(response.headers.get('Content-Disposition'))
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)

  if (onDone) {
    onDone(fileName)
  }
}

/**
 * 导出结果表为 PDF，可选横向与字号。
 *
 * 方向与字号由调用方在导出前定好（见 ChartPanel / App 的导出弹框）：
 * 一张 16 列的表在 A4 纵向下每列只分到 3 个多中文字的宽度，
 * 列名会被截成「帖… 发…」，导完才发现没法看。所以先把选择权交给用户。
 */
export const downloadPdf = (payload, onDone) => downloadFile('/query/export/pdf', payload, onDone)

/**
 * 导出图表为 PDF（矢量）。
 *
 * 与 PNG 的区别是「矢量」：服务端拿着与屏幕相同的那份聚合数据重新画一遍，
 * 柱子和弧线都是 PDF 绘图指令，放大多少倍都清晰。
 */
export const downloadChartPdf = (payload) =>
  downloadFile('/query/export/chart-pdf', payload)

/**
 * 导出前的版面预估：这张表按各档位导出会不会被截断。
 *
 * 返回 6 个档位（纵向/横向 × 3 档字号）各自「放不下几列」，
 * 由界面标出推荐档位。这样用户在选之前就知道结果，
 * 而不是导完打开才发现列名被截了。
 */
export const previewPdfLayout = (payload) =>
  request.post('/query/export/pdf-preview', payload)

/**
 * 从 Content-Disposition 里取文件名。
 *
 * 后端同时下发 ASCII 兜底名（filename=）与 RFC 5987 的 UTF-8 名
 * （filename*=）。中文名只可能出现在后者里，所以优先解析它 ——
 * 直接用前者会拿到 %E6%88%90%E7%BB%A9 这种转义串。
 */
const resolveFileName = (disposition) => {
  if (!disposition) {
    return 'chart.pdf'
  }
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(disposition)
  if (utf8) {
    try {
      return decodeURIComponent(utf8[1].trim())
    } catch {
      // 转义串坏了就退回下面的 ASCII 名，不至于连文件名都没有
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition)
  return plain ? plain[1].trim() : 'chart.pdf'
}

