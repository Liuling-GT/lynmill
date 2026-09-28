import { createApp } from 'vue'
import App from './App.vue'

import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

// tokens.css 必须在 element-plus 的样式之后 ——
// 它靠"同优先级、后加载者胜"来覆盖 --el-* 变量，提前加载会被 Element 的默认值盖回去
import './assets/tokens.css'
import './assets/main.css'

const app = createApp(App)

app.use(ElementPlus)

app.mount('#app')
