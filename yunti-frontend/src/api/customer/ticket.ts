import { default as service, request } from '../request'

/**
 * 工单中心接口。
 *
 * <p>编号一律用字符串：工单号是 TK 开头的字符串，用户 ID 是雪花 ID（19 位数字），
 * 用 number 接会在前端丢精度——第 19 篇踩过这个坑，这里从一开始就按字符串走。</p>
 */

export interface TicketItem {
  /** 租户号（平台工单跨租户展示时要用） */
  tenantCode?: string | null
  /** 1-企业内部工单、2-平台支持工单 */
  ticketType?: number | null
  ticketTypeText?: string | null
  ticketNo: string
  title: string
  /** 来源渠道（1-在线会话、2-电话热线、3-邮件、4-工单导入、5-其它） */
  sourceChannel?: number | null
  sourceChannelText?: string | null
  category: number
  categoryText: string
  priority: number
  priorityText: string
  status: number
  statusText: string
  source: number
  sourceText: string
  sessionNo?: string | null
  customerName?: string | null
  assigneeId?: string | null
  assigneeName?: string | null
  /** 1-正常、2-即将超时、3-已超时 */
  slaState: number
  slaStateText: string
  /** 距离下一个截止时间还有多少分钟，负数表示已经超了 */
  remainMinutes?: number | null
  firstResponded: boolean
  firstResponseDue?: string | null
  resolveDue?: string | null
  createTime?: string | null
  creatorName?: string | null
  /** 已解决时的"耗时 x 分钟" */
  finishText?: string | null
}

export interface TicketEventItem {
  id: string
  eventType: number
  eventTypeText: string
  operatorName?: string | null
  content?: string | null
  fromStatusText?: string | null
  toStatusText?: string | null
  visibleToCustomer: boolean
  eventTime?: string | null
}

export interface TicketSla {
  firstResponseMinutes: number
  resolveMinutes: number
}

export interface TicketDetail {
  ticket: TicketItem
  content?: string | null
  events: TicketEventItem[]
  sla: TicketSla
  /** 租户号：工单号只在租户内唯一，报给平台排查时要一起给 */
  tenantCode?: string | null
}

export interface TicketOverview {
  pending: number
  processing: number
  confirming: number
  overdue: number
  warning: number
  mine: number
  resolvedToday: number
}

export interface TicketSlaRule {
  priority: number
  priorityText: string
  firstResponseMinutes: number
  resolveMinutes: number
  enabled: boolean
}

export interface TicketQuery {
  status?: number
  priority?: number
  category?: number
  slaState?: number
  assigneeId?: string
  mineOnly?: boolean
  unassignedOnly?: boolean
  sessionNo?: string
  keyword?: string
  /** 工单类型：1-企业内部（默认）、2-平台支持 */
  ticketType?: number
  /** 分页：第几页（从 1 开始）与每页条数 */
  page?: number
  pageSize?: number
}

export interface TicketPage {
  total: number
  page: number
  pageSize: number
  list: TicketItem[]
}

/** 当前登录人在工单里的权限（后端算好下发，前端只负责显隐按钮） */
export interface TicketAccess {
  roleCode?: string | null
  roleName: string
  /** 能不能建单与处理（质检专员 / AI运营这类只读角色为 false） */
  canOperate: boolean
  /** 能不能改 SLA 规则、手动扫超时（仅企业管理员） */
  canManageSla: boolean
  hint?: string | null
}

export interface CreateTicketBody {
  title?: string
  content?: string
  category?: number
  priority?: number
  /** 1-会话转单、2-客户自助、3-坐席新建（带 sessionNo 时后端会按会话转单处理） */
  source?: number
  sessionNo?: string
  assigneeId?: string
  assigneeName?: string
}

export function fetchTicketOverview(): Promise<TicketOverview> {
  return request<TicketOverview>({ url: '/customer/tickets/overview', method: 'get' })
}

export function fetchTicketAccess(): Promise<TicketAccess> {
  return request<TicketAccess>({ url: '/customer/tickets/access', method: 'get' })
}

export function fetchTickets(query: TicketQuery = {}): Promise<TicketPage> {
  return request<TicketPage>({ url: '/customer/tickets', method: 'get', params: query })
}

export function fetchTicketDetail(ticketNo: string): Promise<TicketDetail> {
  return request<TicketDetail>({ url: `/customer/tickets/${ticketNo}`, method: 'get' })
}

export function createTicket(body: CreateTicketBody): Promise<TicketItem> {
  return request<TicketItem>({ url: '/customer/tickets', method: 'post', data: body })
}

/** 提交给平台支持（渠道接入失败、计费异常这类平台才能处理的问题） */
export function createPlatformTicket(body: CreateTicketBody): Promise<TicketItem> {
  return request<TicketItem>({ url: '/customer/tickets/platform', method: 'post', data: body })
}

/** 在平台支持工单下补充说明（不算平台首次响应，也不改状态） */
export function supplementTicket(ticketNo: string, content: string): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/customer/tickets/${ticketNo}/supplement`,
    method: 'post',
    data: { content, visibleToCustomer: true },
  })
}

/** 升级工单：优先级提一档（不重算 SLA 截止时间），并通知处理人与主管 */
export function escalateTicket(
  ticketNo: string,
  body: { remark?: string } = {},
): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/customer/tickets/${ticketNo}/escalate`,
    method: 'post',
    data: body,
  })
}

/** 分派 / 转派；toUserId 不传表示认领给自己 */
export function assignTicket(
  ticketNo: string,
  body: { toUserId?: string; toUserName?: string; remark?: string } = {},
): Promise<TicketDetail> {
  return request<TicketDetail>({ url: `/customer/tickets/${ticketNo}/assign`, method: 'post', data: body })
}

export function replyTicket(
  ticketNo: string,
  body: { content: string; visibleToCustomer?: boolean },
): Promise<TicketDetail> {
  return request<TicketDetail>({ url: `/customer/tickets/${ticketNo}/reply`, method: 'post', data: body })
}

export function changeTicketStatus(
  ticketNo: string,
  body: { status: number; remark?: string },
): Promise<TicketDetail> {
  return request<TicketDetail>({ url: `/customer/tickets/${ticketNo}/status`, method: 'post', data: body })
}

/** 手动触发一次 SLA 扫描（页面上的"检查超时"） */
export function scanTicketSla(): Promise<{ scanned: number; alerted: number; overdue: number }> {
  return request({ url: '/customer/tickets/scan-sla', method: 'post' })
}

export function fetchTicketSlaRules(): Promise<TicketSlaRule[]> {
  return request<TicketSlaRule[]>({ url: '/customer/tickets/sla-rules', method: 'get' })
}

export function saveTicketSlaRules(
  rules: Array<{ priority: number; firstResponseMinutes: number; resolveMinutes: number; enabled: boolean }>,
): Promise<TicketSlaRule[]> {
  return request<TicketSlaRule[]>({ url: '/customer/tickets/sla-rules', method: 'put', data: rules })
}

/**
 * 导出工单 CSV（按当前筛选）。
 *
 * <p>走原始 axios 实例而不是 `request()`：CSV 不是 `{code,data}` 结构，
 * 统一解包会把它当成业务失败。这里直接把响应当 blob 交给浏览器下载。</p>
 */
export async function downloadTicketsCsv(query: TicketQuery, fileName = '工单列表.csv') {
  const response = await service.get('/customer/tickets/export', {
    params: query,
    responseType: 'blob',
  })
  saveBlob(response.data as Blob, fileName)
}

/** 触发浏览器下载（BOM 由后端写入，Excel 打开中文不乱码） */
export function saveBlob(blob: Blob, fileName: string) {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}
