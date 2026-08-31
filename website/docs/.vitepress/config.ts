import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'WeaveMask',
  base: '/WeaveMask/',
  sitemap: {
    hostname: 'https://seyud.github.io/WeaveMask/'
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
        const path = location.pathname
        if (!path.startsWith(base)) return
        const sub = path.slice(base.length)
        const cur = sub.startsWith('zh_CN/') || sub === 'zh_CN'
        let pref = null
        try { pref = localStorage.getItem('lang-pref:/WeaveMask/') } catch (e) {}
        if (pref === 'zh' || pref === 'en') {
          if (pref === 'zh' && !cur) {
            location.replace(base + 'zh_CN/' + sub + location.search + location.hash)
          } else if (pref === 'en' && cur) {
            let rest = sub === 'zh_CN' ? '' : sub.slice(6)
            location.replace(base + rest + location.search + location.hash)
          }
          return
        }
        if (!cur && (navigator.language || '').toLowerCase().startsWith('zh')) {
          location.replace(base + 'zh_CN/' + sub + location.search + location.hash)
        }
      })()
      ;(() => {
        const base = '/WeaveMask/'
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
              if (lang === auto) localStorage.removeItem('lang-pref:/WeaveMask/')
              else localStorage.setItem('lang-pref:/WeaveMask/', lang)
            } catch (err) {}
          }
        }
        // capture 阶段 + pointerdown 双保险：早于扩展对 DOM/事件的包装
        document.addEventListener('click', onPick, true)
        document.addEventListener('pointerdown', onPick, true)
      })()`
    ]
  ]
})
