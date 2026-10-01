import axios from 'axios'
import { TOKEN_HEADER, getToken } from './token'

// 数据源接口封装。后端统一返回 { code, message, data }，code === 0 为成功
const request = axios.create({
  baseURL: '/api',
  timeout: 60000, // 加载数据源可能较慢，给足超时
})

// 每个请求都带上访问令牌。没有它后端一律回 401
request.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers[TOKEN_HEADER] = token
  }
  return config
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

// 重新加载数据源（数据文件或数据库内容变化后同步）
export const refreshDataSource = (name) =>
  request.post(`/datasource/${encodeURIComponent(name)}/refresh`)

// 测试数据源配置是否可用（不注册、不加载数据）
export const testDataSource = (config) => request.post('/datasource/test', config)

// 系统凭据存储是否可用（决定「保存密码」勾选框能否点）
export const getCredentialStatus = () => request.get('/datasource/credential-status')
