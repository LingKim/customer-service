import service, { request } from './request'
import type {
  EnterpriseGuideState,
  EnterpriseProfilePayload,
  LicenseUploadResult,
} from '../types'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'
const MOCK_GUIDE_KEY = 'yunti_mock_enterprise_guide'
const mockFiles = new Map<string, Blob>()

const initialMockGuide: EnterpriseGuideState = {
  enterpriseId: '325036800000002001',
  enterpriseCode: 'E202609210000001',
  stage: 'PENDING_PROFILE',
  companyName: '杭州云智网络科技有限公司',
  industry: '企业服务',
  scale: '10~50 人',
  contactName: '张伟',
  contactPhone: '13800138000',
  contactEmail: 'admin@example.com',
  licenseFileId: null,
}

function readMockGuide(): EnterpriseGuideState {
  const raw = localStorage.getItem(MOCK_GUIDE_KEY)
  if (!raw) return { ...initialMockGuide }
  try {
    return JSON.parse(raw) as EnterpriseGuideState
  } catch {
    localStorage.removeItem(MOCK_GUIDE_KEY)
    return { ...initialMockGuide }
  }
}

function writeMockGuide(state: EnterpriseGuideState) {
  localStorage.setItem(MOCK_GUIDE_KEY, JSON.stringify(state))
  return state
}

function mockDelay<T>(value: T): Promise<T> {
  return new Promise((resolve) => window.setTimeout(() => resolve(value), 180))
}

/** 查询当前企业开通引导状态 */
export function getEnterpriseGuide(): Promise<EnterpriseGuideState> {
  if (USE_MOCK) return mockDelay(readMockGuide())
  return request<EnterpriseGuideState>({
    url: '/tenant/enterprise/my',
    method: 'get',
  })
}

/** 暂存企业资料 */
export function saveEnterpriseProfile(data: EnterpriseProfilePayload): Promise<EnterpriseGuideState> {
  if (USE_MOCK) {
    return mockDelay(writeMockGuide({ ...readMockGuide(), ...data, stage: 'PENDING_PROFILE' }))
  }
  return request<EnterpriseGuideState>({
    url: '/tenant/enterprise/my/profile',
    method: 'post',
    data,
  })
}

/** 提交企业资料，进入审核 */
export function submitEnterpriseReview(data: EnterpriseProfilePayload): Promise<EnterpriseGuideState> {
  if (USE_MOCK) {
    const previous = readMockGuide()
    const versionNo = (previous.versionNo ?? 0) + 1
    return mockDelay(writeMockGuide({
      ...previous,
      ...data,
      stage: 'PENDING_REVIEW',
      applyNo: `YR${Date.now()}`,
      versionNo,
      rejectReason: undefined,
      submitTime: new Date().toISOString(),
    }))
  }
  return request<EnterpriseGuideState>({
    url: '/tenant/enterprise/my/submit',
    method: 'post',
    data,
  })
}

/** 上传营业执照扫描件 */
export function uploadEnterpriseLicense(file: File): Promise<LicenseUploadResult> {
  if (USE_MOCK) {
    const fileId = `${Date.now()}${Math.floor(Math.random() * 1000).toString().padStart(3, '0')}`
    mockFiles.set(fileId, file)
    return mockDelay({ fileId, fileName: file.name, fileSize: file.size, mimeType: file.type })
  }
  const formData = new FormData()
  formData.append('file', file)
  return request<LicenseUploadResult>({
    url: '/customer/files/enterprise/license',
    method: 'post',
    data: formData,
  })
}

/** 拉取营业执照附件内容（用于预览） */
export async function fetchEnterpriseLicense(fileId: string): Promise<Blob> {
  if (USE_MOCK) {
    return mockDelay(mockFiles.get(fileId) ?? new Blob(['Mock 营业执照附件'], { type: 'text/plain' }))
  }
  const response = await service.get(`/customer/files/enterprise/${fileId}`, {
    responseType: 'blob',
  })
  return response.data as Blob
}
