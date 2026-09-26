/**
 * 云梯智能客服 · 官网交互脚本（零依赖，原生 JS）
 *
 * 做五件事：
 *   1. 视觉交互：导航滚动变实、板块进场渐显、FAQ 手风琴、年付/月付切换、订阅提示；
 *   2. **价格同步**：`/api/billing/public/plans` 拉后端已发布套餐，
 *      尚未发布套餐时显示待发布提示；
 *   3. **官网配置**：`/api/ops/public/site-config` 覆盖文案（hero / 信任墙 / 功能 / 方案 /
 *      案例 / 数据横幅 / 定价说明 / FAQ / CTA / 页脚），字段契约与平台原型一致；
 *   4. 配置的三种下发方式：`?site=<base64>`（平台预览） > localStorage（已发布） > 后端接口；
 *   5. 入口跳转：登录 / 注册 / 工作台等按钮指向管理端（可用 `?app=` 或
 *      `window.YUNTI_APP_BASE` 覆盖，构建时由 build.mjs 注入）。
 */
(function () {
  'use strict'

  var API_BASE = (document.querySelector('meta[name="yunti-api-base"]') || {}).content || '/api'
  var params = new URLSearchParams(location.search)
  // 管理端地址：默认本地开发端口 5173，部署时用 ?app= / window.YUNTI_APP_BASE 覆盖
  var APP_BASE = params.get('app') || window.YUNTI_APP_BASE || 'http://localhost:5173'
  var CONFIG_KEY = 'yt_site_config_v1'
  var cycle = 'year'
  // 定价卖点前的对勾：与 index.html 静态兜底里的 SVG 完全一致，接口渲染后视觉不跳
  var CHECK_ICON =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg>'

  // ---------------------------------------------------------------- 工具
  function $(sel, root) {
    return (root || document).querySelector(sel)
  }

  function $$(sel, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(sel))
  }

  function get(path, obj) {
    return String(path)
      .split('.')
      .reduce(function (acc, key) {
        return acc == null ? undefined : acc[key]
      }, obj)
  }

  function esc(text) {
    return String(text == null ? '' : text).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]
    })
  }

  function toast(title, text) {
    var wrap = $('#toast-wrap')
    if (!wrap) {
      wrap = document.createElement('div')
      wrap.id = 'toast-wrap'
      wrap.className = 'toast-wrap'
      document.body.appendChild(wrap)
    }
    var el = document.createElement('div')
    el.className = 'toast'
    el.innerHTML = '<b>' + esc(title) + '</b>' + (text ? '<span>' + esc(text) + '</span>' : '')
    wrap.appendChild(el)
    setTimeout(function () {
      el.classList.add('leaving')
      setTimeout(function () {
        el.remove()
      }, 260)
    }, 3200)
  }

  /**
   * 价格展示：数字走千分位、0 显示「免费」；
   * 后端 BigDecimal 有可能按字符串回（"5980.00"），所以纯数字字符串也按数字格式化，
   * 其余字符串（比如「联系我们」）原样展示。
   */
  function money(value) {
    if (value == null || value === '') return '免费'
    var raw = typeof value === 'string' ? value.trim() : value
    if (typeof raw === 'string' && !/^\d+(\.\d+)?$/.test(raw)) return raw
    var num = Number(raw)
    if (!num) return '免费'
    return '¥' + num.toLocaleString('zh-CN', { maximumFractionDigits: 0 })
  }

  function isFree(plan) {
    var value = plan.priceY
    return value == null || value === '' || value === '免费' || Number(value) === 0
  }

  // ---------------------------------------------------------------- 视觉交互
  function initNav() {
    var nav = $('#nav')
    if (!nav) return
    var onScroll = function () {
      nav.classList.toggle('scrolled', window.scrollY > 12)
    }
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
  }

  function initReveal() {
    var items = $$('.reveal')
    if (!('IntersectionObserver' in window)) {
      items.forEach(function (el) {
        el.classList.add('in')
      })
      return
    }
    var observer = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry, index) {
          if (!entry.isIntersecting) return
          entry.target.style.transitionDelay = (index % 3) * 0.06 + 's'
          entry.target.classList.add('in')
          observer.unobserve(entry.target)
        })
      },
      { threshold: 0.12 },
    )
    items.forEach(function (el) {
      observer.observe(el)
    })
  }

  /** FAQ 手风琴：同一时间只展开一条 */
  function initFaq() {
    $$('.faq-item').forEach(function (item) {
      var head = $('.faq-q', item)
      if (!head || head.dataset.bound) return
      head.dataset.bound = '1'
      head.addEventListener('click', function () {
        var open = item.classList.contains('open')
        $$('.faq-item').forEach(function (other) {
          other.classList.remove('open')
        })
        item.classList.toggle('open', !open)
      })
    })
  }

  function initCycleToggle() {
    var buttons = $$('.pt-btn')
    if (!buttons.length) return
    buttons.forEach(function (btn) {
      btn.addEventListener('click', function () {
        buttons.forEach(function (other) {
          other.classList.toggle('active', other === btn)
        })
        cycle = btn.dataset.cycle || 'year'
        applyCycle()
      })
    })
  }

  /** 切换年付/月付：只换价格与单位，功能列表不动（年付时按原型展示划线原价） */
  function applyCycle() {
    $$('#price-grid .plan').forEach(function (card) {
      var holder = $('[data-price-y]', card)
      if (!holder) return
      var value = cycle === 'month' ? holder.dataset.priceM : holder.dataset.priceY
      var label = $('[data-price-val]', card)
      var per = $('small', holder)
      if (label && value != null) label.textContent = value
      if (per) per.textContent = cycle === 'month' ? '/月' : '/年'
    })
  }

  function initSubscribe() {
    var form = $('#subscribe-form')
    if (!form) return
    form.addEventListener('submit', function (event) {
      event.preventDefault()
      var email = (form.elements.email.value || '').trim()
      if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
        toast('邮箱格式不正确', '请输入有效的工作邮箱')
        return
      }
      // 官网是静态站点：这里先本地留档并提示，正式订阅名单由运营侧接口承接
      try {
        var list = JSON.parse(localStorage.getItem('yt_site_subscribers_v1') || '[]')
        if (list.indexOf(email) < 0) list.push(email)
        localStorage.setItem('yt_site_subscribers_v1', JSON.stringify(list))
      } catch (e) {
        /* 忽略本地存储异常 */
      }
      toast('仅本地记录', '当前没有订阅服务，邮箱不会被提交。')
      form.reset()
    })
  }

  // ---------------------------------------------------------------- 定价（真实套餐）

  /** 从后台套餐字段推导卖点文案（配额以 plan 表为准，官网不写死数字） */
  function planFeatures(plan) {
    var feats = []
    feats.push(numberText(plan.quotaRobotSessions) + ' 次/月机器人会话')
    feats.push(plan.quotaChannels + ' 个渠道 · ' + plan.quotaSeats + ' 个坐席')
    if (plan.quotaAiQa) {
      feats.push('全量 AI 质检' + (plan.quotaVision ? ' + 视觉识别（图片判读）' : ''))
    } else if (plan.quotaAiCalls) {
      feats.push(numberText(plan.quotaAiCalls) + ' 次/月 AI 调用')
    }
    if (plan.overagePricePerK) {
      feats.push('超量按 ¥' + plan.overagePricePerK + '/千次计费')
    } else if (plan.quotaStorageGb) {
      feats.push(plan.quotaStorageGb + ' GB 知识库存储')
    }
    return feats
  }

  function numberText(value) {
    var num = Number(value || 0)
    return num >= 1000 ? num.toLocaleString('zh-CN') : String(num)
  }

  function fromApi(plan) {
    return {
      code: plan.planCode,
      name: plan.planName,
      desc: plan.planCode === 'FREE' ? '注册即试用，额度用完再升级' : plan.planName + ' · 按需升级',
      priceY: plan.priceYearly,
      priceM: plan.priceMonthly,
      feats: planFeatures(plan),
      cta: plan.planCode === 'FREE' ? '免费开始' : plan.planCode === 'ENTERPRISE' ? '联系我们' : '立即开通',
      hot: plan.planCode === 'PROFESSIONAL',
    }
  }

  function fromConfig(plan, index) {
    return {
      code: plan.code || 'CFG_' + index,
      name: plan.name,
      desc: plan.desc || '',
      priceY: plan.priceY,
      priceM: plan.priceM,
      feats: plan.feats || [],
      cta: plan.cta || '立即开通',
      hot: !!plan.hot,
    }
  }

  function renderPlans(plans) {
    var grid = $('#price-grid')
    if (!grid || !plans.length) return
    grid.innerHTML = plans
      .map(function (plan) {
        var free = isFree(plan)
        var priceHtml = free
          ? '<div class="price free"><span data-price-val>' + esc(money(plan.priceY)) + '</span></div>'
          : '<div class="price" data-price-y="' +
            esc(money(plan.priceY)) +
            '" data-price-m="' +
            esc(money(plan.priceM)) +
            '"><span data-price-val>' +
            esc(money(plan.priceY)) +
            '</span><small>/年</small></div>'
        return (
          '<div class="plan' +
          (plan.hot ? ' hot' : '') +
          '" data-plan-code="' +
          esc(plan.code) +
          '">' +
          (plan.hot ? '<div class="rib">最受欢迎</div>' : '') +
          '<h4>' +
          esc(plan.name) +
          '</h4>' +
          '<div class="desc">' +
          esc(plan.desc) +
          '</div>' +
          priceHtml +
          '<ul>' +
          plan.feats
            .map(function (text) {
              return '<li>' + CHECK_ICON + esc(text) + '</li>'
            })
            .join('') +
          '</ul>' +
          '<a class="btn ' +
          (plan.hot ? 'btn-primary' : 'btn-outline') +
          ' btn-block" href="#" data-app-link="/register">' +
          esc(plan.cta) +
          '</a>' +
          '</div>'
        )
      })
      .join('')
    bindAppLinks()
    applyCycle()
  }

  /**
   * 仅使用后台已发布套餐。当前计费骨架返回空列表。
   */
  function syncPlans() {
    fetch(API_BASE + '/billing/public/plans', { headers: { Accept: 'application/json' } })
      .then(function (res) { return res.ok ? res.json() : null })
      .then(function (body) {
        if (body && body.code === 0 && Array.isArray(body.data) && body.data.length) {
          renderPlans(body.data.map(fromApi))
        }
      })
      .catch(function () { /* 保留尚未发布套餐的明确提示 */ })
  }

  // ---------------------------------------------------------------- 官网配置

  function decodeSiteParam(raw) {
    try {
      var b64 = String(raw).replace(/-/g, '+').replace(/_/g, '/')
      return JSON.parse(decodeURIComponent(escape(atob(b64))))
    } catch (e) {
      return null
    }
  }

  function applySiteConfig(cfg) {
    if (!cfg || typeof cfg !== 'object') return

    // 1) 标量文案：hero / 功能 / 方案 / 案例 / 数据横幅 / 定价说明 / CTA / 页脚
    $$('[data-cfg]').forEach(function (el) {
      var value = get(el.dataset.cfg, cfg)
      if (value != null && value !== '' && typeof value !== 'object') el.textContent = value
    })

    // 2) 信任 Logo 墙：逗号分隔字符串或数组都能吃
    var trust = cfg.trust || {}
    if (trust.brands) {
      var brands = Array.isArray(trust.brands) ? trust.brands : String(trust.brands).split(/[,，]/)
      var row = $('#trust-brands')
      if (row) {
        row.innerHTML = brands
          .map(function (name) {
            return String(name).trim()
          })
          .filter(Boolean)
          .map(function (name) {
            return '<span>' + esc(name) + '</span>'
          })
          .join('')
      }
    }

    // 3) Hero 统计：兼容 [{v,l}] 与 [{value,label}] 两种字段名
    var stats = (cfg.hero && cfg.hero.stats) || cfg.stats
    if (Array.isArray(stats) && stats.length) {
      var box = $('#hero-stats')
      if (box) {
        box.innerHTML = stats
          .map(function (item) {
            var value = item.v != null ? item.v : item.value
            var label = item.l != null ? item.l : item.label
            return '<div><b>' + esc(value) + '</b><span>' + esc(label) + '</span></div>'
          })
          .join('')
      }
    }

    // 4) FAQ：条数可变，重建后重新绑定手风琴
    var faq = cfg.faq || {}
    if (Array.isArray(faq.items) && faq.items.length) {
      var list = $('#faq-list')
      if (list) {
        list.innerHTML = faq.items
          .map(function (item) {
            return (
              '<div class="faq-item"><div class="faq-q">' +
              esc(item.q) +
              '<span class="pm">＋</span></div><div class="faq-a">' +
              esc(item.a) +
              '</div></div>'
            )
          })
          .join('')
        initFaq()
      }
    }
  }

  /** 拉官网配置：返回 Promise<配置对象|null>，失败静默（页面用默认文案） */
  function syncSiteConfig() {
    var fromQuery = params.get('site') ? decodeSiteParam(params.get('site')) : null
    if (fromQuery) {
      applySiteConfig(fromQuery)
      return Promise.resolve(fromQuery)
    }
    try {
      var cached = localStorage.getItem(CONFIG_KEY)
      if (cached) {
        var parsed = JSON.parse(cached)
        applySiteConfig(parsed)
        return Promise.resolve(parsed)
      }
    } catch (e) {
      /* 缓存坏了就继续走接口 */
    }
    return fetch(API_BASE + '/ops/public/site-config', { headers: { Accept: 'application/json' } })
      .then(function (res) {
        return res.ok ? res.json() : null
      })
      .then(function (body) {
        if (body && body.code === 0 && body.data) {
          applySiteConfig(body.data)
          return body.data
        }
        return null
      })
      .catch(function () {
        return null
      })
  }

  // ---------------------------------------------------------------- 入口链接
  function bindAppLinks() {
    $$('[data-app-link]').forEach(function (el) {
      var path = el.dataset.appLink
      // 部署时如果已经把 href 写成生产绝对地址，就不再覆盖
      if (/^https?:/.test(el.getAttribute('href') || '')) return
      el.setAttribute('href', APP_BASE.replace(/\/$/, '') + path)
    })
  }

  // ---------------------------------------------------------------- 启动
  document.addEventListener('DOMContentLoaded', function () {
    initNav()
    initReveal()
    initFaq()
    initCycleToggle()
    initSubscribe()
    bindAppLinks()
    syncSiteConfig()
    syncPlans()
  })
})()
