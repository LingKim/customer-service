import { default as service, request } from '../request'
import type { TicketDetail, TicketItem, TicketPage } from '../customer/ticket'
import { saveBlob } from '../customer/ticket'

/**
 * 平台侧支持工单接口（跨租户）。
 *
 * <p>和 `/customer/tickets` 分开：那边是企业租户内的工单（租户从登录态取），
 * 这边是平台账号的跨租户视角，每条都要显式带 `tenant`（工单号只在租户内唯一）。</p>
 */

export interface PlatformTicketOverview {
  pending: number
  processing: number
  confirming: number
  overdue: number
  warning: number
  mine: number
  resolvedToday: number
}

export interface PlatformTicketQuery {
  status?: number
  priority?: number
  slaState?: number
  /** 租户号（支持前缀模糊） */
  tenant?: string
  keyword?: string
  mineOnly?: boolean
  unassignedOnly?: boolean
  page?: number
  pageSize?: number
}

export function fetchPlatformTicketOverview(): Promise<PlatformTicketOverview> {
  return request<PlatformTicketOverview>({ url: '/platform/tickets/overview', method: 'get' })
}

export function fetchPlatformTickets(query: PlatformTicketQuery = {}): Promise<TicketPage> {
  return request<TicketPage>({ url: '/platform/tickets', method: 'get', params: query })
}

export function fetchPlatformTicketDetail(ticketNo: string, tenant: string): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/platform/tickets/${ticketNo}`,
    method: 'get',
    params: { tenant },
  })
}

/** 平台认领（把处理人设成自己） */
export function claimPlatformTicket(ticketNo: string, tenant: string): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/platform/tickets/${ticketNo}/claim`,
    method: 'post',
    params: { tenant },
  })
}

export function replyPlatformTicket(
  ticketNo: string,
  tenant: string,
  body: { content: string; visibleToCustomer?: boolean },
): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/platform/tickets/${ticketNo}/reply`,
    method: 'post',
    params: { tenant },
    data: body,
  })
}

export function changePlatformTicketStatus(
  ticketNo: string,
  tenant: string,
  body: { status: number; remark?: string },
): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/platform/tickets/${ticketNo}/status`,
    method: 'post',
    params: { tenant },
    data: body,
  })
}

/** 平台侧升级（一线处理不动就提优先级，并通知企业） */
export function escalatePlatformTicket(
  ticketNo: string,
  tenant: string,
  body: { remark?: string } = {},
): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/platform/tickets/${ticketNo}/escalate`,
    method: 'post',
    params: { tenant },
    data: body,
  })
}

/** 平台侧导出 CSV（跨租户，带租户号） */
export async function downloadPlatformTicketsCsv(
  query: PlatformTicketQuery,
  fileName = '支持工单.csv',
) {
  const response = await service.get('/platform/tickets/export', {
    params: query,
    responseType: 'blob',
  })
  saveBlob(response.data as Blob, fileName)
}

export type { TicketDetail, TicketItem, TicketPage }
