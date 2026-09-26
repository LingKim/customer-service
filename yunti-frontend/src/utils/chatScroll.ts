import { nextTick, onUnmounted, ref, type Ref } from 'vue'

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
 *   <li><b>不能抢用户</b>：坐席往上翻历史时，新消息一到就把人拽到底部，是最招人烦的交互之一。
 *       所以只在"用户本来就在底部附近"时自动滚；否则只挂一个"有新消息 ↓"的提示，让用户自己决定。</li>
 * </ol>
 */

/** 距离底部多少像素以内算"还在底部"（滚轮抖动、亚像素误差都不至于误判） */
const NEAR_BOTTOM_PX = 80

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

  function distanceToBottom(el: HTMLElement) {
    return el.scrollHeight - el.scrollTop - el.clientHeight
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

  onUnmounted(() => cancelAnimationFrame(rafId))

  return { atBottom, hasNewBelow, scrollToBottom, onScroll, jumpToBottom }
}
