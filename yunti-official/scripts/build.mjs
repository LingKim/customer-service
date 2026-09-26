/**
 * 官网构建（零依赖）：把站点复制到 dist/，并按环境变量注入 API 前缀。
 *
 *   node scripts/build.mjs
 *   YUNTI_API_BASE=https://api.yunti.example.com/api node scripts/build.mjs
 *
 * 产物 dist/ 是纯静态文件，直接丢 CDN / nginx 即可（见 Dockerfile 与 README）。
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const DIST = path.join(ROOT, 'dist')
const API_BASE = process.env.YUNTI_API_BASE || '/api'
const APP_BASE = process.env.YUNTI_APP_BASE || ''

fs.rmSync(DIST, { recursive: true, force: true })
fs.mkdirSync(DIST, { recursive: true })

function copyDir(from, to) {
  for (const entry of fs.readdirSync(from, { withFileTypes: true })) {
    if (from === ROOT && !['index.html', 'demo.html', 'legal.html', 'assets'].includes(entry.name)) continue
    const src = path.join(from, entry.name)
    const dest = path.join(to, entry.name)
    if (entry.isDirectory()) {
      fs.mkdirSync(dest, { recursive: true })
      copyDir(src, dest)
    } else {
      fs.copyFileSync(src, dest)
    }
  }
}

copyDir(ROOT, DIST)

// 注入 API 前缀与管理端地址：改一处，全站生效
const indexFile = path.join(DIST, 'index.html')
let html = fs.readFileSync(indexFile, 'utf8')
html = html.replace(
  /<meta name="yunti-api-base" content="[^"]*" \/>/,
  `<meta name="yunti-api-base" content="${API_BASE.replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;')}" />`,
)
if (APP_BASE) {
  html = html.replace(
    '<script src="assets/js/site.js" defer></script>',
    `<script>window.YUNTI_APP_BASE = ${JSON.stringify(APP_BASE).replaceAll('<', '\\u003c')};</script>\n<script src="assets/js/site.js" defer></script>`,
  )
}
fs.writeFileSync(indexFile, html, 'utf8')

const files = fs.readdirSync(DIST, { recursive: true }).length
console.log(`构建完成：${DIST}`)
console.log(`  API 前缀：${API_BASE}${APP_BASE ? ` · 管理端：${APP_BASE}` : ''}`)
console.log(`  文件数：${files}`)
