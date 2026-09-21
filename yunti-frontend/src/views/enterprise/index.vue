<template>
  <div class="enterprise-page">
    <div class="page-head">
      <div><h1>我的企业</h1><p>查看企业资料与开通审核状态</p></div>
      <el-button v-if="guide?.stage === 'PENDING_PROFILE' || guide?.stage === 'REJECTED'" type="primary" @click="router.push('/guide')">去完善企业信息</el-button>
    </div>
    <el-card v-if="loading" class="loading" shadow="never"><el-icon class="is-loading" :size="28"><Loading /></el-icon><p>正在加载企业信息…</p></el-card>
    <el-alert v-else-if="error" :title="error" type="error" show-icon :closable="false" />

    <template v-else-if="guide">
      <el-card class="status-card" shadow="never">
        <div class="status-body">
          <div class="status-icon" :class="stageMeta.type"><el-icon :size="30"><component :is="stageMeta.icon" /></el-icon></div>
          <div class="status-info"><h2>{{ stageMeta.title }}</h2><p>{{ stageMeta.description }}</p><span v-if="guide.tenantCode">租户编码：{{ guide.tenantCode }}</span></div>
          <el-tag :type="stageMeta.tagType" size="large">{{ stageMeta.badge }}</el-tag>
        </div>
      </el-card>

      <div class="info-grid">
        <el-card shadow="never">
          <template #header><strong>企业资料</strong></template>
          <div class="info-list">
            <InfoRow label="企业名称" :value="guide.companyName" />
            <InfoRow label="统一社会信用代码" :value="guide.licenseNo" />
            <InfoRow label="注册地址" :value="guide.registerAddress" />
            <InfoRow label="法人代表" :value="guide.legalPerson" />
            <InfoRow label="行业 / 规模" :value="`${guide.industry || '—'} / ${guide.scale || '—'}`" />
            <InfoRow label="管理员" :value="guide.contactName" />
          </div>
        </el-card>
        <el-card shadow="never">
          <template #header><strong>审核与营业执照</strong></template>
          <div class="info-list">
            <InfoRow label="企业编码" :value="guide.enterpriseCode" mono />
            <InfoRow label="申请单号" :value="guide.applyNo" mono />
            <InfoRow label="提交版本" :value="guide.versionNo ? `第 ${guide.versionNo} 次` : '—'" />
            <div class="info-row"><span>营业执照</span><el-button v-if="guide.licenseFileId" link type="primary" @click="openLicense">预览附件</el-button><strong v-else>未上传</strong></div>
            <div v-if="guide.stage === 'REJECTED'" class="reject"><strong>驳回原因</strong><p>{{ guide.rejectReason || '资料核验未通过，请修改后重新提交' }}</p></div>
          </div>
        </el-card>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchEnterpriseLicense, getEnterpriseGuide } from '../../api/enterprise'
import type { EnterpriseGuideState } from '../../types'

const InfoRow = defineComponent({
  props: { label: { type: String, required: true }, value: String, mono: Boolean },
  setup: (props) => () => h('div', { class: 'info-row' }, [h('span', props.label), h('strong', { class: props.mono ? 'mono' : '' }, props.value || '—')]),
})
const router = useRouter()
const loading = ref(true)
const error = ref('')
const guide = ref<EnterpriseGuideState | null>(null)
const temporaryUrls = new Set<string>()

const stageMeta = computed(() => {
  if (guide.value?.stage === 'PENDING_REVIEW') return { type: 'pending', icon: 'Clock', title: '企业资料审核中', description: '运营正在核验营业执照与法人信息', badge: '审核中', tagType: 'warning' as const }
  if (guide.value?.stage === 'REJECTED') return { type: 'rejected', icon: 'CircleCloseFilled', title: '审核未通过', description: '请根据驳回原因修改资料后重新提交', badge: '已驳回', tagType: 'danger' as const }
  if (guide.value?.stage === 'APPROVED') return { type: 'approved', icon: 'CircleCheckFilled', title: '企业已开通', description: '审核已通过，企业租户已就绪', badge: '已开通', tagType: 'success' as const }
  return { type: 'todo', icon: 'EditPen', title: '企业资料待完善', description: '完成企业资料填写后提交运营审核', badge: '待完善', tagType: 'info' as const }
})

onMounted(load)
onUnmounted(releaseTemporaryUrls)

async function load() {
  try { guide.value = await getEnterpriseGuide() }
  catch (reason) { error.value = reason instanceof Error ? reason.message : '加载失败' }
  finally { loading.value = false }
}

async function openLicense() {
  if (!guide.value?.licenseFileId) return
  try {
    const blob = await fetchEnterpriseLicense(guide.value.licenseFileId)
    const url = URL.createObjectURL(blob)
    temporaryUrls.add(url)
    window.open(url, '_blank', 'noopener,noreferrer')
    window.setTimeout(() => { URL.revokeObjectURL(url); temporaryUrls.delete(url) }, 60000)
  } catch { ElMessage.error('营业执照加载失败') }
}

function releaseTemporaryUrls() {
  temporaryUrls.forEach((url) => URL.revokeObjectURL(url))
  temporaryUrls.clear()
}
</script>

<style scoped>
.enterprise-page { max-width: 1000px; margin: 0 auto; }.page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 18px; }.page-head h1 { margin: 0; color: #0f172a; font-size: 22px; }.page-head p { margin: 6px 0 0; color: #94a3b8; font-size: 13px; }.loading { padding: 48px; text-align: center; color: #64748b; }
.status-card { margin-bottom: 18px; }.status-body { display: flex; align-items: center; gap: 16px; }.status-icon { width: 60px; height: 60px; display: grid; place-items: center; flex: none; border-radius: 16px; }.status-icon.pending { color: #f59e0b; background: #fff7ed; }.status-icon.rejected { color: #dc2626; background: #fef2f2; }.status-icon.approved { color: #10b981; background: #ecfdf5; }.status-icon.todo { color: #2563eb; background: #eff6ff; }.status-info { flex: 1; }.status-info h2 { margin: 0; color: #0f172a; font-size: 18px; }.status-info p { margin: 5px 0; color: #64748b; font-size: 13px; }.status-info span { color: #2563eb; font: 13px ui-monospace, SFMono-Regular, Menlo, monospace; }
.info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }.info-list { display: grid; }.info-list :deep(.info-row), .info-row { min-height: 44px; padding: 11px 0; border-bottom: 1px dashed #eef2f7; display: flex; justify-content: space-between; gap: 14px; font-size: 13px; }.info-list :deep(.info-row span), .info-row span { color: #94a3b8; }.info-list :deep(.info-row strong), .info-row strong { color: #1e293b; text-align: right; word-break: break-all; }.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }.reject { margin-top: 12px; padding: 12px; border: 1px solid #fecaca; border-radius: 10px; background: #fef2f2; color: #b91c1c; }.reject p { margin: 5px 0 0; font-size: 12px; line-height: 1.6; }
@media (max-width: 820px) { .info-grid { grid-template-columns: 1fr; }.status-body { align-items: flex-start; flex-wrap: wrap; }.page-head { flex-direction: column; } }
</style>
