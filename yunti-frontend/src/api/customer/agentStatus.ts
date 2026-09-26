import { request } from '../request'

/** 坐席状态：1-在线、2-忙碌、3-小休 */
export interface AgentStatusItem {
  agentId: string
  status: number
  statusText: string
  /** 最多同时接待几单 */
  maxConcurrency: number
  /** 当前正在接待几单（达到上限后路由不再派新会话） */
  activeCount: number
  connected: boolean
}

export interface AgentStatusView {
  mine: AgentStatusItem
  agents: AgentStatusItem[]
}

/** 我的状态 + 同事状态 */
export function fetchAgentStatuses(): Promise<AgentStatusView> {
  return request<AgentStatusView>({ url: '/customer/agent-status', method: 'get' })
}

/** 切换我的状态（可选顺带改"最多同时接待几单"） */
export function updateAgentStatus(data: {
  status?: number
  maxConcurrency?: number
}): Promise<AgentStatusItem> {
  return request<AgentStatusItem>({ url: '/customer/agent-status', method: 'put', data })
}
