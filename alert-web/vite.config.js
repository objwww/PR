import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // control-app 默认 8080；后端配套 API（/rca-runs 等）落码后经此代理联调
      '/api': { target: 'http://localhost:8080', changeOrigin: true, rewrite: p => p.replace(/^\/api/, '') }
    }
  },
  build: { outDir: 'dist', sourcemap: false }
})
