import { request } from '../request'

/** 会话状态：1-排队中、2-机器人接待、3-人工接待、4-已结束 */
export interface SessionItem {
  sessionNo: string
  status: number
  agentId?: number | null
  channelId?: number | null
  customerId?: number | null
  customerName?: string | null
  customerLevel?: number | null
  source?: string | null
  intent?: string | null
  emotion?: string | null
  startTime?: string
  endTime?: string
  lastContent?: string | null
  lastSenderType?: number | null
  lastTime?: string | null
  msgCount?: number | null
}

/** 消息：senderType 1-客户、2-坐席、3-机器人、4-系统 */
export interface SessionMessageItem {
  msgId: string
  msgNo: string
  sessionId?: number
  senderType: number
  senderId?: number | null
  msgType: number
  content: string
  /** 可见范围：1-客户与坐席都可见、2-仅坐席可见（内部备注） */
  visibleTo?: number
  sendTime: string
}

/** 访客开会话结果 */
export interface OpenSessionResult {
  sessionNo: string
  customerNo: string
  customerName: string
  /** 会话令牌：连长连接用（短期，绑定当前会话） */
  visitorToken: string
  /** 访客身份令牌：本地保存，下次打开时回传，服务端据此认出"还是这个客户" */
  visitorIdentityToken: string
  tokenExpireAt: string
  sessionStatus: number
  created: boolean
}

  /** 坐席会话列表：scope 取值 queue-待接待、mine-我的会话、all-全部 */
export function listSessions(params: {
  scope?: string
  keyword?: string
} = {}): Promise<SessionItem[]> {
  return request<SessionItem[]>({
    url: '/customer/sessions',
    method: 'get',
    params,
  })
}

/** 会话流转记录 */
export interface SessionEventItem {
eventType: number
operatorId?: number | null
fromValue?: string | null
toValue?: string | null
remark?: string | null
eventTime?: string
}

export function listSessionEvents(sessionNo: string): Promise<SessionEventItem[]> {
return request<SessionEventItem[]>({
url: `/customer/sessions/${sessionNo}/events`,
method: 'get',
})
}

/** 会话详情 */
export function getSessionDetail(sessionNo: string): Promise<SessionItem> {
  return request<SessionItem>({
    url: `/customer/sessions/${sessionNo}`,
    method: 'get',
  })
}

/** 聊天记录（beforeId 用于向上翻页） */
export function listSessionMessages(
  sessionNo: string,
  params: { beforeId?: string; limit?: number } = {},
): Promise<SessionMessageItem[]> {
  return request<SessionMessageItem[]>({
    url: `/customer/sessions/${sessionNo}/messages`,
    method: 'get',
    params,
  })
}

/** 访客用渠道密钥开会话（无需登录） */
export function openVisitorSession(data: {
  appKey: string
/** 上次打开时拿到的访客身份令牌（由服务端签发，不要自己拼客户编号） */
visitorToken?: string
  visitorName?: string
}): Promise<OpenSessionResult> {
  return request<OpenSessionResult>({
    url: '/customer/sessions/open',
    method: 'post',
    data,
    silent: true,
  })
}
