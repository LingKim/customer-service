import { default as service, request } from '../request'

/**
 * 客户 360：客户画像、标签体系与会话轨迹的接口。
 *
 * 编号一律按字符串接：客户编号是 V 开头的随机串，标签 ID 是雪花 ID（19 位数字），
 * 用 number 接会丢精度（第 19 篇踩过的坑）。
 */

/** 客户身上的一个标签 */
export interface CustomerTag {
  tagId?: string | null
  tagName: string
  tagGroup?: string | null
  /** blue / green / orange / red / purple / gray */
  color?: string | null
  /** 1-手工打标、2-规则自动 */
  tagType?: number | null
  /** 1-手工打标、2-规则自动、3-批量导入 */
  source?: number | null
  sourceText?: string | null
  operatorName?: string | null
  createTime?: string | null
}

/** 客户列表 / 画像主体 */
export interface CustomerItem {
  customerNo: string
  name: string
  /** 手机号（后端已脱敏：138****8888） */
  phone?: string | null
  level: number
  levelText: string
  /** 客户类型：1-个人客户、2-企业客户（跟会员等级无关） */
  customerType: number
  customerTypeText: string
  /** 1-正常、2-关注、3-风险 */
  riskLevel: number
  riskText: string
  channel?: string | null
  /** 累计会话数 */
  sessionCount?: number | null
  /** 累计转人工次数 */
  humanCount?: number | null
  /** 累计工单数 */
  ticketCount?: number | null
  /** 满意度均值（1~5） */
  csat?: number | null
  sentimentText?: string | null
  /** 最近一次会话的意图 / 情绪 */
  lastIntent?: string | null
  lastEmotion?: string | null
  lastSessionNo?: string | null
  lastSessionAt?: string | null
  lastActive?: string | null
  remark?: string | null
  tags: CustomerTag[]
  /** 匿名化时间（非空表示这个人已经被抹掉 PII） */
  anonymizedAt?: string | null
  /** 被合并到哪个客户（非空表示这条是重复档案，已经并走了） */
  mergedInto?: string | null
  createTime?: string | null
}

export interface CustomerTicketBrief {
  ticketNo: string
  title: string
  statusText: string
  priority: number
  priorityText: string
  assigneeName?: string | null
  sessionNo?: string | null
  createTime?: string | null
}

export interface CustomerDetail {
  customer: CustomerItem
  tickets: CustomerTicketBrief[]
}

/** 会话轨迹的一行 */
export interface SessionTrace {
  sessionNo: string
  status: number
  statusText: string
  channelName?: string | null
  source?: string | null
  agentId?: string | null
  intent?: string | null
  emotion?: string | null
  botTransferReason?: string | null
  msgCount?: number | null
  csatScore?: number | null
  ticketNo?: string | null
  ticketStatus?: number | null
  startTime?: string | null
  endTime?: string | null
  durationMinutes?: number | null
}

export interface CustomerEvent {
  id: string
  eventType: number
  eventTypeText: string
  title: string
  content?: string | null
  operatorName?: string | null
  eventTime?: string | null
}

/** 标签体系里的一条定义 */
export interface TagDef {
  id: string
  tagCode: string
  tagName: string
  tagGroup: string
  color: string
  tagType: number
  tagTypeText: string
  ruleHint?: string | null
  /** 规则指标（规则标签才有）：TOTAL_VALUE / ORDERS / SESSIONS / REFUND_SESSIONS … */
  ruleMetric?: string | null
  ruleMetricText?: string | null
  /** 比较符：GT / GTE / LT / LTE / EQ */
  ruleOp?: string | null
  ruleValue?: number | null
  /** 统计窗口（天）：0 表示全周期 */
  ruleWindowDays?: number | null
  /** 规则的人话描述：累计会话数 ≥ 5（全周期） */
  ruleText?: string | null
  description?: string | null
  sortNo: number
  enabled: boolean
  /** 这个标签当前打在多少个客户身上 */
  customerCount: number
  createTime?: string | null
}

export interface CustomerOverview {
  total: number
  monthNew: number
  active7d: number
  riskCount: number
  vipCount: number
  taggedCount: number
}

export interface CustomerPage {
  total: number
  page: number
  pageSize: number
  list: CustomerItem[]
}

export interface CustomerAccess {
  roleCode?: string | null
  roleName: string
  /** 能不能打标、改档案（质检专员 / AI运营这类只读角色为 false） */
  canOperate: boolean
  /** 能不能维护标签体系（仅企业管理员） */
  canManageTag: boolean
  /** 能不能看完整的客户手机号（管理员 / 客服主管，且每次查看都会留痕） */
  canSeePhone: boolean
  hint?: string | null
}

export interface CustomerQuery {
  keyword?: string
  level?: number
  riskLevel?: number
  /** 客户类型：1-个人、2-企业 */
  customerType?: number
  tagId?: string
  channel?: string
  /** 只看最近 N 天活跃过的 */
  activeDays?: number
  hasTicket?: boolean
  /** active（默认）/ sessions / csat / level */
  sort?: string
  page?: number
  pageSize?: number
}

export function fetchCustomers(query: CustomerQuery): Promise<CustomerPage> {
  return request<CustomerPage>({ url: '/customer/customers', method: 'get', params: query })
}

export function fetchCustomerOverview(): Promise<CustomerOverview> {
  return request<CustomerOverview>({ url: '/customer/customers/overview', method: 'get' })
}

export function fetchCustomerAccess(): Promise<CustomerAccess> {
  return request<CustomerAccess>({ url: '/customer/customers/access', method: 'get' })
}

export function fetchCustomerDetail(customerNo: string): Promise<CustomerDetail> {
  return request<CustomerDetail>({ url: `/customer/customers/${customerNo}`, method: 'get' })
}

export function fetchCustomerSessions(customerNo: string): Promise<SessionTrace[]> {
  return request<SessionTrace[]>({ url: `/customer/customers/${customerNo}/sessions`, method: 'get' })
}

export function fetchCustomerEvents(customerNo: string): Promise<CustomerEvent[]> {
  return request<CustomerEvent[]>({ url: `/customer/customers/${customerNo}/events`, method: 'get' })
}

/** 打标 */
export function addCustomerTag(customerNo: string, tagId: string, remark?: string): Promise<CustomerDetail> {
  return request<CustomerDetail>({
    url: `/customer/customers/${customerNo}/tags`,
    method: 'post',
    data: { tagId, remark },
  })
}

/** 去标 */
export function removeCustomerTag(customerNo: string, tagId: string): Promise<CustomerDetail> {
  return request<CustomerDetail>({
    url: `/customer/customers/${customerNo}/tags/${tagId}`,
    method: 'delete',
  })
}

/** 改档案（只传要改的字段） */
export function updateCustomerProfile(
  customerNo: string,
  body: {
    name?: string
    phone?: string
    level?: number
    customerType?: number
    riskLevel?: number
    remark?: string
  },
): Promise<CustomerDetail> {
  return request<CustomerDetail>({
    url: `/customer/customers/${customerNo}/profile`,
    method: 'post',
    data: body,
  })
}

/** 标签体系（含使用量） */
export function fetchTagDefs(): Promise<TagDef[]> {
  return request<TagDef[]>({ url: '/customer/customers/tags', method: 'get' })
}

/** 规则标签可选的指标（规则编辑器下拉） */
export function fetchMetricOptions(): Promise<MetricOption[]> {
  return request<MetricOption[]>({ url: '/customer/customers/tags/metrics', method: 'get' })
}

/** 手动重算全部客户的规则标签（仅企业管理员） */
export function recalculateAllTags(): Promise<TagRecalcBatchResult> {
  return request<TagRecalcBatchResult>({ url: '/customer/customers/tags/recalculate', method: 'post' })
}

/** 批量打标 */
export function batchAddCustomerTag(
  customerNos: string[],
  tagId: string,
  remark?: string,
): Promise<BatchTagResult> {
  return request<BatchTagResult>({
    url: '/customer/customers/tags/batch',
    method: 'post',
    data: { customerNos, tagId, remark },
  })
}

/**
 * 客户导入（CSV）。
 *
 * 注意：这里**不能**手写 Content-Type —— 手写 'multipart/form-data' 会把 boundary 丢掉，
 * 服务端解析出来的文件是空的（第 20 篇踩过这个坑）。
 */
export function importCustomers(file: File): Promise<ImportResult> {
  const form = new FormData()
  form.append('file', file)
  return request<ImportResult>({ url: '/customer/customers/import', method: 'post', data: form })
}

/** 查看完整手机号（管理员 / 主管，会写一条客户动态留痕） */
export function revealCustomerPhone(customerNo: string): Promise<{ phone?: string | null; masked?: string | null }> {
  return request({ url: `/customer/customers/${customerNo}/phone/reveal`, method: 'post' })
}

/** 重算这个客户的规则标签 */
export function recalculateCustomerTags(customerNo: string): Promise<TagRecalcResult> {
  return request<TagRecalcResult>({
    url: `/customer/customers/${customerNo}/tags/recalculate`,
    method: 'post',
  })
}

/** 合并重复客户（源客户软删，关系迁到目标客户） */
export function mergeCustomer(sourceNo: string, targetCustomerNo: string): Promise<MergeResult> {
  return request<MergeResult>({
    url: `/customer/customers/${sourceNo}/merge`,
    method: 'post',
    params: { targetCustomerNo },
  })
}

/** 匿名化（抹掉姓名 / 手机号 / 备注，保留会话与统计） */
export function anonymizeCustomer(customerNo: string): Promise<CustomerDetail> {
  return request<CustomerDetail>({
    url: `/customer/customers/${customerNo}/anonymize`,
    method: 'post',
  })
}

/** 删除客户（软删 + 抹 PII） */
export function deleteCustomer(customerNo: string): Promise<void> {
  return request<void>({ url: `/customer/customers/${customerNo}`, method: 'delete' })
}

export interface TagDefBody {
  tagCode?: string
  tagName: string
  tagGroup: string
  color: string
  tagType: number
  ruleHint?: string
  /** 规则标签的四件套：指标 / 比较符 / 阈值 / 统计窗口（天） */
  ruleMetric?: string
  ruleOp?: string
  ruleValue?: number
  ruleWindowDays?: number
  description?: string
  sortNo?: number
  enabled?: boolean
}

/** 规则标签可选的指标 */
export interface MetricOption {
  value: string
  label: string
}

/** 规则重算结果 */
export interface TagRecalcResult {
  ruleCount: number
  tagged: number
  untagged: number
}

/** 全量重算结果 */
export interface TagRecalcBatchResult {
  scanned: number
  tagged: number
  untagged: number
}

/** 批量打标结果 */
export interface BatchTagResult {
  requested: number
  tagged: number
  skipped: number
  missing: string[]
}

/** 导入结果：逐行容错，失败明细带行号 */
export interface ImportResult {
  total: number
  created: number
  updated: number
  tagged: number
  failures: Array<{ line: number; reason: string }>
}

/** 合并结果 */
export interface MergeResult {
  sourceCustomerNo: string
  targetCustomerNo: string
  sessions: number
  tickets: number
  orders: number
  tags: number
}

export function createTagDef(body: TagDefBody): Promise<TagDef> {
  return request<TagDef>({ url: '/customer/customers/tags', method: 'post', data: body })
}

export function updateTagDef(id: string, body: TagDefBody): Promise<TagDef> {
  return request<TagDef>({ url: `/customer/customers/tags/${id}`, method: 'put', data: body })
}

export function toggleTagDef(id: string, enabled: boolean): Promise<TagDef> {
  return request<TagDef>({
    url: `/customer/customers/tags/${id}/enabled`,
    method: 'post',
    params: { enabled },
  })
}

export function deleteTagDef(id: string): Promise<void> {
  return request<void>({ url: `/customer/customers/tags/${id}`, method: 'delete' })
}

/**
 * 导出客户 CSV（按当前筛选）。
 *
 * 走原始 axios 实例而不是 `request()`：CSV 不是 `{code,data}` 结构，
 * 统一解包会把它当成业务失败。
 */
export async function downloadCustomersCsv(query: CustomerQuery, fileName = '客户列表.csv') {
  const response = await service.get('/customer/customers/export', {
    params: query,
    responseType: 'blob',
  })
  const url = window.URL.createObjectURL(response.data as Blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}

/** 标签颜色 → Element Plus 的 tag type（页面统一用一套色板） */
export function tagTypeOf(color?: string | null): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  switch (color) {
    case 'green':
      return 'success'
    case 'orange':
      return 'warning'
    case 'red':
      return 'danger'
    case 'gray':
      return 'info'
    default:
      return 'primary'
  }
}
