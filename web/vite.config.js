import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'
import { createReadStream, existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { extname, join, normalize } from 'node:path'

// 小程序的静态资源目录（数据库里的 /assets/xxx 都指向这里）
const ASSETS_ROOT = fileURLToPath(new URL('../assets', import.meta.url))
const API_TARGET = process.env.CAMPUS_API_PROXY_TARGET || process.env.VITE_API_PROXY_TARGET || process.env.API_PROXY_TARGET || 'http://127.0.0.1:8080'
// WebSocket 代理（圈子群聊）：ws 协议需单独声明
const WS_TARGET = API_TARGET.replace(/^http/, 'ws')

const MIME = {
  '.webp': 'image/webp',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml'
}

/**
 * 让管理端也能访问 /assets/**。
 *
 * 为什么需要：数据库里图片存的是 `/assets/social/xxx.webp`，文件在小程序目录下。
 * Vite 默认把未匹配路径当 SPA 路由返回 index.html（Content-Type: text/html），
 * 浏览器拿到 HTML 当图片解码 → 全部显示成破图。
 * 这里加中间件：命中真实文件就返回文件流，不再走 SPA 回退。
 */
function serveMiniappAssets() {
  return {
    name: 'serve-miniapp-assets',

    configureServer(server) {
      server.middlewares.use('/assets', (req, res, next) => {
        const urlPath = decodeURIComponent((req.url || '').split('?')[0])
        const safePath = normalize(urlPath).replace(/^(\.\.[/\\])+/, '')
        const filePath = join(ASSETS_ROOT, safePath)

        // 路径穿越保护 + 必须是真的文件
        if (!filePath.startsWith(ASSETS_ROOT) || !existsSync(filePath) || !statSync(filePath).isFile()) {
          return next()
        }

        res.setHeader('Content-Type', MIME[extname(filePath).toLowerCase()] || 'application/octet-stream')
        res.setHeader('Cache-Control', 'no-cache')
        createReadStream(filePath).pipe(res)
      })
    },

    // 生产构建时把 assets 一起拷进 dist，保证打包后图片也能用
    generateBundle() {
      if (!existsSync(ASSETS_ROOT)) return
      const walk = (dir, prefix) => {
        for (const name of readdirSync(dir)) {
          const full = join(dir, name)
          if (statSync(full).isDirectory()) {
            walk(full, `${prefix}${name}/`)
          } else {
            this.emitFile({
              type: 'asset',
              fileName: `assets/${prefix}${name}`,
              source: readFileSync(full)
            })
          }
        }
      }
      walk(ASSETS_ROOT, '')
    }
  }
}

export default defineConfig({
  plugins: [vue(), serveMiniappAssets()],
  server: {
    port: 5180,
    // 后端接口代理：管理端与 API 同源，避免开发期 CORS 与 cookie 问题
    proxy: {
      '/api': { target: API_TARGET, changeOrigin: true },
      '/uploads': { target: API_TARGET, changeOrigin: true },
      // 圈子群聊 WebSocket（task-16）
      '/ws': { target: WS_TARGET, ws: true, changeOrigin: true }
    }
  }
})
