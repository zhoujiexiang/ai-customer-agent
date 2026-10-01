import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      // 走代理而不是直连 8080：SSE 长连接在同源下不会被浏览器当作跨站请求拦截，
      // 生产环境由 Nginx 承担同样的角色
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  // preview 服务的是 build 之后的真实产物，用于现场演示。
  // 注意 server.proxy 只对 dev server 生效，preview 必须单独声明，
  // 否则演示时接口全部 404——这个坑不写出来一定会踩。
  preview: {
    port: 4173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // 手动分包：Naive UI 全量引入后单文件会到 1.5 MB，
        // 拆开之后业务代码改动不会让用户重新下载整个组件库
        manualChunks: {
          'vendor-vue': ['vue', 'vue-router', 'pinia'],
          'vendor-naive': ['naive-ui'],
          'vendor-markdown': ['marked', 'dompurify']
        }
      }
    }
  }
})
