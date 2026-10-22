import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
    plugins: [react()],
    build: {
        lib: {entry: {index: path.resolve(__dirname, 'src/index.js'), pages: path.resolve(__dirname, 'src/pages.js')}, formats: ['es']},
        outDir: 'dist', emptyOutDir: true,
        // 基座子路径必须逐条 external：rollup 的 external 是精确字符串匹配，不是前缀匹配。
        // 只写 '@yuku123/z-frontend-common' 时，'@yuku123/z-frontend-common/ui' 会被打进本包 dist，
        // 基座组件就多了一份副本 —— 破坏「共享基座单一真源」，且包体膨胀。
        rollupOptions: {external: ['react', 'react-dom', 'react-dom/client', 'react-router-dom', 'antd', '@ant-design/icons', 'axios', '@yuku123/z-frontend-common', '@yuku123/z-frontend-common/ui', '@yuku123/z-frontend-common/ui/echarts']},
    },
})
