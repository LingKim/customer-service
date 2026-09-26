<template>
  <div class="alerts-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">实时预警</h2>
        <p class="page-sub">
          边聊边检：坐席和客户每发一句话就过一遍规则，命中敏感词 / 绝对化承诺 / 情绪未安抚的，
          当场推给坐席，并在这里留痕
        </p>
      </div>
      <div class="head-actions">
        <span v-if="overview" class="pending-badge">待处理 {{ overview.alertPending }} 条</span>
        <el-button @click="router.push('/modules/qa')">
          <el-icon class="btn-icon"><DataAnalysis /></el-icon>
          质检中心
        </el-button>
        <el-button type="primary" :loading="loading" @click="load()">
          <el-icon class="btn-icon"><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>
    <div class="stat-row">
      <div class="stat-card">
        <div class="stat-icon orange"><el-icon :size="18"><BellFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.alertTotal ?? '—' }}</div><div class="stat-label">累计预警</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon amber"><el-icon :size="18"><Clock /></el-icon></div>
        <div><div class="stat-value">{{ overview?.alertPending ?? '—' }}</div><div class="stat-label">待处理</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon red"><el-icon :size="18"><WarningFilled /></el-icon></div>
        <div><div class="stat-value">{{ overview?.alertSevere ?? '—' }}</div><div class="stat-label">严重级别</div></div>
      </div>
      <div class="stat-card">
        <div class="stat-icon blue"><el-icon :size="18"><List /></el-icon></div>
        <div><div class="stat-value">{{ alerts.length }}</div><div class="stat-label">当前结果</div></div>
      </div>
    </div>
    <el-card shadow="never" class="filter-card">
      <el-form class="filter-form">
        <el-form-item>
          <el-radio-group v-model="query.status" @change="applyFilter">
            <el-radio-button :value="0">全部</el-radio-button>
            <el-radio-button :value="1">待处理</el-radio-button>
            <el-radio-button :value="2">已处理</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.severity" class="w-140" placeholder="全部级别" @change="applyFilter">
            <el-option :value="0" label="全部级别" />
            <el-option :value="3" label="严重" />
            <el-option :value="2" label="警告" />
            <el-option :value="1" label="提示" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-select v-model="query.ruleType" class="w-140" placeholder="全部类型" @change="applyFilter">
            <el-option :value="0" label="全部类型" />
            <el-option v-for="item in ruleTypes" :key="item.value" :value="item.value" :label="item.label" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="query.keyword"
            class="w-240"
            clearable
            placeholder="会话号 / 规则名 / 命中内容"
            @keydown.enter="applyFilter"
          />
        </el-form-item>
        <el-form-item>
          <el-date-picker
            v-model="query.range"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            :default-time="defaultRangeTime"
            class="w-300"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="applyFilter">查询</el-button>
          <el-button @click="resetFilter">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
    <el-card shadow="never" class="main-card">
      <el-table v-loading="loading" :data="pagedAlerts" stripe>
        <el-table-column type="index" label="序号" width="70" :index="rowIndexOf" />
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag
              size="small"
              effect="dark"
              :type="row.severity >= 3 ? 'danger' : row.severity === 1 ? 'info' : 'warning'"
            >
              {{ row.severityText }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ruleName" label="命中规则" width="140" show-overflow-tooltip />
        <el-table-column label="规则类型" width="110">
          <template #default="{ row }">{{ row.ruleTypeText }}</template>
        </el-table-column>
        <el-table-column label="命中内容" min-width="300">
          <template #default="{ row }">
            <div class="alert-snippet">
              <span v-if="row.hitKeyword" class="hit-keyword">{{ row.hitKeyword }}</span>
              {{ row.snippet || '—' }}
            </div>
            <div v-if="row.advice" class="alert-advice">处置建议：{{ row.advice }}</div>
          </template>
        </el-table-column>
        <el-table-column label="会话号" width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.sessionNo }}</span>
          </template>
        </el-table-column>
        <el-table-column label="负责坐席" width="110">
          <template #default="{ row }">{{ row.agentName || '未接入' }}</template>
        </el-table-column>
        <el-table-column label="发生时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="170" fixed="right">
          <template #default="{ row }">
            <el-tag v-if="row.status === 2" size="small" type="success" effect="light">已处理</el-tag>
            <el-button v-else link type="primary" @click="openHandle(row)">标记已处理</el-button>
          </template>
        </el-table-column>
        <el-table-column label="处理信息" width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.status === 2">
              {{ row.handlerName || '—' }} · {{ formatTime(row.handleTime) }}
            </span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager-row">
        <span class="pager-tip">共 {{ alerts.length }} 条</span>
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="alerts.length"
          layout="prev, pager, next"
          background
        />
      </div>
    </el-card>
    <el-dialog v-model="handleVisible" title="标记预警已处理" width="460px">
      <div class="dialog-tip">
        {{ current?.ruleName }}：{{ current?.snippet || '' }}
      </div>
      <el-input
        v-model="handleRemark"
        type="textarea"
        :rows="3"
        maxlength="512"
        show-word-limit
        placeholder="处理说明，例如：已提醒坐席改用规范话术并回访客户"
      />
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitHandle">确认已处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  fetchQaAlerts,
  fetchQaOverview,
  handleQaAlert,
  type QaAlertItem,
  type QaOverview,
} from '../../api/customer/qa'

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const alerts = ref<QaAlertItem[]>([])
const overview = ref<QaOverview | null>(null)

const page = ref(1)
const pageSize = 10

const handleVisible = ref(false)
const handleRemark = ref('')
const current = ref<QaAlertItem | null>(null)

const query = reactive({
  /** 0-全部、1-待处理、2-已处理 */
  status: 0,
  /** 0-全部级别 */
  severity: 0,
  /** 0-全部类型 */
  ruleType: 0,
  keyword: '',
  /** 时间范围：[开始, 结束]，格式 yyyy-MM-dd HH:mm:ss */
  range: null as [string, string] | null,
})

const defaultRangeTime: [Date, Date] = [
  new Date(2000, 0, 1, 0, 0, 0),
  new Date(2000, 0, 1, 23, 59, 59),
]

const ruleTypes = [
  { value: 1, label: '敏感词' },
  { value: 2, label: '承诺规范' },
  { value: 3, label: '必答项' },
  { value: 4, label: '情绪识别' },
]

const pagedAlerts = computed(() => {
  const start = (page.value - 1) * pageSize
  return alerts.value.slice(start, start + pageSize)
})

/** 序号要跨页连续：第 2 页第一条是 11 而不是 1 */
function rowIndexOf(index: number) {
  return (page.value - 1) * pageSize + index + 1
}

onMounted(() => {
  void load()
})

async function load() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {}
    if (query.status) {
      params.status = query.status
    }
    if (query.severity) {
      params.severity = query.severity
    }
    if (query.ruleType) {
      params.ruleType = query.ruleType
    }
    if (query.keyword.trim()) {
      params.keyword = query.keyword.trim()
    }
    if (query.range && query.range.length === 2) {
      params.startTime = query.range[0]
      params.endTime = query.range[1]
    }
    const [rows, stat] = await Promise.all([
      fetchQaAlerts(params),
      fetchQaOverview().catch(() => null),
    ])
    alerts.value = rows
    overview.value = stat
    page.value = 1
  } finally {
    loading.value = false
  }
}

function applyFilter() {
  void load()
}

function resetFilter() {
  query.status = 0
  query.severity = 0
  query.ruleType = 0
  query.keyword = ''
  query.range = null
  void load()
}

function openHandle(row: QaAlertItem) {
  current.value = row
  handleRemark.value = ''
  handleVisible.value = true
}

async function submitHandle() {
  if (!current.value) {
    return
  }
  saving.value = true
  try {
    await handleQaAlert(current.value.id, handleRemark.value.trim() || '已在实时预警页面标记处理')
    handleVisible.value = false
    ElMessage.success('已标记处理')
    await load()
  } finally {
    saving.value = false
  }
}

/** 后端返回的是 ISO 时间（带毫秒/微秒），这里统一显示到秒 */
function formatTime(value?: string | null) {
  if (!value) {
    return '—'
  }
  const text = String(value).replace('T', ' ')
  return text.length > 19 ? text.slice(0, 19) : text
}
</script>
<style scoped>
.alerts-page { width: 100%; min-width: 0; }

.page-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #64748b; max-width: 720px; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.btn-icon { margin-right: 4px; }

.pending-badge {
  padding: 4px 12px;
  border-radius: 999px;
  background: #fff7ed;
  color: #c2410c;
  font-size: 13px;
  font-weight: 600;
}

.stat-row { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
}

.stat-icon { width: 38px; height: 38px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.stat-icon.blue { background: #eef4ff; color: #2563eb; }
.stat-icon.amber { background: #fff7ed; color: #d97706; }
.stat-icon.red { background: #fef2f2; color: #dc2626; }
.stat-icon.orange { background: #fff7ed; color: #ea580c; }
.stat-value { font-size: 22px; font-weight: 800; color: #0f172a; line-height: 1.2; }
.stat-label { margin-top: 3px; font-size: 12px; color: #94a3b8; }

.filter-card { width: 100%; margin-bottom: 16px; }
.filter-form { display: flex; flex-wrap: wrap; gap: 0 8px; }
.filter-form :deep(.el-form-item) { margin-bottom: 8px; }

/* 时间范围：外面收窄到 300px，里面把输入框留白收一点，完整到秒的时间也放得下 */
.w-300 :deep(.el-range-input) { font-size: 12px; }
.w-300 :deep(.el-range-separator) { padding: 0 2px; font-size: 12px; }

.w-140 { width: 140px; }
.w-240 { width: 240px; }
.w-300 { width: 300px; }

.main-card { width: 100%; }
.alert-snippet { font-size: 13px; color: #0f172a; }
.alert-advice { margin-top: 2px; font-size: 12px; color: #b45309; }
.hit-keyword {
  display: inline-block;
  margin-right: 4px;
  padding: 0 6px;
  border-radius: 4px;
  background: #fee2e2;
  color: #b91c1c;
  font-weight: 600;
}
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.muted { color: #94a3b8; font-size: 13px; }
.pager-row { display: flex; align-items: center; justify-content: space-between; margin-top: 14px; }
.pager-tip { font-size: 13px; color: #64748b; }
.dialog-tip { font-size: 13px; color: #64748b; margin-bottom: 10px; }
</style>
