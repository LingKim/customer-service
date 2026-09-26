import { request } from '../request'

/**
 * 消息中心（后端）。
 *
 * <p>消息按"租户 + 类型"存，已读按人存：工单 SLA 预警、被分派的工单、
 * 平台回复企业这些都会写成 notification，右上角铃铛读的就是它。</p>
 */

export interface NotificationItem {
  id: string
  /** 1-系统、2-工单、3-审核、4-质检、5-公告、6-账单 */
  notifyType: number
  notifyTypeText: string
  title: string
  content?: string | null
  /** 点击后跳转的页面标识（tickets / platform-support …） */
  linkView?: string | null
  publishTime?: string | null
  read: boolean
}

export function fetchNotifications(params: { notifyType?: number; limit?: number } = {}) {
  return request<NotificationItem[]>({ url: '/customer/notifications', method: 'get', params })
}

export function fetchNotificationUnreadCount(): Promise<{ count: number }> {
  return request<{ count: number }>({ url: '/customer/notifications/unread-count', method: 'get' })
}

export function markNotificationRead(notifyId: string): Promise<void> {
  return request<void>({ url: `/customer/notifications/${notifyId}/read`, method: 'post' })
}

export function markAllNotificationsRead(notifyType?: number): Promise<{ count: number }> {
  return request<{ count: number }>({
    url: '/customer/notifications/read-all',
    method: 'post',
    params: { notifyType },
  })
}
