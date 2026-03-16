import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { writeFileSync } from 'fs'

const buildVersion = Date.now().toString()

// https://vite.dev/config/
export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(buildVersion),
  },
  plugins: [
    vue(),
    {
      name: 'generate-version-json',
      buildStart() {
        writeFileSync('public/version.json', JSON.stringify({ version: buildVersion }))
      },
    },
  ],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
