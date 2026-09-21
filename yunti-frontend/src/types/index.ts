/** 统一后端响应结构（与后端 ApiResponse 对齐） */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  requestId?: string
  timestamp?: number
}

/** 登录参数 */
export interface LoginParams {
  account: string
  password: string
  captchaId: string
  captchaCode: string
}

/** 登录返回 */
export interface LoginResult {
  token: string
  userId: string
  userNo: string
  name: string
  userType: number
  tenantCode?: string
  avatar?: string
}

/** 图形验证码 */
export interface CaptchaResult {
  captchaId: string
  imageBase64: string
  debugCode?: string
}

/** 企业账号注册参数（与登录页「注册企业账号」表单一致） */
export interface RegisterParams {
  companyName: string
  industry: string
  scale: string
  contactName: string
  phone: string
  email: string
  password: string
  captchaId: string
  captchaCode: string
}

/** 企业账号注册返回 */
export interface RegisterResult {
  userId: string
  userNo: string
  contactName: string
  phone: string
  email: string
  enterpriseId: string
  enterpriseCode: string
  enterpriseStatus: number
  tenantCode: string
  userType: number
}

/** /user/auth/me 返回的登录用户信息（JWT 载荷） */
export interface MeResult {
  userId: string
  userNo: string
  name: string
  userType: number
  tenantCode?: string
}

/** 企业开通引导状态 */
export interface EnterpriseGuideState {
  enterpriseId: string
  enterpriseCode: string
  /** PENDING_PROFILE / PENDING_REVIEW / REJECTED / APPROVED */
  stage: 'PENDING_PROFILE' | 'PENDING_REVIEW' | 'REJECTED' | 'APPROVED'
  companyName?: string
  industry?: string
  scale?: string
  licenseNo?: string
  registerAddress?: string
  legalPerson?: string
  licenseFileId?: string | null
  contactName?: string
  contactPhone?: string
  contactEmail?: string
  applyNo?: string
  versionNo?: number | null
  rejectReason?: string
  submitTime?: string
  tenantCode?: string
}

/** 企业资料提交参数 */
export interface EnterpriseProfilePayload {
  companyName: string
  industry: string
  scale: string
  licenseNo: string
  registerAddress: string
  legalPerson: string
  licenseFileId: string
  contactName: string
  contactPhone: string
  contactEmail: string
}

/** 营业执照上传结果 */
export interface LicenseUploadResult {
  fileId: string
  fileName: string
  fileSize: number
  mimeType?: string
}

/** 修改密码参数 */
export interface ChangePasswordParams {
  oldPassword: string
  newPassword: string
}
