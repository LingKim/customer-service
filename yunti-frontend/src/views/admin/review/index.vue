<template>
  <div class="review-page">
    <div class="page-head">
      <div><h1>企业审核</h1><p>核对企业资料与营业执照后处理申请。</p></div>
    </div>
    <el-card shadow="never">
      <div class="filters">
        <el-select v-model="statusFilter" placeholder="全部状态" clearable @change="search">
          <el-option label="待审核" :value="1" />
          <el-option label="已通过" :value="2" />
          <el-option label="已驳回" :value="3" />
        </el-select>
        <el-input v-model.trim="keyword" placeholder="企业名称、申请单号、联系人" clearable @keyup.enter="search" />
        <el-button type="primary" @click="search">查询</el-button>
      </div>
      <el-table v-loading="loading" :data="records" empty-text="暂无审核申请">
        <el-table-column prop="applyNo" label="申请单号" min-width="185" />
        <el-table-column prop="companyName" label="企业名称" min-width="180" />
        <el-table-column prop="contactName" label="联系人" min-width="100" />
        <el-table-column label="状态" width="105">
          <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="submitTime" label="提交时间" min-width="165" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }"><el-button link type="primary" @click="openDetail(row.id)">详情</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-model:current-page="pageNum" v-model:page-size="pageSize"
        :total="total" :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next"
        @current-change="loadPage" @size-change="loadPage"
      />
    </el-card>

    <el-drawer v-model="detailVisible" title="审核申请详情" size="min(620px, 100%)">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="申请单号">{{ detail.applyNo }}</el-descriptions-item>
          <el-descriptions-item label="申请版本">第 {{ detail.versionNo }} 次</el-descriptions-item>
          <el-descriptions-item label="企业名称">{{ detail.companyName }}</el-descriptions-item>
          <el-descriptions-item label="统一社会信用代码">{{ detail.licenseNo || '—' }}</el-descriptions-item>
          <el-descriptions-item label="所属行业">{{ detail.industry || '—' }}</el-descriptions-item>
          <el-descriptions-item label="团队规模">{{ detail.scale || '—' }}</el-descriptions-item>
          <el-descriptions-item label="注册地址">{{ detail.registerAddress || '—' }}</el-descriptions-item>
          <el-descriptions-item label="法人代表">{{ detail.legalPerson || '—' }}</el-descriptions-item>
          <el-descriptions-item label="联系人">{{ detail.contactName }}</el-descriptions-item>
          <el-descriptions-item label="联系电话">{{ detail.contactPhone || '—' }}</el-descriptions-item>
          <el-descriptions-item label="联系邮箱">{{ detail.contactEmail || '—' }}</el-descriptions-item>
          <el-descriptions-item label="营业执照">
            <el-button v-if="detail.licenseFileId" link type="primary" :loading="fileLoading" @click="openLicense">查看附件</el-button>
            <span v-else>未上传</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.rejectReason" label="驳回原因">{{ detail.rejectReason }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.tenantCode" label="租户编码">{{ detail.tenantCode }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="detail.status === 1" class="actions">
          <el-button type="danger" plain :loading="saving" @click="rejectVisible = true">驳回</el-button>
          <el-button type="primary" :loading="saving" @click="approve">审核通过</el-button>
        </div>
        <div v-else-if="detail.status === 2" class="actions">
          <el-button type="primary" plain :loading="saving" @click="approve">重试用户归属同步</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="rejectVisible" title="驳回申请" width="440px">
      <el-input v-model.trim="rejectReason" type="textarea" :rows="4" maxlength="512" show-word-limit placeholder="请输入驳回原因" />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :loading="saving" :disabled="!rejectReason" @click="reject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchEnterpriseLicense } from '../../../api/enterprise'
import {
  approveEnterpriseReview, getEnterpriseReview, pageEnterpriseReviews, rejectEnterpriseReview,
  type EnterpriseReviewItem,
} from '../../../api/admin/review'

const loading = ref(false)
const saving = ref(false)
const fileLoading = ref(false)
const records = ref<EnterpriseReviewItem[]>([])
const detail = ref<EnterpriseReviewItem | null>(null)
const detailVisible = ref(false)
const rejectVisible = ref(false)
const rejectReason = ref('')
const statusFilter = ref<number | undefined>(1)
const keyword = ref('')
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

onMounted(loadPage)

function statusText(status: number) { return status === 1 ? '待审核' : status === 2 ? '已通过' : '已驳回' }
function statusType(status: number): 'warning' | 'success' | 'danger' {
  return status === 1 ? 'warning' : status === 2 ? 'success' : 'danger'
}
function search() { pageNum.value = 1; void loadPage() }

async function loadPage() {
  loading.value = true
  try {
    const page = await pageEnterpriseReviews({
      pageNum: pageNum.value, pageSize: pageSize.value,
      status: statusFilter.value, keyword: keyword.value || undefined,
    })
    records.value = page.records
    total.value = Number(page.total)
  } finally { loading.value = false }
}

async function openDetail(reviewId: string) {
  detail.value = await getEnterpriseReview(reviewId)
  detailVisible.value = true
}

async function openLicense() {
  if (!detail.value?.licenseFileId) return
  fileLoading.value = true
  try {
    const blob = await fetchEnterpriseLicense(detail.value.licenseFileId)
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank', 'noopener,noreferrer')
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } finally { fileLoading.value = false }
}

async function approve() {
  if (!detail.value) return
  try { await ElMessageBox.confirm('确认通过这份企业申请？', '审核通过', { type: 'warning' }) } catch { return }
  saving.value = true
  try {
    detail.value = await approveEnterpriseReview(detail.value.id)
    ElMessage.success('审核通过，用户租户归属已同步')
    await loadPage()
  } finally { saving.value = false }
}

async function reject() {
  if (!detail.value || !rejectReason.value) return
  saving.value = true
  try {
    detail.value = await rejectEnterpriseReview(detail.value.id, rejectReason.value)
    rejectVisible.value = false
    rejectReason.value = ''
    ElMessage.success('申请已驳回')
    await loadPage()
  } finally { saving.value = false }
}
</script>

<style scoped>
.review-page { max-width: 1400px; margin: 0 auto; }
.page-head { margin-bottom: 20px; }
.page-head h1 { margin: 0 0 5px; font-size: 23px; color: #172033; }
.page-head p { margin: 0; color: #768197; }
.filters { display: flex; gap: 12px; margin-bottom: 18px; }
.filters .el-select { width: 140px; }
.filters .el-input { width: min(330px, 100%); }
.el-pagination { justify-content: flex-end; margin-top: 20px; }
.actions { display: flex; justify-content: flex-end; margin-top: 24px; }
@media (max-width: 650px) { .filters { flex-wrap: wrap; } }
</style>
