import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitepress'

// Hero logo 内联为 data URI：图片随 HTML 一起到达，不再单独走一个被 GitHub Pages
// 限速的请求（线上实测 LCP P75 = 6.1s 就是耗在这张图上）。
// SVG 由 tmp/logo-svg/ 从原 1249px PNG 逐块临摹生成（MAE 2.93，噪声底 2.14），1.5KB。
const LOGO_SVG = readFileSync(fileURLToPath(new URL('../public/logo.svg', import.meta.url)))
const LOGO_DATA_URI = `data:image/svg+xml;base64,${LOGO_SVG.toString('base64')}`

export default defineConfig({
  title: 'WeaveMask',
  base: '/WeaveMask/',
  sitemap: {
    hostname: 'https://seyud.github.io/WeaveMask/'
  },
  // 首页 hero 图内联：构建时把 frontmatter 里的 /logo.svg 替换为 data URI，
  // img 与 HTML 同连接到达，消除单独请求（withBase 对 data: 原样放行）。
  transformPageData(pageData) {
    const fm: any = pageData.frontmatter
    const img = fm?.hero?.image
    if (
      fm?.layout === 'home' &&
      img &&
      typeof img === 'object' &&
      typeof img.src === 'string' &&
      img.src.includes('/logo.')
    ) {
      img.src = LOGO_DATA_URI
    }
  },
  locales: {
    root: {
      label: 'English',
      lang: 'en-US',
      description: 'A Magisk fork with enhanced features for Android.',
      themeConfig: {
        nav: [
          { text: 'Guide', link: '/guide/what-is-weavemask' }
        ],
        lastUpdatedText: 'Last updated',
        sidebar: {
          '/guide/': [
            {
              text: 'Guide',
              items: [
                { text: 'What is WeaveMask?', link: '/guide/what-is-weavemask' },
                { text: 'Installation', link: '/guide/installation' },
                { text: 'FAQ', link: '/guide/faq' },
                { text: 'OTA Upgrade', link: '/guide/ota' },
                { text: 'Changelog', link: '/guide/changes' }
              ]
            },
            {
              text: 'Developer',
              items: [
                { text: 'Building', link: '/guide/build' },
                { text: 'Developer Guides', link: '/guide/guides' },
                { text: 'Magisk Tools', link: '/guide/tools' },
                { text: 'Internal Details', link: '/guide/details' },
                { text: 'Android Booting', link: '/guide/boot' },
                { text: 'App Changelog', link: '/guide/app_changes' }
              ]
            }
          ]
        },
        socialLinks: [
          { icon: 'github', link: 'https://github.com/Seyud/WeaveMask' }
        ],
        footer: {
          message: 'Released under the GPL3 License.',
          copyright: 'Copyright © 2024-present WeaveMask developers.'
        },
        editLink: {
          pattern: 'https://github.com/Seyud/WeaveMask/edit/main/website/docs/:path',
          text: 'Edit this page on GitHub'
        }
      }
    },
    zh_CN: {
      label: '简体中文',
      lang: 'zh-CN',
      description: '一个增强版的 Magisk 分支，适用于 Android。',
      themeConfig: {
        nav: [
          { text: '指南', link: '/zh_CN/guide/what-is-weavemask' }
        ],
        lastUpdatedText: '最后更新',
        sidebar: {
          '/zh_CN/guide/': [
            {
              text: '指南',
              items: [
                { text: '什么是 WeaveMask？', link: '/zh_CN/guide/what-is-weavemask' },
                { text: '安装', link: '/zh_CN/guide/installation' },
                { text: '常见问题', link: '/zh_CN/guide/faq' },
                { text: 'OTA 升级', link: '/zh_CN/guide/ota' },
                { text: '更新日志', link: '/zh_CN/guide/changes' }
              ]
            },
            {
              text: '开发者',
              items: [
                { text: '构建', link: '/zh_CN/guide/build' },
                { text: '开发者指南', link: '/zh_CN/guide/guides' },
                { text: 'Magisk 工具', link: '/zh_CN/guide/tools' },
                { text: '内部细节', link: '/zh_CN/guide/details' },
                { text: 'Android 启动', link: '/zh_CN/guide/boot' },
                { text: '应用更新日志', link: '/zh_CN/guide/app_changes' }
              ]
            }
          ]
        },
        socialLinks: [
          { icon: 'github', link: 'https://github.com/Seyud/WeaveMask' }
        ],
        footer: {
          message: '在 GPL3 许可证下发布。',
          copyright: 'Copyright © 2024-现在 WeaveMask 开发者。'
        },
        editLink: {
          pattern: 'https://github.com/Seyud/WeaveMask/edit/main/website/docs/:path',
          text: '在 GitHub 中编辑本页'
        }
      }
    }
  },
  head: [
    ['link', { rel: 'icon', type: 'image/png', href: '/WeaveMask/logo.png' }],
    // Cloudflare Web Analytics
    [
      'script',
      {
        type: 'module',
        src: 'https://static.cloudflareinsights.com/beacon.min.js',
        'data-cf-beacon': JSON.stringify({ token: '7a60b306ee8f46f38f2699681b26453e' })
      }
    ],
    [
      'script',
      {},
      `;(() => {
        const base = '/WeaveMask/'
        const KEY = 'lang-pref:/WeaveMask/'
        const LAST = 'lang-last:/WeaveMask/'
        const FLAG = 'lang-redir:/WeaveMask/'
        const path = location.pathname
        if (!path.startsWith(base)) return
        const sub = path.slice(base.length)
        const cur = sub.startsWith('zh_CN/') || sub === 'zh_CN'
        const auto = (navigator.language || '').toLowerCase().startsWith('zh') ? 'zh' : 'en'
        let pref = null
        try { pref = localStorage.getItem(KEY) } catch (e) {}
        try {
          // 跨语言落地检测：站内语言链接跳转而来（referrer 同源、非前进/后退）
          // 即使用户的点击事件被翻译类扩展拦截，原生导航仍会发生，此检测兜底
          const last = sessionStorage.getItem(LAST)
          if (sessionStorage.getItem(FLAG) !== null) {
            sessionStorage.removeItem(FLAG)
          } else if (last !== null && last !== (cur ? 'zh' : 'en')) {
            const nav = performance.getEntriesByType('navigation')[0]
            const ref = document.referrer
            const internal = !!ref && new URL(ref).origin === location.origin
            if (internal && (!nav || nav.type !== 'back_forward')) {
              const now = cur ? 'zh' : 'en'
              if (now === auto) { localStorage.removeItem(KEY); pref = null }
              else { localStorage.setItem(KEY, now); pref = now }
            }
          }
          sessionStorage.setItem(LAST, cur ? 'zh' : 'en')
        } catch (e) {}
        const go = u => {
          try { sessionStorage.setItem(FLAG, '1') } catch (e) {}
          location.replace(u)
        }
        if (pref === 'zh' || pref === 'en') {
          if (pref === 'zh' && !cur) {
            go(base + 'zh_CN/' + sub + location.search + location.hash)
          } else if (pref === 'en' && cur) {
            let rest = sub === 'zh_CN' ? '' : sub.slice(6)
            go(base + rest + location.search + location.hash)
          }
          return
        }
        if (!cur && auto === 'zh') {
          go(base + 'zh_CN/' + sub + location.search + location.hash)
        }
      })()
      ;(() => {
        const base = '/WeaveMask/'
        const KEY = 'lang-pref:/WeaveMask/'
        const onPick = e => {
          const a = e.target && e.target.closest ? e.target.closest('a') : null
          if (!a) return
          const href = a.getAttribute('href') || ''
          let sub = null
          if (href.startsWith(base)) sub = href.slice(base.length)
          else {
            try {
              const u = new URL(href, location.origin)
              if (u.origin === location.origin && u.pathname.startsWith(base)) sub = u.pathname.slice(base.length)
            } catch (err) {}
          }
          if (sub === null) return
          const cur = location.pathname.slice(base.length)
          const t = sub.startsWith('zh_CN/') || sub === 'zh_CN'
          const c = cur.startsWith('zh_CN/') || cur === 'zh_CN'
          if (t !== c) {
            const lang = t ? 'zh' : 'en'
            const auto = (navigator.language || '').toLowerCase().startsWith('zh') ? 'zh' : 'en'
            try {
              if (lang === auto) localStorage.removeItem(KEY)
              else localStorage.setItem(KEY, lang)
            } catch (err) {}
          }
        }
        // window 捕获阶段注册早于一切内容脚本，先于扩展的事件拦截
        window.addEventListener('click', onPick, true)
        window.addEventListener('pointerdown', onPick, true)
      })()`
    ]
  ]
})
