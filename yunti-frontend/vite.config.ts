import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  // 默认走本地网关 9090；联调不同后端时可用 VITE_PROXY_TARGET 覆盖
  const apiTarget = env.VITE_PROXY_TARGET || 'http://127.0.0.1:9090'
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        // 开发环境代理到后端服务；生产环境由 API 网关统一转发
        '/api/user': {
          target: apiTarget,
          changeOrigin: true,
        },
        '/api/tenant': {
          target: apiTarget,
          changeOrigin: true,
        },
        '/api/customer': {
          target: apiTarget,
          changeOrigin: true,
        },
      },
    },
  }
})
