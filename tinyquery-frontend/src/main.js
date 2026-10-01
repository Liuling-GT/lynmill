import { createApp } from 'vue'
import App from './App.vue'

import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

// tokens.css 必须在 element-plus 的样式之后 ——
// 它靠"同优先级、后加载者胜"来覆盖 --el-* 变量，提前加载会被 Element 的默认值盖回去
import './assets/tokens.css'
import './assets/main.css'

import { loadToken } from './api/token'

// 先拿到访问令牌再挂载应用：令牌必须在任何 API 调用之前就位，
// 否则首屏那几个请求会全部 401。
// 打包运行时首页已经注入了令牌，这一步是空操作
async function bootstrap() {
  await loadToken()

  const app = createApp(App)
  app.use(ElementPlus)
  app.mount('#app')
}

bootstrap()
