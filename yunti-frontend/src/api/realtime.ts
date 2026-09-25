import { request } from './request'

/** 在线快照：当前租户在线的访客会话号 */
export interface PresenceSnapshot {
onlineSessions: string[]
}

/**
* 查一次在线状态。
*
* <p>走 HTTP 而不是长连接：工作台"客户一直显示在线"的根因就是长连接断了/被后台节流，
* 再用长连接去要状态是绕不出来的。失败时静默（返回 null），由调用方保留现有状态。</p>
*/
export function fetchPresence(): Promise<PresenceSnapshot | null> {
return request<PresenceSnapshot>({
url: '/realtime/presence',
method: 'get',
silent: true,
}).catch(() => null)
}
