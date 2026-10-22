// pages 桶：宿主（suit / 主壳）按这张脸重组路由。
// HomePage / LoginPage 也必须在这里转发 —— pages-manifest 里已经导出了，
// 但这个桶以前漏了它们，宿主 `import {HomePage, LoginPage} from '.../pages'`
// 会在构建期直接报 "not exported by"（z-llm-suit 构建因此一直是红的）。
export {menuItems, routes, Provider, Model, HomePage, LoginPage, ComponentDocPage} from './pages-manifest.jsx'
export {configureLlm} from './services/api.js'
