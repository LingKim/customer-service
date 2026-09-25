<template>
  <div class="guide-page">
    <header class="guide-nav">
      <div class="brand">
        <img src="/yunti-mark.svg" alt="云梯" />
        <div><strong>云梯智能客服</strong><span>企业开通引导</span></div>
      </div>
      <div class="nav-actions">
        <span>{{ guide?.companyName }}</span>
        <el-button text @click="openLogout">退出登录</el-button>
      </div>
    </header>

    <main class="guide-wrap">
      <el-card v-if="loading" class="state-card" shadow="never">
        <el-icon class="is-loading" :size="30"><Loading /></el-icon><p>正在加载开通状态…</p>
      </el-card>
      <el-card v-else-if="loadError" class="state-card" shadow="never">
        <el-icon :size="34" color="#f59e0b"><WarningFilled /></el-icon>
        <p>{{ loadError }}</p>
        <el-button type="primary" @click="loadGuide">重新加载</el-button>
      </el-card>

      <template v-else-if="guide">
        <div class="steps">
          <div v-for="(label, index) in stepLabels" :key="label" class="step" :class="{ active: currentStep >= index + 1 }">
            <span>{{ currentStep > index + 1 ? '✓' : index + 1 }}</span>{{ label }}
          </div>
        </div>

        <el-card v-if="guide.stage === 'PENDING_PROFILE' || guide.stage === 'REJECTED'" class="guide-card" shadow="never">
          <el-alert
            v-if="guide.stage === 'REJECTED'"
            :title="`第 ${guide.versionNo ?? 1} 次申请未通过`"
            :description="guide.rejectReason || '请根据审核意见修改后重新提交'"
            type="error"
            show-icon
            :closable="false"
          />
          <div class="card-head">
            <div><h1>完善企业信息</h1><p>填写实名资料，提交后进入运营审核。</p></div>
            <el-tag type="warning">第 1 步 / 共 3 步</el-tag>
          </div>

          <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
            <div class="form-grid">
              <el-form-item label="企业名称" prop="companyName"><el-input v-model.trim="form.companyName" maxlength="128" /></el-form-item>
              <el-form-item label="统一社会信用代码" prop="licenseNo"><el-input v-model.trim="form.licenseNo" maxlength="18" /></el-form-item>
            </div>
            <el-form-item label="营业执照扫描件">
              <input ref="fileInput" class="hidden-input" type="file" accept=".jpg,.jpeg,.png,.pdf" @change="onFileChange" />
              <div v-if="!licenseFileId" class="upload-zone" @click="fileInput?.click()">
                <el-icon :size="26"><UploadFilled /></el-icon><strong>点击上传营业执照</strong><span>JPG / PNG / PDF，不超过 10MB</span>
              </div>
              <div v-else class="file-ready">
                <img v-if="previewUrl && previewIsImage" :src="previewUrl" alt="营业执照预览" />
                <el-icon v-else :size="28" color="#10b981"><Document /></el-icon>
                <div><strong>{{ licenseFileName || '已上传营业执照' }}</strong><span>文件将用于企业实名核验</span></div>
                <el-button size="small" @click="resetLicense">重新上传</el-button>
              </div>
            </el-form-item>
            <div class="form-grid">
              <el-form-item label="注册地址" prop="registerAddress"><el-input v-model.trim="form.registerAddress" maxlength="255" /></el-form-item>
              <el-form-item label="法人代表" prop="legalPerson"><el-input v-model.trim="form.legalPerson" maxlength="64" /></el-form-item>
              <el-form-item label="管理员姓名" prop="contactName"><el-input v-model.trim="form.contactName" maxlength="64" /></el-form-item>
              <el-form-item label="联系电话" prop="contactPhone"><el-input v-model.trim="form.contactPhone" maxlength="11" /></el-form-item>
              <el-form-item label="企业邮箱" prop="contactEmail"><el-input v-model.trim="form.contactEmail" maxlength="128" /></el-form-item>
              <el-form-item label="所属行业 / 团队规模" prop="industry">
                <div class="inline-selects">
                  <el-select v-model="form.industry" placeholder="所属行业"><el-option v-for="item in industries" :key="item" :label="item" :value="item" /></el-select>
                  <el-select v-model="form.scale" placeholder="团队规模"><el-option v-for="item in scales" :key="item" :label="item" :value="item" /></el-select>
                </div>
              </el-form-item>
            </div>
            <div class="privacy-note"><el-icon><InfoFilled /></el-icon>请确保已获得企业授权，营业执照与法人信息仅用于本次实名核验。</div>
            <div class="form-actions">
              <el-button :loading="saving" @click="saveDraft">保存草稿</el-button>
              <el-button type="primary" :loading="submitting" @click="submit">提交资料进入审核</el-button>
            </div>
          </el-form>
        </el-card>

        <el-card v-else-if="guide.stage === 'PENDING_REVIEW'" class="guide-card result-card" shadow="never">
          <el-icon :size="54" color="#f59e0b"><Clock /></el-icon>
          <h1>企业资料审核中</h1>
          <p>申请单 {{ guide.applyNo }}，已于 {{ formatTime(guide.submitTime) }} 提交。</p>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="企业名称">{{ guide.companyName }}</el-descriptions-item>
            <el-descriptions-item label="统一社会信用代码">{{ guide.licenseNo }}</el-descriptions-item>
            <el-descriptions-item label="审核说明">预计 1~2 个工作日，页面会自动刷新状态。</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card v-else class="guide-card result-card" shadow="never">
          <el-icon :size="58" color="#10b981"><CircleCheckFilled /></el-icon>
          <h1>企业已完成开通</h1>
          <p>租户编码：{{ guide.tenantCode || '待分配' }}</p>
          <p v-if="userStore.tenantCode === 'PLATFORM'">租户已开通，请重新登录以获取新的租户身份。</p>
          <p v-else>下一步接入第一个客服渠道。</p>
          <el-button type="primary" size="large" @click="enterWorkspace">
            {{ userStore.tenantCode === 'PLATFORM' ? '重新登录' : '接入渠道' }}
          </el-button>
        </el-card>
      </template>
    </main>

    <LogoutConfirmDialog v-model="logoutVisible" :name="userStore.name" :account="userStore.userNo" @confirm="doLogout" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  fetchEnterpriseLicense,
  getEnterpriseGuide,
  saveEnterpriseProfile,
  submitEnterpriseReview,
  uploadEnterpriseLicense,
} from '../../api/enterprise'
import LogoutConfirmDialog from '../../components/LogoutConfirmDialog.vue'
import { useUserStore } from '../../stores/user'
import type { EnterpriseGuideState, EnterpriseProfilePayload } from '../../types'

const router = useRouter()
const userStore = useUserStore()
const DONE_KEY = 'yunti_onboard_done'
const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const submitting = ref(false)
const logoutVisible = ref(false)
const guide = ref<EnterpriseGuideState | null>(null)
const formRef = ref<FormInstance>()
const fileInput = ref<HTMLInputElement>()
const licenseFileId = ref<string | null>(null)
const licenseFileName = ref('')
const previewUrl = ref('')
const previewIsImage = ref(false)
let reviewTimer: number | undefined

const stepLabels = ['完善企业信息', '企业审核', '系统初始化']
const industries = ['电商零售', '企业服务', '金融保险', '教育', '智能硬件', '其他']
const scales = ['10 人以内', '10~50 人', '50~200 人', '200 人以上']
const currentStep = computed(() => guide.value?.stage === 'APPROVED' ? 3 : guide.value?.stage === 'PENDING_PROFILE' ? 1 : 2)
const form = reactive({ companyName: '', industry: '', scale: '', licenseNo: '', registerAddress: '', legalPerson: '', contactName: '', contactPhone: '', contactEmail: '' })
const rules: FormRules = {
  companyName: [{ required: true, message: '请输入企业名称', trigger: 'blur' }],
  industry: [{ required: true, message: '请选择所属行业', trigger: 'change' }],
  licenseNo: [{ required: true, message: '请输入统一社会信用代码', trigger: 'blur' }, { pattern: /^[0-9A-HJ-NPQRTUWXY]{18}$/, message: '统一社会信用代码格式不正确', trigger: 'blur' }],
  registerAddress: [{ required: true, message: '请输入注册地址', trigger: 'blur' }],
  legalPerson: [{ required: true, message: '请输入法人代表', trigger: 'blur' }],
  contactName: [{ required: true, message: '请输入管理员姓名', trigger: 'blur' }],
  contactPhone: [{ required: true, message: '请输入联系电话', trigger: 'blur' }, { pattern: /^1[3-9]\d{9}$/, message: '联系电话格式不正确', trigger: 'blur' }],
  contactEmail: [{ required: true, message: '请输入企业邮箱', trigger: 'blur' }, { type: 'email', message: '企业邮箱格式不正确', trigger: 'blur' }],
}

onMounted(loadGuide)
onUnmounted(() => { stopTimer(); releasePreview() })

async function loadGuide() {
  loading.value = true
  loadError.value = ''
  try {
    if (!userStore.userId) await userStore.fetchProfile()
    if (userStore.userType === 1) {
      await router.replace('/admin/reviews')
      return
    }
    applyGuide(await getEnterpriseGuide())
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function applyGuide(state: EnterpriseGuideState) {
  guide.value = state
  Object.assign(form, {
    companyName: state.companyName || '', industry: state.industry || '', scale: state.scale || '',
    licenseNo: state.licenseNo || '', registerAddress: state.registerAddress || '', legalPerson: state.legalPerson || '',
    contactName: state.contactName || '', contactPhone: state.contactPhone || '', contactEmail: state.contactEmail || '',
  })
  licenseFileId.value = state.licenseFileId ?? null
  if (licenseFileId.value) void loadPreview(licenseFileId.value)
  if (state.stage === 'PENDING_REVIEW') startTimer(); else stopTimer()
}

function payload(): EnterpriseProfilePayload {
  return { ...form, licenseFileId: licenseFileId.value || '' }
}

async function validate() {
  if (!licenseFileId.value) { ElMessage.warning('请先上传营业执照'); return false }
  return await formRef.value?.validate().catch(() => false) ?? false
}

async function saveDraft() {
  if (!await validate()) return
  saving.value = true
  try { applyGuide(await saveEnterpriseProfile(payload())); ElMessage.success('草稿已保存') } finally { saving.value = false }
}

async function submit() {
  if (!await validate()) return
  submitting.value = true
  try { applyGuide(await submitEnterpriseReview(payload())); ElMessage.success('企业资料已提交') } finally { submitting.value = false }
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!/\.(jpe?g|png|pdf)$/i.test(file.name)) { ElMessage.warning('仅支持 JPG / PNG / PDF'); input.value = ''; return }
  if (file.size > 10 * 1024 * 1024) { ElMessage.warning('文件不能超过 10MB'); input.value = ''; return }
  try {
    const result = await uploadEnterpriseLicense(file)
    licenseFileId.value = result.fileId
    licenseFileName.value = result.fileName
    setPreview(file)
    ElMessage.success('营业执照上传成功')
  } finally { input.value = '' }
}

async function loadPreview(fileId: string) {
  try { setPreview(await fetchEnterpriseLicense(fileId)) } catch { releasePreview() }
}

function setPreview(blob: Blob) {
  releasePreview()
  previewIsImage.value = blob.type.startsWith('image/')
  if (previewIsImage.value) previewUrl.value = URL.createObjectURL(blob)
}

function releasePreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  previewIsImage.value = false
}

function resetLicense() { releasePreview(); licenseFileId.value = null; licenseFileName.value = ''; fileInput.value?.click() }
function startTimer() { stopTimer(); reviewTimer = window.setInterval(refreshReview, import.meta.env.VITE_USE_MOCK === 'true' ? 1000 : 30000) }
function stopTimer() { if (reviewTimer) window.clearInterval(reviewTimer); reviewTimer = undefined }
async function refreshReview() { try { const state = await getEnterpriseGuide(); if (state.stage !== 'PENDING_REVIEW') applyGuide(state) } catch { /* 下次继续 */ } }
function enterWorkspace() {
  if (userStore.tenantCode === 'PLATFORM') {
    userStore.reset()
    localStorage.removeItem(DONE_KEY)
    router.push('/login')
  } else {
    router.push('/channels')
  }
}
function openLogout() { logoutVisible.value = true }
function doLogout() { userStore.reset(); localStorage.removeItem(DONE_KEY); router.push('/login') }
function formatTime(value?: string) { return value ? new Date(value).toLocaleString('zh-CN') : '—' }
</script>

<style scoped>
.guide-page { min-height: 100%; background: radial-gradient(900px 480px at 10% -10%, rgb(59 130 246 / 20%), transparent 60%), #f6f7f9; }
.guide-nav { position: sticky; top: 0; z-index: 10; height: 64px; padding: 0 28px; display: flex; align-items: center; justify-content: space-between; background: rgb(255 255 255 / 92%); border-bottom: 1px solid #e6e8ef; backdrop-filter: blur(10px); }
.brand, .nav-actions, .file-ready, .form-actions { display: flex; align-items: center; gap: 12px; }
.brand img { width: 34px; height: 34px; border-radius: 9px; }.brand div { display: grid; }.brand span, .nav-actions { color: #64748b; font-size: 12px; }
.guide-wrap { width: min(900px, calc(100% - 32px)); margin: 0 auto; padding: 34px 0 60px; }.state-card, .result-card { text-align: center; padding: 44px; }
.steps { display: grid; grid-template-columns: repeat(3, 1fr); margin-bottom: 22px; }.step { display: flex; align-items: center; justify-content: center; gap: 8px; color: #94a3b8; }.step span { width: 28px; height: 28px; display: grid; place-items: center; border: 1px solid #cbd5e1; border-radius: 50%; }.step.active { color: #2563eb; font-weight: 600; }.step.active span { color: #fff; background: #2563eb; border-color: #2563eb; }
.guide-card { border-radius: 16px; }.card-head { display: flex; justify-content: space-between; gap: 20px; margin: 18px 0 22px; }.card-head h1, .result-card h1 { margin: 0; color: #0f172a; font-size: 22px; }.card-head p, .result-card p { margin: 7px 0 0; color: #64748b; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }.inline-selects { width: 100%; display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }.hidden-input { display: none; }
.upload-zone { width: 100%; padding: 28px; border: 1px dashed #93c5fd; border-radius: 12px; background: #f8fbff; color: #2563eb; display: grid; justify-items: center; gap: 7px; cursor: pointer; }.upload-zone span, .file-ready span { color: #94a3b8; font-size: 12px; }.file-ready { width: 100%; padding: 12px; border: 1px solid #dbeafe; border-radius: 12px; }.file-ready img { width: 72px; height: 52px; object-fit: cover; border-radius: 7px; }.file-ready div { flex: 1; display: grid; gap: 4px; }
.privacy-note { display: flex; gap: 7px; padding: 10px 12px; border-radius: 9px; background: #eff6ff; color: #1d4ed8; font-size: 12px; }.form-actions { justify-content: flex-end; margin-top: 20px; }.result-card :deep(.el-descriptions) { max-width: 650px; margin: 24px auto; text-align: left; }
@media (max-width: 680px) { .guide-nav { padding: 0 14px; }.nav-actions > span { display: none; }.form-grid { grid-template-columns: 1fr; }.steps { font-size: 12px; }.step { flex-direction: column; }.card-head { flex-direction: column; }.inline-selects { grid-template-columns: 1fr; } }
</style>
