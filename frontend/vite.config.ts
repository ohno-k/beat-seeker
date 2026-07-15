import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { writeFileSync } from 'fs'
import { build } from 'esbuild'

const buildVersion = Date.now().toString()

const origin =
    process.env.NODE_ENV === 'production'
        ? 'https://beat-seeker.com'
        : 'http://localhost:5173'

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
    {
      name: 'generate-bookmarklet-js',
      async load() {
        await build({
          entryPoints: ['src/utils/mainBookmarklet.ts'],
          outfile: 'public/bookmarklet.js',
          bundle: true,
          format: 'iife',
          minify: true,
          define: {
            __APP_ORIGIN__: JSON.stringify(origin),
          },
        })
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
