import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    AutoImport({ resolvers: [ElementPlusResolver()] }),
    Components({ resolvers: [ElementPlusResolver()] }),
  ],
  server: {
    port: 5173,
    proxy: {
      // EX-C3a：后端端点自带 /api 前缀（/api/auth/login、/api/rca-runs 等），
      // 原样透传不再 rewrite（旧 strip-rewrite 与真端点路径不符）
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  },
  build: { outDir: 'dist', sourcemap: false }
})
