import { default as service, request } from '../request'

/**
 * 数据大屏与坐席绩效接口。
 *
 * 两个数据源（后端已经分好）：
 *   · `fetchRealtime`：实时大屏，"此刻 / 今天"的口径，页面 5 秒轮询一次；
 *   · `fetchAgentReport` 等：坐席绩效，读的是"坐席 × 天"的预聚合，支持筛选、排序、分页、下钻。
 */

export interface RealtimeMetrics {
  /** 在线坐席（长连接在线且空闲） */
  onlineAgents: number
  busyAgents: number
  /** 租户下已登记状态的坐席总数（"在线坐席 4/7" 的分母） */
  totalAgents: number
  queuingSessions: number
  activeSessions: number
  botSessions: number
  sessionToday: number
  messageToday: number
  customerMessageToday: number
  csatCountToday: number
  /** 今日好评条数（4~5 星）：首页"客户满意度"按好评率算，口径与坐席绩效一致 */
  csatGoodToday: number
  csatScoreToday?: number | null
  firstResponseSeconds: number
  botSessionToday: number
  /** 机器人接待占比（百分比整数） */
  botRatio: number
  hourly: Array<{ hour: number; count: number }>
  channels: Array<{ name: string; count: number }>
  intents: Array<{ name: string; count: number }>
  emotions: Array<{ name: string; count: number }>
  topAgents: Array<{
    agentId?: string | null
    agentName: string
    sessionCount: number
    messageCount: number
    avgResponseSeconds: number
    csatScore?: number | null
  }>
  /** 坐席实时状态（首页"坐席实时状态"卡片，含在线/忙碌/小休） */
  agentLive: Array<{
    agentId?: string | null
    agentName: string
    status: number
    statusText: string
    connected: boolean
    activeCount: number
    sessionCount: number
    csatScore?: number | null
  }>
  /** 近 14 天租户趋势：会话 / 机器人接待 / 消息 / 评价 / 首响 */
  trend: Array<{
    day: string
    sessionCount: number
    botCount: number
    messageCount: number
    csatCount: number
    goodCsatCount: number
    csatScore?: number | null
    firstResponseSeconds: number
  }>
  /** 实时动态：今天的会话 / 转人工 / 评价 / 工单 / 质检预警事件流 */
  activities: Array<{ kind: string; text: string; eventTime?: string | null }>
  serverTime?: string | null
}

export interface AgentMetricRow {
  agentId?: string | null
  agentName: string
  activeDays: number
  sessionCount: number
  humanSessionCount: number
  messageCount: number
  customerMessageCount: number
  firstResponseSeconds: number
  avgResponseSeconds: number
  avgSessionSeconds: number
  transferCount: number
  csatCount: number
  csatScore?: number | null
  goodCsatCount: number
  closeCount: number
  /** 日均接待 */
  sessionPerDay: number
}

export interface AgentReport {
  total: number
  page: number
  pageSize: number
  from: string
  to: string
  list: AgentMetricRow[]
}

export interface AgentDrill {
  agentId?: string | null
  agentName: string
  from: string
  to: string
  trend: Array<{ day: string; sessionCount: number; messageCount: number; csatScore?: number | null; firstResponseSeconds: number }>
  sessions: Array<{
    sessionNo: string
    customerName?: string | null
    source?: string | null
    status: number
    msgCount: number
    csatScore: number
    intent?: string | null
    emotion?: string | null
    startTime?: string | null
    endTime?: string | null
    durationMinutes?: number | null
  }>
}

export interface MetricsQuery {
  from?: string
  to?: string
  agentId?: string
  /** sessionCount / messageCount / firstResponse / csat / transfer / closeCount */
  sort?: string
  page?: number
  pageSize?: number
}

export function fetchRealtime(): Promise<RealtimeMetrics> {
  return request<RealtimeMetrics>({ url: '/customer/metrics/realtime', method: 'get' })
}

/** 当前登录人能不能看数据报表（管理员 / 客服主管），菜单据此显隐 */
export interface MetricsAccess {
  canView: boolean
  roleCode?: string | null
  roleName: string
  hint?: string | null
}

export function fetchMetricsAccess(): Promise<MetricsAccess> {
  return request<MetricsAccess>({ url: '/customer/metrics/access', method: 'get', silent: true })
}

export function fetchAgentReport(query: MetricsQuery): Promise<AgentReport> {
  return request<AgentReport>({ url: '/customer/metrics/agents', method: 'get', params: query })
}

export function fetchAgentDrill(agentId: string, query: MetricsQuery): Promise<AgentDrill> {
  return request<AgentDrill>({
    url: `/customer/metrics/agents/${agentId}`,
    method: 'get',
    params: { from: query.from, to: query.to },
  })
}

/** 重算某一天的坐席绩效（幂等） */
export function rebuildMetrics(date?: string): Promise<{ date: string; rows: number }> {
  return request<{ date: string; rows: number }>({
    url: '/customer/metrics/rebuild',
    method: 'post',
    params: date ? { date } : {},
  })
}

/**
 * 导出绩效 CSV。
 *
 * 走原始 axios 实例：CSV 不是 `{code,data}`，统一解包会当成业务失败。
 */
export async function downloadMetricsCsv(query: MetricsQuery, fileName = '坐席绩效.csv') {
  const response = await service.get('/customer/metrics/agents/export', {
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

/** 秒 → 人话（"1 分 20 秒" / "3 小时 5 分"）：报表里全是秒，直接看数字没有体感 */
export function durationText(seconds?: number | null): string {
  if (seconds == null || seconds <= 0) {
    return '—'
  }
  if (seconds < 60) {
    return `${seconds} 秒`
  }
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    const rest = seconds % 60
    return rest ? `${minutes} 分 ${rest} 秒` : `${minutes} 分`
  }
  const hours = Math.floor(minutes / 60)
  const restMinutes = minutes % 60
  return restMinutes ? `${hours} 小时 ${restMinutes} 分` : `${hours} 小时`
}
