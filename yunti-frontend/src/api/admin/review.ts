import { request } from '../request'
import type { EnterpriseGuideState } from '../../types'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'
const MOCK_GUIDE_KEY = 'yunti_mock_enterprise_guide'

export interface EnterpriseReviewItem {
  id: string
  enterpriseId: string
  enterpriseCode: string
  tenantCode?: string
  applyNo: string
  versionNo: number
  applicantId: string
  companyName: string
  industry?: string
  scale?: string
  contactName: string
  contactPhone?: string
  contactEmail?: string
  licenseNo?: string
  registerAddress?: string
  legalPerson?: string
  licenseFileId?: string | null
  status: number
  rejectReason?: string
  reviewerId?: string | null
  reviewTime?: string
  submitTime?: string
  enterpriseStatus?: number
}

export interface EnterpriseReviewPage {
  records: EnterpriseReviewItem[]
  total: number
  current: number
  size: number
  pages: number
}

function mockReview(): EnterpriseReviewItem | null {
  try {
    const guide = JSON.parse(localStorage.getItem(MOCK_GUIDE_KEY) || '{}') as EnterpriseGuideState
    if (!guide.applyNo) return null
    return {
      id: '325036800000003001', enterpriseId: guide.enterpriseId,
      enterpriseCode: guide.enterpriseCode, tenantCode: guide.tenantCode,
      applyNo: guide.applyNo, versionNo: guide.versionNo || 1,
      applicantId: '325036800000001001', companyName: guide.companyName || '',
      industry: guide.industry, scale: guide.scale, contactName: guide.contactName || '',
      contactPhone: guide.contactPhone, contactEmail: guide.contactEmail,
      licenseNo: guide.licenseNo, registerAddress: guide.registerAddress,
      legalPerson: guide.legalPerson, licenseFileId: guide.licenseFileId,
      status: guide.stage === 'APPROVED' ? 2 : guide.stage === 'REJECTED' ? 3 : 1,
      rejectReason: guide.rejectReason, submitTime: guide.submitTime,
    }
  } catch { return null }
}

function updateMockGuide(patch: Partial<EnterpriseGuideState>): EnterpriseReviewItem {
  const guide = JSON.parse(localStorage.getItem(MOCK_GUIDE_KEY) || '{}') as EnterpriseGuideState
  localStorage.setItem(MOCK_GUIDE_KEY, JSON.stringify({ ...guide, ...patch }))
  const review = mockReview()
  if (!review) throw new Error('审核申请不存在')
  return review
}

export function pageEnterpriseReviews(params: {
  pageNum: number
  pageSize: number
  status?: number
  keyword?: string
}): Promise<EnterpriseReviewPage> {
  if (USE_MOCK) {
    const review = mockReview()
    const matched = review && (!params.status || review.status === params.status)
      && (!params.keyword || review.companyName.includes(params.keyword) || review.applyNo.includes(params.keyword))
    const records = matched ? [review] : []
    return Promise.resolve({ records, total: records.length, current: params.pageNum, size: params.pageSize, pages: records.length ? 1 : 0 })
  }
  return request({ url: '/tenant/admin/reviews', method: 'get', params })
}

export function getEnterpriseReview(reviewId: string): Promise<EnterpriseReviewItem> {
  if (USE_MOCK) {
    const review = mockReview()
    return review && review.id === reviewId ? Promise.resolve(review) : Promise.reject(new Error('审核申请不存在'))
  }
  return request({ url: `/tenant/admin/reviews/${reviewId}`, method: 'get' })
}

export function approveEnterpriseReview(reviewId: string): Promise<EnterpriseReviewItem> {
  if (USE_MOCK) {
    const review = mockReview()
    if (!review || review.id !== reviewId) return Promise.reject(new Error('审核申请不存在'))
    const date = new Date().toISOString().slice(0, 10).replace(/-/g, '')
    return Promise.resolve(updateMockGuide({ stage: 'APPROVED', tenantCode: `T${date}0000001` }))
  }
  return request({ url: `/tenant/admin/reviews/${reviewId}/approve`, method: 'post' })
}

export function rejectEnterpriseReview(reviewId: string, reason: string): Promise<EnterpriseReviewItem> {
  if (USE_MOCK) {
    const review = mockReview()
    if (!review || review.id !== reviewId) return Promise.reject(new Error('审核申请不存在'))
    return Promise.resolve(updateMockGuide({ stage: 'REJECTED', rejectReason: reason }))
  }
  return request({ url: `/tenant/admin/reviews/${reviewId}/reject`, method: 'post', data: { reason } })
}
