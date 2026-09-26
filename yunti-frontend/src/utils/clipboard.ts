/**
 * 复制文本到剪贴板。
 *
 * <p>为什么要两条路：`navigator.clipboard` 只在**安全上下文**（https 或 localhost）可用，
 * 而客服挂件经常被嵌在 `http://` 的客户站点里，file:// 打开的演示页也一样。
 * 只写 clipboard API 的话，这些场景会"点了复制没反应"——正是最容易被投诉的那种 bug。</p>
 *
 * <p>兜底用旧的 textarea + execCommand('copy')：虽然 API 被标记废弃，
 * 但所有浏览器仍在支持，是目前唯一能在非安全上下文里生效的办法。</p>
 *
 * @returns 是否复制成功（调用方据此决定提示"已复制"还是"请手动选中复制"）
 */
export async function copyText(text: string): Promise<boolean> {
  const value = text ?? ''
  if (!value) {
    return false
  }
  // 优先用异步剪贴板：支持富文本场景、也不会闪一下临时元素
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(value)
      return true
    } catch {
      // 用户拒绝授权 / 浏览器策略限制：继续走下面的兜底
    }
  }
  return legacyCopy(value)
}

/** 非安全上下文的兜底：造一个看不见的 textarea，选中它再执行复制 */
function legacyCopy(value: string): boolean {
  const area = document.createElement('textarea')
  area.value = value
  // 固定到视口外并防止页面跳动（移动端键盘弹出也会影响布局）
  area.setAttribute('readonly', 'readonly')
  area.style.position = 'fixed'
  area.style.top = '-1000px'
  area.style.left = '-1000px'
  area.style.opacity = '0'
  document.body.appendChild(area)
  try {
    area.select()
    area.setSelectionRange(0, value.length)
    return document.execCommand('copy')
  } catch {
    return false
  } finally {
    document.body.removeChild(area)
  }
}
