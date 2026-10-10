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
