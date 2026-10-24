import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

// 网关统一入口：/api -> http://127.0.0.1:18080
export default defineConfig({
  plugins: [
    vue(),
    Components({
      dts: false,
      resolvers: [ElementPlusResolver({ importStyle: 'css' })]
    })
  ],
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  }
})
