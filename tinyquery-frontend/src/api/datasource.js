import axios from 'axios'

// 数据源接口封装。后端统一返回 { code, message, data }，code === 0 为成功
const request = axios.create({
  baseURL: '/api',
  timeout: 60000, // 加载数据源可能较慢，给足超时
})

request.interceptors.response.use(
  (response) => {
    const body = response.data
    // 业务失败：把后端 message 透传给调用方，而不是丢掉只留 HTTP 状态码
    if (body && typeof body.code === 'number') {
      if (body.code === 0) {
        return body.data
      }
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return response.data
  },
  (error) => {
    const body = error.response && error.response.data
    const message =
      (body && body.message) || error.message || '网络请求失败'
    return Promise.reject(new Error(message))
  },
)

// 列出所有已注册数据源（敏感字段已由服务端脱敏）
export const listDataSources = () => request.get('/datasource')

// 列出支持的数据源类型及字段描述，供表单动态渲染
export const listDataSourceTypes = () => request.get('/datasource/types')

// 注册数据源
export const registerDataSource = (config) => request.post('/datasource', config)

// 删除数据源
export const removeDataSource = (name) =>
  request.delete(`/datasource/${encodeURIComponent(name)}`)
