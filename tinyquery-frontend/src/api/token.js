// 访问令牌的唯一出口。
//
// 三处调用（api/query.js、api/datasource.js、App.vue 里的导出 fetch）都从这里取，
// 避免哪天只改了其中两处 —— 导出那条尤其容易漏，因为它为了流式下载没有用 axios。
//
// 令牌的来源有两种，对应后端的两种运行方式：
//   · 打包运行 —— 后端把 window.__TINYQUERY_TOKEN__ 直接注入首页
//     （见后端 config/IndexPageController）
//   · 开发模式 —— 页面由 Vite 提供，后端注入不进来，启动时向后端取一次
//     （见后端 config/DevTokenController）
//
// 后端侧的原理见 config/SecurityToken 的类注释

export const TOKEN_HEADER = 'X-TinyQuery-Token'

export const getToken = () => window.__TINYQUERY_TOKEN__ || ''

// 取令牌的请求头。没有令牌时返回空对象 —— 让后端回 401、
// 把「缺少访问令牌」的提示显示到界面上，比在前端静默失败好排查得多
export const tokenHeaders = () => {
  const token = getToken()
  return token ? { [TOKEN_HEADER]: token } : {}
}

// 开发模式启动时调一次。打包运行时首页已注入，这里直接返回、不发请求。
// 必须在挂载应用之前完成，否则首屏那几个请求会全部 401
export const loadToken = async () => {
  if (getToken()) {
    return
  }
  try {
    const response = await fetch('/api/dev/token')
    if (!response.ok) {
      return
    }
    const body = await response.json()
    const token = body && body.data && body.data.token
    if (token) {
      window.__TINYQUERY_TOKEN__ = token
    }
  } catch {
    // 取不到就不带令牌，后续请求由后端回 401，
    // 错误信息会照常显示在界面上。这里不往上抛，免得整个应用起不来
  }
}
