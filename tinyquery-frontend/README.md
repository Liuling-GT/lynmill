# TinyQuery 前端

TinyQuery 的 Web 界面。Vue 3 + Vite。

## 开发

```sh
npm install
npm run dev        # 开发服务器（默认 5173），带热更新
```

开发时后端只提供 API（默认 8080），**不会自动打开浏览器** —— 前端跑在 Vite 自己的端口上，需要手动访问。

## 构建

```sh
npm run build      # 产出 dist/
```

**这一步不能跳过。** 后端的 `pom.xml` 里有一道防呆检查：`dist/` 不存在、或比 `src/` 旧，`mvn package` 会直接失败并提示先构建前端。

构建产物会被打进后端的 jar（`BOOT-INF/classes/static/`），所以打包顺序始终是**先前端、后后端**：

```sh
cd tinyquery-frontend && npm run build
cd .. && mvn clean package
```

## 代码检查

```sh
npm run lint
```

## 目录结构

| 路径 | 内容 |
|---|---|
| `src/App.vue` | 主界面（查询、结果表格、退出遮罩） |
| `src/components/` | 数据源面板与表单、图表面板 |
| `src/api/` | 后端接口封装；`lifecycle.js` 负责存活探测与关闭标签页 |
| `src/assets/tokens.css` | 设计变量（颜色、间距、圆角）。**组件里不要再写死色值** |

## 与后端的两处约定

- **令牌**：后端把一次性访问令牌注入到首页的 `window.__TINYQUERY_TOKEN__`，前端每个 API 请求都要带 `X-TinyQuery-Token` 请求头。见 `src/api/token.js`。
- **退出检测**：后端进程结束后页面不会自己知道，`src/api/lifecycle.js` 每隔 2 秒打一次 `/api/ping`，连续失败两次即判定已退出。

## 环境要求

Node.js `^22.18.0 || >=24.12.0`（见 `package.json` 的 `engines`）。
