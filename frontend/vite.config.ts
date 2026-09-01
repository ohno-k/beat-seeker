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
      // eagate 上で実行するスクリプトを 2 種類生成する。収集処理の中核（src/utils/eagateScraper.ts）は
      // 両者で共通で、エントリだけが「ブックマークレット」「Android アプリの WebView」で分かれる。
      name: 'generate-eagate-scripts',
      async load() {
        // ブックマークレット本体（/bookmarklet.js）
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
        // Android アプリが実行時に読み込んで注入するスクリプト（/native-scraper.js）。
        // アプリに同梱せず配信することで、eagate の HTML 変更にアプリ再リリース無しで追従する。
        await build({
          entryPoints: ['src/utils/nativeScraper.ts'],
          outfile: 'public/native-scraper.js',
          bundle: true,
          format: 'iife',
          minify: true,
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
