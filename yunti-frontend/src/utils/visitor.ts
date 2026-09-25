/**
* 访客测试入口：把"用哪个渠道、用哪个访客"拼成一个访客页地址。
*
* <p>访客身份存在浏览器本地（localStorage 里的身份令牌），所以同一个浏览器
* 反复打开同一个渠道，认出来的都是同一个客户；想模拟一个新客户，就带
* {@code fresh=1}，访客页会先清掉本地身份再开会话。为了让坐席在待接待列表里
* 能分清多条测试会话，新访客的访客名会带一个随机后缀。</p>
*/

export interface VisitorTestChannel {
name: string
appKey?: string | null
}

export interface VisitorTestOptions {
/** true：每次都当新访客（换客户）；false：复用同一个访客 */
fresh?: boolean
}

/** 随机后缀：4 位大写字母数字，用来区分不同的测试访客 */
function randomSuffix() {
return Math.random().toString(36).slice(2, 6).toUpperCase()
}

/** 拼访客测试地址；渠道没有密钥时返回空串，调用方据此提示 */
export function visitorTestUrl(channel: VisitorTestChannel, options: VisitorTestOptions = {}) {
if (!channel?.appKey) {
return ''
}
const params = new URLSearchParams({ appId: channel.appKey })
params.set('name', options.fresh ? `测试访客-${channel.name}-${randomSuffix()}` : `测试访客-${channel.name}`)
if (options.fresh) {
params.set('fresh', '1')
}
return `${window.location.origin}/visitor?${params.toString()}`
}

/** 新标签页打开访客测试页 */
export function openVisitorTestTab(channel: VisitorTestChannel, options: VisitorTestOptions = {}) {
const url = visitorTestUrl(channel, options)
if (!url) {
return false
}
window.open(url, '_blank', 'noopener')
return true
}
