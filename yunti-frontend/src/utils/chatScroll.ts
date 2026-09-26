import { nextTick, onUnmounted, ref, watch, type Ref } from 'vue'

/**
 * 聊天区自动滚动（工作台和访客窗口共用）。
 *
 * <p>为什么不能只写"每次有新消息就 scrollTop = scrollHeight"：</p>
 *
 * <ol>
 *   <li><b>时机太早</b>：Vue 的 nextTick 只保证 DOM 更新完成，不保证**布局**已经稳定。
 *       消息插入、气泡换行、正在输入那三个点消失，都会在之后改变容器高度；
 *       早一步设置 scrollTop，结果就是"滚了一点但没到底"，下面那条新消息还露在可视区外。
 *       这里用双 requestAnimationFrame，等这一帧的布局真正算完再滚。</li>
 *   <li><b>图片是"加载完才长高"的</b>：`<img>` 刚插进来时高度接近 0（`loading="lazy"` 还要等
 *       进入可视区才开始下载），双 rAF 那一刻量到的高度不是最终高度，滚出来自然差一截，
 *       下面那部分内容就被挡住了。所以除了 rAF，还要接"内容变高"的信号：在滚动容器上挂一个
 *       **捕获阶段**的 load 监听（img 的 load 不冒泡，但父元素在捕获阶段收得到），
 *       再补几个延时校对，兜住字体、折叠面板这类同样"晚一步才长高"的内容。</li>
 *   <li><b>不能抢用户</b>：坐席往上翻历史时，新消息一到就把人拽到底部，是最招人烦的交互之一。
 *       所以只在"用户本来就在底部附近"时自动滚；否则只挂一个"有新消息 ↓"的提示，让用户自己决定。</li>
 * </ol>
 */

/** 距离底部多少像素以内算"还在底部"（滚轮抖动、亚像素误差都不至于误判） */
const NEAR_BOTTOM_PX = 80

/** 贴底之后的"校对时刻"（毫秒）：图片、字体、折叠面板都是加载完才长高，双 rAF 只保证那一刻的高度 */
const SETTLE_DELAYS_MS = [120, 400, 900, 1800]

export interface ChatScroller {
  /** 当前是否贴着底部 */
  atBottom: Ref<boolean>
  /** 底部有新内容但用户没在底部（用来显示"有新消息"提示） */
  hasNewBelow: Ref<boolean>
  /** 滚到底部；force=true 时无视用户当前位置（自己发言、切换会话用） */
  scrollToBottom: (force?: boolean) => void
  /** 绑定到滚动容器上的 @scroll 处理 */
  onScroll: () => void
  /** 绑定到"有新消息"提示按钮上 */
  jumpToBottom: () => void
}

export function useChatScroll(scroller: Ref<HTMLElement | undefined | null>): ChatScroller {
  const atBottom = ref(true)
  const hasNewBelow = ref(false)
  let rafId = 0
  const settleTimers: number[] = []

  function distanceToBottom(el: HTMLElement) {
    return el.scrollHeight - el.scrollTop - el.clientHeight
  }

  function cancelSettleTimers() {
    settleTimers.splice(0).forEach((id) => window.clearTimeout(id))
  }

  /** 内容长高了（图片加载完、气泡换行）：客户本来就在底部就跟着往下贴，他在看历史就不动他 */
  function pinIfStillAtBottom() {
    const el = scroller.value
    if (!el || !atBottom.value) {
      return
    }
    if (distanceToBottom(el) <= 1) {
      return
    }
    el.scrollTop = el.scrollHeight
    hasNewBelow.value = false
  }

  /**
   * 直接贴到底，并在随后的几个时刻再校对几次。
   *
   * <p>校对要解决的正是"图片消息"：气泡先出现、图片后加载，高度是分两次长出来的。
   * 每次校对都先看客户还在不在底部——他要是这几百毫秒里自己翻上去了，就绝不把他拽下来。</p>
   */
  function pinToBottom() {
    // 双 rAF：第一帧 Vue 把 DOM 改完，第二帧布局算完，这时候再滚才准
    cancelAnimationFrame(rafId)
    void nextTick(() => {
      rafId = requestAnimationFrame(() => {
        rafId = requestAnimationFrame(() => {
          const target = scroller.value
          if (!target) {
            return
          }
          target.scrollTop = target.scrollHeight
          atBottom.value = true
          hasNewBelow.value = false
        })
      })
    })
    cancelSettleTimers()
    SETTLE_DELAYS_MS.forEach((delay) => {
      settleTimers.push(window.setTimeout(pinIfStillAtBottom, delay))
    })
  }

  function scrollToBottom(force = false) {
    const el = scroller.value
    if (!el) {
      return
    }
    if (!force && !atBottom.value) {
      // 用户正在看上面的历史：不打扰，只提示下面有新内容
      hasNewBelow.value = true
      return
    }
    pinToBottom()
  }

  function onScroll() {
    const el = scroller.value
    if (!el) {
      return
    }
    atBottom.value = distanceToBottom(el) <= NEAR_BOTTOM_PX
    if (atBottom.value) {
      hasNewBelow.value = false
    }
  }

  function jumpToBottom() {
    scrollToBottom(true)
  }

  // 滚动容器上挂"内容变高"的信号：<img> 的 load 事件不冒泡，但父元素在捕获阶段收得到。
  // 图片消息就靠它把"长高之后"的位置补回来（`loading="lazy"` 时图加载得晚，更是必须）。
  let bound: HTMLElement | null = null
  const onImageLoaded = () => pinIfStillAtBottom()
  watch(scroller, (el, _previous, onCleanup) => {
    bound?.removeEventListener('load', onImageLoaded, true)
    bound = el ?? null
    bound?.addEventListener('load', onImageLoaded, true)
    onCleanup(() => {
      bound?.removeEventListener('load', onImageLoaded, true)
      bound = null
    })
  }, { immediate: true, flush: 'post' })

  onUnmounted(() => {
    cancelAnimationFrame(rafId)
    cancelSettleTimers()
  })

  return { atBottom, hasNewBelow, scrollToBottom, onScroll, jumpToBottom }
}
