import { ApiOutlined, HomeOutlined, ReadOutlined, RobotOutlined } from '@ant-design/icons'
import Provider from './pages/Provider'
import Model from './pages/Model'


export {default as Provider} from './pages/Provider'
export {default as Model} from './pages/Model'
import HomePage from './pages/HomePage'
import ComponentDocPage from './pages/ComponentDoc.jsx'

/** 菜单 + 路由清单（lead 008 §10/§14/§16 批量落地）。App 壳在 suit 侧组装。 */
export const appMeta = { title: 'z-llm 大模型中心', short: 'z-llm' }

export const menuItems = [
    { key: '/z-llm/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-llm/providers', label: '供应商', icon: <ApiOutlined /> },
    { key: '/z-llm/models', label: '模型', icon: <RobotOutlined /> },
    { key: '/z-llm/docs', label: '组件文档', icon: <ReadOutlined /> },
]

export const routes = [
    { path: '/z-llm/home', Component: HomePage },
    { path: '/z-llm/providers', Component: Provider },
    { path: '/z-llm/models', Component: Model },
    { path: '/z-llm/docs', Component: ComponentDocPage },
]

export { default as HomePage } from './pages/HomePage'
export { default as LoginPage } from './pages/LoginPage'
// 主壳接线用：文档页统一以 ComponentDocPage 这个名字从主入口导出。
export { default as ComponentDocPage } from './pages/ComponentDoc.jsx'
