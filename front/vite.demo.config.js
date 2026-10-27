import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'
export default defineConfig({ plugins: [vue()], server: { host: '127.0.0.1', port: 18081, strictPort: true, proxy: { '/api': 'http://127.0.0.1:18080' } } })