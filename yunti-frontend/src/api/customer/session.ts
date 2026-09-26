import { request } from '../request'

/** 会话状态：1-排队中、2-机器人接待、3-人工接待、4-已结束 */
export interface SessionItem {
  sessionNo: string
  status: number
  agentId?: string | null
  channelId?: string | null
  customerId?: string | null
  customerName?: string | null
  customerLevel?: number | null
  customerNo?: string | null
  source?: string | null
  intent?: string | null
  emotion?: string | null
  /** 机器人转人工的原因（客户情绪激动 / 答不上来 / 命中转人工意图等） */
  botTransferReason?: string | null
  startTime?: string
  endTime?: string
  lastContent?: string | null
  lastSenderType?: number | null
  lastTime?: string | null
  msgCount?: number | null
}

export interface SessionWorkload {
  mine: number
  queue: number
}

export function fetchSessionWorkload(): Promise<SessionWorkload> {
  return request({ url: '/customer/sessions/summary', method: 'get' })
}

/** 一条消息里的一张图（多张时挂在 `images` 里） */
export interface ChatImageItem {
  fileId?: string
  url?: string
  name?: string
  size?: number
}

/**
 * 图片消息的正文结构（msgType=2）。
 *
 * `fileId / url / name / size / fileIds / images / text` 是前端发消息时写进去的；
 * `ai*` 那几个是**后端识别完之后回填**的（所以一条图片消息会先到一次、识别完再更新一次）。
 *
 * 一张图时顶层 `fileId/url` 就够了（老消息长的就是这个样子）；**多张图（最多 3 张）**
 * 顶层仍然写第一张（老代码/老消息渲染都不至于瞎），完整清单在 `images` 与 `fileIds` 里。
 */
export interface ImageMessageContent extends ChatImageItem {
  fileIds?: string[]
  /** 这条消息里的全部图片（最多 3 张，顺序就是客户选图的顺序） */
  images?: ChatImageItem[]
  /**
   * 客户随图说的那句话（可以为空）。
   *
   * 大厂客服的图片消息都是"文字 + 图片"一条消息：客户先打字、再选图，点发送才一起发出去。
   * 这句话不是装饰——服务端会把它当作"客户的问题"交给视觉模型（识别时带上它更准）
   * 和客服大脑（意图识别、知识库检索都要用它）。
   */
  text?: string
  /** AI 一句话判读（"这是一张支付失败截图"） */
  aiSummary?: string
  /** 图里的文字（OCR 原文） */
  aiOcrText?: string
  aiOrderNo?: string
  aiAmount?: string
  aiErrorText?: string
  /** 模型建议人工介入（退款失败 / 支付异常 / 投诉这类敏感截图），坐席端会打标 */
  aiNeedHuman?: boolean
  aiAvailable?: boolean
  aiModel?: string
}

/** 解析图片消息正文；解析失败返回 null（老消息 / 脏数据都不会把页面搞崩） */
export function parseImageContent(content?: string | null): ImageMessageContent | null {
  if (!content) {
    return null
  }
  try {
    const parsed = JSON.parse(content) as ImageMessageContent
    return parsed && (parsed.url || parsed.fileId) ? parsed : null
  } catch {
    return null
  }
}

/**
 * 一条图片消息里的所有图片（最多 3 张）。
 *
 * 老消息只有顶层 `fileId/url` 一个字段，新消息多张时清单在 `images` 里——
 * 页面渲染、图片预览、复制都得走这里，别自己直接读 `content.url`（多图会只显示第一张）。
 */
export function imagesOf(message: { msgType?: number | null; content?: string | null }): ChatImageItem[] {
  const image = message.msgType === 2 ? parseImageContent(message.content) : null
  if (!image) {
    return []
  }
  const many = (image.images || []).filter((item) => item && (item.url || item.fileId))
  if (many.length) {
    return many
  }
  return image.url || image.fileId ? [image] : []
}

/**
 * 一条消息"给人看"的文本（复制、导出会话记录用）。
 *
 * 图片消息的正文是一段 JSON，直接复制出去就是 `{"fileId":"22...","url":"..."}`，
 * 粘到工单里没法看；所以图片消息取"随图说的那句话"，客户没写字就退回图片地址。
 */
export function messageTextOf(message: { msgType?: number | null; content?: string | null }): string {
  const image = message.msgType === 2 ? parseImageContent(message.content) : null
  if (!image) {
    return message.content || ''
  }
  const urls = imagesOf(message).map((item) => item.url).filter(Boolean)
  return (image.text || '').trim() || urls.join(' ') || image.url || image.name || '[图片]'
}

/** 聊天图片上传结果 */
export interface ChatImageUploadResult {
  fileId: string
  url: string
  name: string
  size: number
  mimeType?: string
}

/**
 * 访客上传聊天图片。
 *
 * 访客没有登录态，所以用**渠道密钥**（appKey）证明这条会话来自哪个渠道——租户由服务端从密钥解析，
 * 前端传什么都不影响租户归属。
 */
export function uploadChatImage(data: {
  appKey: string
  sessionNo: string
  visitorToken: string
  file: File
}): Promise<ChatImageUploadResult> {
  const form = new FormData()
  form.append('file', data.file)
  return request<ChatImageUploadResult>({
    url: '/customer/sessions/attachments',
    method: 'post',
    params: { appKey: data.appKey, sessionNo: data.sessionNo },
    data: form,
    headers: { 'X-Visitor-Token': data.visitorToken },
    timeout: 60000,
  })
}

/** 消息：senderType 1-客户、2-坐席、3-机器人、4-系统 */
export interface SessionMessageItem {
  msgId: string
  msgNo: string
  /** 客户端消息号：本地"发送中"的气泡靠它和 ACK 对上 */
  clientMsgNo?: string | null
  /** 会话内序号：排序与增量补拉的游标 */
  seq?: number | null
  sessionId?: string
  senderType: number
  senderId?: string | null
  msgType: number
  content: string
  /** 可见范围：1-客户与坐席都可见、2-仅坐席可见（内部备注） */
  visibleTo?: number
  sendTime: string
  /** 仅前端使用：本地乐观气泡的状态 */
  pending?: boolean
  failed?: boolean
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
  /** 机器人显示名（租户配置）：消息标签用它，不写死"机器人" */
  botName?: string | null
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
operatorId?: string | null
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
  params: { beforeId?: string; afterSeq?: number; limit?: number } = {},
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

/** 会话结束后的满意度评价。访客传会话令牌，坐席可用登录态。 */
export interface CsatResult {
  score: number
  feedback?: string | null
  average?: number | null
}

export function submitSessionCsat(
  sessionNo: string,
  body: { score: number; feedback?: string; visitorToken?: string },
): Promise<CsatResult> {
  return request<CsatResult>({
    url: `/customer/sessions/${sessionNo}/csat`,
    method: 'post',
    data: body,
  })
}
