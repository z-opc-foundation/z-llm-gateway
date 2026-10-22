# z-llm-component

z-llm（大模型中心）跨仓唯一前端面：把「供应商接入配置 / 模型定义」这一组 CRUD 页面
收敛成一个可复用的 React 组件包，由 `z-llm-suit` 独立壳或平台主壳挂载。

- **npm 包名**：`@yuku123/z-llm-component`（仓名是 `z-llm-gateway`，包名去掉了 `-gateway`）
- **宿主接入**：`@yuku123/z-llm-component/pages`（manifest + 请求层），主壳可只取 `ComponentDocPage`
- **后端**：z-llm-gateway 的 llm-center 面（`/llm-center/**`），前缀由 `configureLlm({apiBase})` 注入
- **技术栈**：React 19 + antd 6 + react-router-dom 7 + vite（library mode，双入口 `.` / `./pages`）

---

## 一、能力概览

路由前缀 `/z-llm`，菜单与路由都在 `src/pages-manifest.jsx` 里声明，宿主（suit 或主壳）读数组自己组装。

| 页面 | 路由（相对 `/z-llm/*`） | 源文件 | 能力 |
|------|--------------------------|--------|------|
| 首页 | `home` | `pages/HomePage.jsx` | 读 `userInfo` 显示登录人，按 `menuItems` 渲染功能入口卡片 |
| 供应商 | `providers` | `pages/Provider.jsx` | 供应商接入配置的新增 / 编辑 / 删除 |
| 模型 | `models` | `pages/Model.jsx` | 模型定义的新增 / 编辑 / 删除（供应商下拉） |
| 登录 | （不在菜单内） | `pages/LoginPage.jsx` | mock 登录，写 `token` / `userInfo` 后跳 `/z-llm/home` |
| 组件文档 | `docs` | `pages/ComponentDoc.jsx` | 本文件渲染页（左侧 240px TOC + 右侧正文） |

## 二、导出面

```js
// 主入口（. 桶）：请求层
import {llmApi, configureLlm} from '@yuku123/z-llm-component'

// 宿主组装面（./pages 桶）：manifest + 页面组件
import {menuItems, routes, HomePage, LoginPage, Provider, Model, ComponentDocPage} from '@yuku123/z-llm-component/pages'

// 组件文档页（主壳接线用，名字全仓统一）
import {ComponentDocPage} from '@yuku123/z-llm-component'
```

## 三、API 端点

请求实例由基座 `createRequest` 工厂创建（`src/services/api.js`），**`baseURL` 默认为空串**
⇒ 下面的路径就是浏览器实际请求的路径（不带 `/api` 前缀），token 走 `Authorization: Bearer`，
token 存在 `localStorage.zllm_token`。
下表路径是 `src/services/api.js` 里 `llmApi` 定义的**实际路径**。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/llm-center/provider/list` | 供应商列表 |
| POST | `/llm-center/provider/create` | 新增供应商 |
| POST | `/llm-center/provider/update` | 更新供应商 |
| POST | `/llm-center/provider/delete` | 删除供应商（**走 body，body 里是 `{id}`**，不是 query） |
| GET | `/llm-center/model/list` | 模型列表 |
| POST | `/llm-center/model/create` | 新增模型 |
| POST | `/llm-center/model/update` | 更新模型 |
| POST | `/llm-center/model/delete` | 删除模型（走 body，body 里是 `{id}`） |

## 四、页面说明

### 4.1 首页

登录后的着陆页。读 `localStorage.userInfo` 显示欢迎语与角色标签，再把 `menuItems`（去掉首页自身）
渲染成入口卡片 —— 菜单加一项，首页卡片自动多一张，不用改页面代码。

### 4.2 供应商

供应商接入配置表。列：名称、编码（Tag）、Base URL、协议（`OPENAI` 兼容 / `NATIVE`，缺省按 `OPENAI` 渲染）、
启用状态。行操作：编辑（弹窗回填表单）、删除（`Popconfirm` 二次确认）。

表单字段：名称 / 编码 / Base URL / 协议 / 启用。

### 4.3 模型

模型定义表。列：模型名、模型编码（Tag）、供应商（`providerId` 映射成供应商名称，
映射不到时显示原始 id）、上下文窗口（千分位）、最大输出、启用状态。行操作同供应商页。

表单字段：模型名 / 模型编码 / 供应商（下拉，选项来自 `/llm-center/provider/list`）/
上下文窗口 / 最大输出 / 启用。

### 4.4 登录

当前是 **mock 模式**：比对一对写死在 `src/pages/LoginPage.jsx` 里的管理员凭据
（明文口令不在本文复述，见源码），成功后写 `localStorage.token` 与 `localStorage.userInfo`，
再 `window.location.href = '/z-llm/home'`。CTC/SSO 尚未接入。

### 4.5 组件文档

本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。

## 五、配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `configureLlm({apiBase})` | `''` | 改写请求实例的 `baseURL`，用于宿主换挂载前缀 |
| token 存储键 | `zllm_token` | `createRequest` 的 `tokenKey`，与登录页写入的键不一致（见已知限制） |
| 供应商协议枚举 | `OPENAI` / `NATIVE` | 新增供应商时的默认协议是 `OPENAI` |

## 六、已知限制

- **token 键不一致**：`src/services/api.js` 声明 `tokenKey: 'zllm_token'`，
  但 `src/pages/LoginPage.jsx` 写的是 `token`，`z-llm-suit` 的 `ProtectedShell` 读的也是 `token`。
  两边对不上 ⇒ 带 `Authorization` 头的请求会拿不到 token（接口返回 401 时会被基座跳登录）。
- **删除接口走 body**：`provider/delete` 与 `model/delete` 都是 `POST` + body `{id}`，
  不是 `DELETE` + query。改调用方式会让后端拿不到 id。
- 列表响应体做了兼容：裸数组与 `{data: [...]}` 两种形态都认（`Array.isArray(res) ? res : res?.data || []`）。
- 两张表都用 antd 原生 `Table`（`pagination={false}`），**没有用基座的 `ListTable`** ——
  所以没有过滤框、没有空态组件，分页也关掉了。
- 两个弹窗都用了 antd 6 已废弃的 `destroyOnClose`（antd6 应为 `destroyOnHidden`）。
- 静态 `message`（antd 6 下会告警，规范要求改 `App.useApp()`）。
- z-llm 组件包**没有**被 z-opc 主壳的 `domainRoutes.ts` 注册，也没被 z-team 的 `SystemShell` 引用，
  目前只有 `z-llm-suit` 独立壳在用 ⇒ `/z-llm/docs` 在平台主壳里**没有可达路径**。
  （平台上的「LLM 管理」走的是 `@yuku123/z-agent-component` 的 `LlmManage`，不是这个包。）

## 七、变更记录

| 版本 | 变更 |
|---|---|
| 0.1.0 | 新增组件文档页 `ComponentDocPage`（lead 008 §7）：`src/DOC.md` + 基座 `ComponentDoc` 薄包装 |
| 0.1.0 | `src/pages.js` 补齐 `HomePage` / `LoginPage` 转发，修掉 suit 构建的 "not exported by" 失败 |
| 0.1.0 | 首版：供应商 + 模型页面集 + manifest + 自洽 request |
