import { createApp } from 'vue'
import App from './App.vue'

import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

// tokens.css 必须在 element-plus 的样式之后 ——
// 它靠"同优先级、后加载者胜"来覆盖 --el-* 变量，提前加载会被 Element 的默认值盖回去
import './assets/tokens.css'
import './assets/main.css'

import { loadToken } from './api/token'
import { i18n, syncHtmlLang, reconcileLocale } from './locales'

/*
 * Element Plus 自带文案（分页的「条/页」、确认框按钮、取色器的「确定/清空」）
 * 由 App.vue 里的 <el-config-provider> 驱动 —— locale 必须走那个组件才能响应式切换。
 * app.use(ElementPlus, { locale }) 是静态注入，切换语言时不会跟着变。
 */
async function bootstrap() {
  await loadToken()

  // 对齐网页与服务端两份语言设置。
  // 放在挂载之前：避免先按旧语言渲染一帧、再闪一下变成新语言。
  // 失败不阻断 —— 内部已 catch，最坏情况就是保持本地判断
  await reconcileLocale()

  const app = createApp(App)
  app.use(i18n)
  app.use(ElementPlus)

  // 同步初始语言到 <html lang>。
  // 用 syncHtmlLang 而不是 setLocale —— 后者会写 localStorage，
  // 会把 reconcileLocale 刚确定的「本地有没有选择」这个状态覆盖掉
  syncHtmlLang()

  app.mount('#app')
}

bootstrap()
