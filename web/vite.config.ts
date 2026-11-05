import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// PRD feedback-web-admin §2.2：开发期 /api 代理到本地后端（8080），生产由 Nginx 同源反代
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.spec.ts'],
  },
})
