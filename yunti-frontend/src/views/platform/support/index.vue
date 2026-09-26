<template>
  <div class="support-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">支持工单</h2>
        <p class="page-sub">
          企业提给平台的问题（渠道接入、计费、平台故障…）· 跨租户处理 · 首次响应 与 解决 两段 SLA
        </p>
      </div>
      <div class="head-actions">
        <el-button @click="exportCsv">
          <el-icon class="btn-icon"><Download /></el-icon>
          导出
        </el-button>
        <el-button :loading="loading" @click="refreshAll">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><Tickets /></el-icon></div>
        <div><div class="stat-value">{{ overview?.pending ?? '—' }}</div><div class="stat-label">待处理</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon purple"><el-icon :size="18"><Service /></el-icon></div>
        <div><div class="stat-value">{{ overview?.processing ?? '—' }}</div><div class="stat-label">处理中</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><Clock /></el-icon></div>
        <div><div class="stat-value">{{ overview?.confirming ?? '—' }}</div><div class="stat-label">待企业确认</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber"><el-icon :size="18"><WarningFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.warning ?? '—' }}</div><div class="stat-label">即将超时</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon red"><el-icon :size="18"><BellFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.overdue ?? '—' }}</div><div class="stat-label">已超时</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon green"><el-icon :size="18"><CircleCheckFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.resolvedToday ?? '—' }}</div><div class="stat-label">今日解决</div></div>
      </div>
    </div>
    <el-card shadow="never" class="main-card">
      <div class="filter-row">
        <div class="filter-left">
          <el-input v-model="query.tenant" class="f-tenant" placeholder="租户号（可只填前几位）"
                    clearable @keyup.enter="reloadFromFirstPage" @clear="reloadFromFirstPage" />
          <el-select v-model="query.status" placeholder="全部状态" clearable class="f-select"
                     @change="reloadFromFirstPage">
            <el-option v-for="item in STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-select v-model="query.priority" placeholder="全部优先级" clearable class="f-select"
                     @change="reloadFromFirstPage">
            <el-option v-for="item in PRIORITY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-select v-model="assigneeFilter" placeholder="全部处理人" clearable class="f-select"
                     @change="reloadFromFirstPage">
            <el-option label="我处理的" value="mine" />
            <el-option label="还没人认领" value="unassigned" />
          </el-select>
          <el-input v-model="query.keyword" class="f-search" placeholder="搜工单号 / 主题 / 描述"
                    clearable @keyup.enter="reloadFromFirstPage" @clear="reloadFromFirstPage" />
          <el-button type="primary" plain @click="reloadFromFirstPage">查询</el-button>
        </div>
        <div class="filter-right">
          <span class="mine-hint">我处理的 {{ overview?.mine ?? 0 }} 条</span>
        </div>
      </div>
      <el-table v-loading="loading" :data="rows" :row-class-name="rowClass"
                empty-text="没有企业提交的支持工单" class="support-table">
        <el-table-column type="index" label="序号" width="70" fixed="left" />
        <el-table-column label="工单号" width="180">
          <template #default="{ row }">
            <el-button link type="primary" class="mono" @click="openDetail(row)">{{ row.ticketNo }}</el-button>
          </template>
        </el-table-column>
        <el-table-column label="租户" width="180">
          <template #default="{ row }"><span class="mono">{{ row.tenantCode }}</span></template>
        </el-table-column>
        <el-table-column label="主题" min-width="240">
          <template #default="{ row }">
            <div class="ticket-title">{{ row.title }}</div>
            <div class="cell-sub">{{ row.categoryText }} · 提交人 {{ row.creatorName || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="优先级" width="96">
          <template #default="{ row }">
            <el-tag :type="priorityTag(row.priority)" effect="light" size="small">{{ row.priorityText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" effect="plain" size="small">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="SLA" width="170">
          <template #default="{ row }">
            <div class="sla-cell" :class="`sla-${row.slaState}`">
              <el-icon :size="13"><Timer /></el-icon>
              <span>{{ row.slaStateText }}</span>
            </div>
            <div class="cell-sub">{{ slaDetail(row) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="处理人" width="130">
          <template #default="{ row }">
            <span v-if="row.assigneeName" class="member-cell">
              <span class="member-avatar">{{ (row.assigneeName || '?').slice(0, 1) }}</span>
              {{ row.assigneeName }}
            </span>
            <span v-else class="muted">未认领</span>
          </template>
        </el-table-column>
        <el-table-column label="创建" width="160">
          <template #default="{ row }"><div>{{ row.createTime }}</div></template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="!row.assigneeId && row.status !== 4 && row.status !== 5" link type="primary"
                       size="small" @click="claim(row)">
              认领
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-foot">
        <span class="muted">共 {{ total }} 条 · 按创建时间倒序</span>
        <el-pagination
          class="page-bar"
          background
          layout="total, prev, pager, next, sizes"
          :total="total"
          :current-page="page"
          :page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          @current-change="onPageChange"
          @size-change="onPageSizeChange"
        />
      </div>
    </el-card>
    <!-- 支持工单详情 -->
    <el-drawer v-model="detailOpen" :title="detail ? `支持工单 ${detail.ticket.ticketNo}` : '支持工单'"
               size="760px">
      <div v-if="detail" class="detail-panel">
        <div class="detail-head">
          <div class="detail-title">{{ detail.ticket.title }}</div>
          <div class="detail-badges">
            <el-tag :type="priorityTag(detail.ticket.priority)" effect="light" size="small">
              {{ detail.ticket.priorityText }}
            </el-tag>
            <el-tag :type="statusTag(detail.ticket.status)" effect="plain" size="small">
              {{ detail.ticket.statusText }}
            </el-tag>
            <span class="sla-pill" :class="`sla-${detail.ticket.slaState}`">
              <el-icon :size="13"><Timer /></el-icon>
              {{ detail.ticket.slaStateText }} · {{ slaDetail(detail.ticket) }}
            </span>
          </div>
        </div>
        <div class="sla-track">
          <div class="sla-track-item">
            <div class="track-label">首次响应（{{ detail.sla.firstResponseMinutes }} 分钟内）</div>
            <div class="track-value" :class="{ done: detail.ticket.firstResponded }">
              {{ detail.ticket.firstResponded ? '已响应' : `截止 ${detail.ticket.firstResponseDue || '—'}` }}
            </div>
          </div>
          <div class="sla-track-item">
            <div class="track-label">解决（{{ formatMinutes(detail.sla.resolveMinutes) }}内）</div>
            <div class="track-value" :class="{ done: detail.ticket.status === 4 || detail.ticket.status === 5 }">
              {{ detail.ticket.finishText || `截止 ${detail.ticket.resolveDue || '—'}` }}
            </div>
          </div>
        </div>
        <div class="info-card">
          <div class="info-row"><span>提交企业</span><span class="v mono">{{ detail.tenantCode }}</span></div>
          <div class="info-row"><span>提交人</span><span class="v">{{ detail.ticket.creatorName || '—' }}</span></div>
          <div class="info-row"><span>分类</span><span class="v">{{ detail.ticket.categoryText }}</span></div>
          <div class="info-row">
            <span>关联会话</span>
            <span class="v">{{ detail.ticket.sessionNo || '—（企业直接提交）' }}</span>
          </div>
          <div class="info-row">
            <span>处理人</span>
            <span class="v">{{ detail.ticket.assigneeName || '未认领' }}</span>
          </div>
          <div class="info-row">
            <span>创建</span><span class="v">{{ detail.ticket.createTime }}</span>
          </div>
        </div>
        <div class="detail-section-title">问题描述</div>
        <pre class="detail-content">{{ detail.content || '（企业没有填写描述）' }}</pre>
        <div class="detail-section-title">
          流转记录
          <span class="section-sub">平台与企业双方都能看到这条时间线</span>
        </div>
        <el-timeline class="event-line">
          <el-timeline-item
            v-for="event in detail.events"
            :key="event.id"
            :timestamp="event.eventTime"
            placement="top"
            :type="eventDot(event)"
            :hollow="event.eventType === 3"
          >
            <div class="event-head">
              <span class="event-type">{{ event.eventTypeText }}</span>
              <span class="event-who">{{ event.operatorName || '系统' }}</span>
              <span v-if="event.eventType === 7 && event.toStatusText" class="event-flow">
                {{ event.fromStatusText }} → {{ event.toStatusText }}
              </span>
              <span v-if="event.eventType === 3 && !event.visibleToCustomer" class="event-note">内部备注</span>
            </div>
            <div v-if="event.content" class="event-content">{{ event.content }}</div>
          </el-timeline-item>
        </el-timeline>
        <div class="detail-section-title">平台回复</div>
        <div v-if="detail.ticket.status === 4 || detail.ticket.status === 5" class="closed-tip">
          工单{{ detail.ticket.statusText }}了，还需要跟进请先「重新打开」。
        </div>
        <el-input
          v-model="replyForm.content"
          type="textarea"
          :rows="3"
          maxlength="512"
          show-word-limit
          :disabled="detail.ticket.status === 4 || detail.ticket.status === 5"
          placeholder="回复企业（会算作首次响应）；勾掉「企业可见」则只作为平台内部备注"
        />
        <div class="reply-actions">
          <el-checkbox v-model="replyForm.visibleToCustomer"
                       :disabled="detail.ticket.status === 4 || detail.ticket.status === 5">
            企业可见
          </el-checkbox>
          <div class="spacer" />
          <el-button type="primary" :loading="acting"
                     :disabled="detail.ticket.status === 4 || detail.ticket.status === 5"
                     @click="submitReply">
            提交回复
          </el-button>
        </div>
      </div>
      <template #footer>
        <div v-if="detail" class="drawer-foot">
          <el-button v-if="!detail.ticket.assigneeId" :loading="acting" @click="claim(detail.ticket)">
            认领
          </el-button>
          <el-button v-if="detail.ticket.status !== 3 && detail.ticket.status !== 4 && detail.ticket.status !== 5"
                     :loading="acting" @click="openStatus(3)">
            待企业确认
          </el-button>
          <el-button v-if="detail.ticket.status !== 4 && detail.ticket.status !== 5" :loading="acting"
                     @click="escalate">
            升级
          </el-button>
          <el-button v-if="detail.ticket.status !== 4 && detail.ticket.status !== 5" type="primary"
                     :loading="acting" @click="openStatus(4)">
            标记已解决
          </el-button>
          <span class="spacer" />
          <el-button v-if="detail.ticket.status === 5" :loading="acting" @click="openStatus(2)">
            重新打开
          </el-button>
          <el-button v-else type="danger" plain :loading="acting" @click="openStatus(5)">关闭</el-button>
        </div>
      </template>
    </el-drawer>
    <el-dialog v-model="statusOpen" :title="statusForm.actionText" width="460px">
      <el-form label-width="86px">
        <el-form-item label="备注">
          <el-input v-model="statusForm.remark" type="textarea" :rows="3" maxlength="200"
                    placeholder="写一句说明，流转记录里会留痕（企业也看得到）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="statusOpen = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="submitStatus">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  BellFilled,
  CircleCheckFilled,
  Clock,
  Download,
  Refresh,
  Service,
  Tickets,
  Timer,
  WarningFilled,
} from '@element-plus/icons-vue'
import {
  changePlatformTicketStatus,
  claimPlatformTicket,
  downloadPlatformTicketsCsv,
  escalatePlatformTicket,
  fetchPlatformTicketDetail,
  fetchPlatformTicketOverview,
  fetchPlatformTickets,
  replyPlatformTicket,
  type PlatformTicketOverview,
  type PlatformTicketQuery,
} from '../../../api/platform/ticket'
import type { TicketDetail, TicketItem } from '../../../api/customer/ticket'
import { ElMessageBox } from 'element-plus'

const STATUS_OPTIONS = [
  { value: 1, label: '待处理' },
  { value: 2, label: '处理中' },
  { value: 3, label: '待企业确认' },
  { value: 4, label: '已解决' },
  { value: 5, label: '已关闭' },
]
const PRIORITY_OPTIONS = [
  { value: 1, label: '低' },
  { value: 2, label: '中' },
  { value: 3, label: '高' },
  { value: 4, label: '紧急' },
]

const loading = ref(false)
const acting = ref(false)
const overview = ref<PlatformTicketOverview | null>(null)
const rows = ref<TicketItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
const assigneeFilter = ref('')

const query = reactive<PlatformTicketQuery>({
  tenant: '',
  status: undefined,
  priority: undefined,
  keyword: '',
})

const detailOpen = ref(false)
const detail = ref<TicketDetail | null>(null)
const replyForm = reactive({ content: '', visibleToCustomer: true })

const statusOpen = ref(false)
const statusForm = reactive({ status: 4, remark: '', actionText: '标记已解决' })

let timer: number | undefined

async function loadOverview() {
  try {
    overview.value = await fetchPlatformTicketOverview()
  } catch {
    // 看板拿不到不影响列表
  }
}

async function loadList() {
  loading.value = true
  try {
    const result = await fetchPlatformTickets({
      ...query,
      tenant: query.tenant?.trim() || undefined,
      keyword: query.keyword?.trim() || undefined,
      mineOnly: assigneeFilter.value === 'mine' || undefined,
      unassignedOnly: assigneeFilter.value === 'unassigned' || undefined,
      page: page.value,
      pageSize: pageSize.value,
    })
    rows.value = result.list ?? []
    total.value = Number(result.total) || 0
  } finally {
    loading.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadOverview(), loadList()])
}

function reloadFromFirstPage() {
  page.value = 1
  void loadList()
}

function onPageChange(next: number) {
  page.value = next
  void loadList()
}

function onPageSizeChange(size: number) {
  pageSize.value = size
  page.value = 1
  void loadList()
}

onMounted(async () => {
  await refreshAll()
  timer = window.setInterval(loadOverview, 30_000)
})

onUnmounted(() => {
  if (timer) {
    window.clearInterval(timer)
  }
})

function priorityTag(priority: number) {
  if (priority === 4) return 'danger'
  if (priority === 3) return 'warning'
  if (priority === 2) return 'primary'
  return 'info'
}

function statusTag(status: number) {
  if (status === 1) return 'warning'
  if (status === 2) return 'primary'
  if (status === 3) return 'info'
  if (status === 4) return 'success'
  return 'info'
}

function rowClass({ row }: { row: TicketItem }) {
  if (row.slaState === 3) return 'row-overdue'
  if (row.slaState === 2) return 'row-warning'
  return ''
}

function eventDot(event: { eventType: number }) {
  if (event.eventType === 8) return 'danger'
  if (event.eventType === 1) return 'primary'
  if (event.eventType === 2) return 'warning'
  if (event.eventType === 3) return 'success'
  return 'info'
}

function formatMinutes(minutes?: number | null) {
  if (minutes === undefined || minutes === null) {
    return '—'
  }
  const value = Math.abs(minutes)
  if (value < 60) return `${value} 分钟`
  if (value < 60 * 24) {
    const hours = Math.floor(value / 60)
    const rest = value % 60
    return rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`
  }
  const days = Math.floor(value / (60 * 24))
  const hours = Math.floor((value % (60 * 24)) / 60)
  return hours ? `${days} 天 ${hours} 小时` : `${days} 天`
}

function slaDetail(row: TicketItem) {
  if (row.status === 4 || row.status === 5) {
    return row.finishText || '已办结'
  }
  const remain = row.remainMinutes
  if (remain === undefined || remain === null) {
    return '—'
  }
  return remain >= 0 ? `还剩 ${formatMinutes(remain)}` : `已超 ${formatMinutes(-remain)}`
}

async function openDetail(row: TicketItem) {
  if (!row.tenantCode) {
    ElMessage.warning('这条工单没有租户号，无法打开')
    return
  }
  detail.value = await fetchPlatformTicketDetail(row.ticketNo, row.tenantCode)
  replyForm.content = ''
  replyForm.visibleToCustomer = true
  detailOpen.value = true
}

async function claim(row: TicketItem) {
  if (!row.tenantCode) {
    return
  }
  acting.value = true
  try {
    await claimPlatformTicket(row.ticketNo, row.tenantCode)
    ElMessage.success('已认领，工单进入处理中')
    await refreshAll()
    if (detailOpen.value) {
      detail.value = await fetchPlatformTicketDetail(row.ticketNo, row.tenantCode)
    }
  } finally {
    acting.value = false
  }
}

async function submitReply() {
  if (!detail.value || !detail.value.tenantCode) {
    return
  }
  if (!replyForm.content.trim()) {
    ElMessage.warning('请先写回复内容')
    return
  }
  acting.value = true
  try {
    const { ticketNo } = detail.value.ticket
    detail.value = await replyPlatformTicket(ticketNo, detail.value.tenantCode, {
      content: replyForm.content.trim(),
      visibleToCustomer: replyForm.visibleToCustomer,
    })
    ElMessage.success(replyForm.visibleToCustomer ? '已回复企业（计入首次响应）' : '已记录平台内部备注')
    replyForm.content = ''
    await refreshAll()
  } finally {
    acting.value = false
  }
}

function openStatus(status: number) {
  statusForm.status = status
  statusForm.remark = ''
  statusForm.actionText = status === 5 ? '关闭支持工单'
    : status === 4 ? '标记已解决' : status === 3 ? '标记待企业确认' : '重新打开'
  statusOpen.value = true
}

async function submitStatus() {
  if (!detail.value || !detail.value.tenantCode) {
    return
  }
  acting.value = true
  try {
    const { ticketNo } = detail.value.ticket
    detail.value = await changePlatformTicketStatus(ticketNo, detail.value.tenantCode, {
      status: statusForm.status,
      remark: statusForm.remark.trim() || undefined,
    })
    ElMessage.success('状态已更新')
    statusOpen.value = false
    await refreshAll()
  } finally {
    acting.value = false
  }
}

/**
 * 平台升级：一线处理不动就提优先级，同时通知企业（企业消息中心也能看到）。
 * 和企业侧同口径：不重算 SLA 截止时间。
 */
async function escalate() {
  if (!detail.value || !detail.value.tenantCode) {
    return
  }
  let remark = ''
  try {
    const result = await ElMessageBox.prompt(
      '升级会把优先级提一档（最高到紧急），并通知企业；SLA 截止时间不变。',
      '升级支持工单',
      {
        inputPlaceholder: '升级原因（可选，例如：影响多个租户 / 需要平台二线介入）',
        confirmButtonText: '确认升级',
        cancelButtonText: '取消',
      },
    )
    remark = String(result.value || '')
  } catch {
    return
  }
  acting.value = true
  try {
    const { ticketNo } = detail.value.ticket
    detail.value = await escalatePlatformTicket(ticketNo, detail.value.tenantCode, {
      remark: remark.trim() || undefined,
    })
    ElMessage.success('已升级，并已通知提交企业')
    await refreshAll()
  } finally {
    acting.value = false
  }
}

/** 导出当前筛选的支持工单（跨租户，带租户号） */
async function exportCsv() {
  try {
    await downloadPlatformTicketsCsv({
      ...query,
      tenant: query.tenant?.trim() || undefined,
      keyword: query.keyword?.trim() || undefined,
      mineOnly: assigneeFilter.value === 'mine' || undefined,
      unassignedOnly: assigneeFilter.value === 'unassigned' || undefined,
    })
    ElMessage.success('已开始下载')
  } catch {
    ElMessage.error('导出失败，请稍后再试')
  }
}
</script>
<style scoped>
.support-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; gap: 12px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.head-actions { display: flex; align-items: center; gap: 12px; }
.btn-icon { margin-right: 4px; }
.stat-row { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin-bottom: 14px; }
.stat-card { display: flex; align-items: center; gap: 12px; background: #fff; border: 1px solid #e6e8ef; border-radius: 14px; padding: 14px 16px; }
.stat-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.stat-icon.blue { background: #eef4ff; color: #2563eb; }
.stat-icon.amber { background: #fff7ed; color: #d97706; }
.stat-icon.green { background: #ecfdf5; color: #059669; }
.stat-icon.red { background: #fef2f2; color: #dc2626; }
.stat-icon.purple { background: #f5f3ff; color: #7c3aed; }
.stat-value { font-size: 22px; font-weight: 800; color: #0f172a; line-height: 1.2; }
.stat-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }
.main-card { width: 100%; }
.filter-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; flex-wrap: wrap; }
.filter-left { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.f-tenant { width: 210px; }
.f-select { width: 138px; }
.f-search { width: 220px; }
.mine-hint { font-size: 13px; color: #94a3b8; }
.support-table :deep(.row-overdue) { background: #fff7f7; }
.support-table :deep(.row-warning) { background: #fffdf5; }
.ticket-title { font-weight: 600; color: #1e293b; }
.muted { color: #94a3b8; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.cell-sub { color: #94a3b8; font-size: 12px; margin-top: 2px; }
.member-cell { display: inline-flex; align-items: center; gap: 6px; color: #475569; }
.member-avatar { width: 24px; height: 24px; border-radius: 7px; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-weight: 700; background: linear-gradient(135deg, #1d4ed8, #3b82f6); font-size: 12px; }
.sla-cell { display: inline-flex; align-items: center; gap: 4px; font-weight: 600; font-size: 13px; }
.sla-1 { color: #16a34a; }
.sla-2 { color: #d97706; }
.sla-3 { color: #dc2626; }
.table-foot { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 12px; font-size: 12px; flex-wrap: wrap; }
.page-bar { margin-left: auto; }
.detail-panel { padding: 0 4px 8px; }
.detail-head { display: flex; flex-direction: column; gap: 10px; }
.detail-title { font-size: 17px; font-weight: 700; color: #0f172a; }
.detail-badges { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sla-pill { display: inline-flex; align-items: center; gap: 4px; border-radius: 999px; padding: 3px 10px; font-size: 12px; font-weight: 600; background: #f1f5f9; }
.sla-track { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 14px 0; }
.sla-track-item { border: 1px solid #e6e8ef; border-radius: 12px; padding: 12px 14px; background: #f8fafc; }
.track-label { font-size: 12px; color: #94a3b8; }
.track-value { margin-top: 6px; font-weight: 700; color: #b45309; }
.track-value.done { color: #16a34a; }
.info-card { background: #f8fafc; border: 1px solid #e6e8ef; border-radius: 12px; padding: 6px 16px; }
.info-row { display: flex; justify-content: space-between; gap: 20px; padding: 9px 0; color: #475569; font-size: 13px; }
.info-row + .info-row { border-top: 1px dashed #e2e8f0; }
.info-row .v { color: #0f172a; text-align: right; }
.detail-section-title { margin: 18px 0 10px; font-weight: 700; color: #0f172a; font-size: 14px; }
.section-sub { margin-left: 8px; font-weight: 400; font-size: 12px; color: #94a3b8; }
.detail-content { margin: 0; padding: 12px 14px; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 10px; font-size: 13px; color: #334155; line-height: 1.8; white-space: pre-wrap; word-break: break-word; font-family: inherit; }
.event-line { padding-left: 2px; }
.event-head { display: flex; align-items: center; gap: 8px; }
.event-type { font-weight: 700; color: #1e293b; font-size: 13px; }
.event-who { color: #64748b; font-size: 12px; }
.event-flow { color: #2563eb; font-size: 12px; }
.event-note { color: #b45309; background: #fff7ed; border-radius: 4px; padding: 0 6px; font-size: 11px; }
.event-content { margin-top: 4px; font-size: 13px; color: #334155; white-space: pre-wrap; word-break: break-word; }
.reply-actions { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.spacer { flex: 1; }
.drawer-foot { display: flex; align-items: center; gap: 10px; width: 100%; }
.closed-tip { margin-bottom: 8px; padding: 8px 12px; border-radius: 8px; background: #f1f5f9; color: #64748b; font-size: 12px; }
</style>
