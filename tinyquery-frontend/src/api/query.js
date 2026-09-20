import axios from 'axios'

// 查询接口封装。后端统一返回 { code, message, ... }，code === 0 为成功。
// 这里返回整个响应体而不是只返回 data —— 表格需要 columnTypes 与分页字段
const request = axios.create({
  baseURL: '/api',
  // 百万行聚合可能较慢，给足超时
  timeout: 120000,
})

request.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code === 'number') {
      if (body.code === 0) {
        return body
      }
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (error) => {
    const body = error.response && error.response.data
    const message = (body && body.message) || error.message || '网络请求失败'
    return Promise.reject(new Error(message))
  },
)

// 执行查询，返回分页后的明细
export const queryTable = (payload) => request.post('/query', payload)

// 按维度聚合，供图表使用。不返回明细行，只返回类别与值
export const queryChart = (payload) => request.post('/query/chart', payload)
