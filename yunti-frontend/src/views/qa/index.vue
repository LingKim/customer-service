<template>
  <section class="qa-page">
    <header class="page-header">
      <div>
        <h2>质检中心</h2>
        <p>规则配置、任务初检与人工复核</p>
      </div>
      <el-button @click="refresh">刷新</el-button>
    </header>

    <el-alert title="尚未接入会话消息链路。演示扫描仅生成样本；手工任务提供真实对话后可调用模型，结果来源会在列表显示。" type="warning" :closable="false" show-icon />

    <div class="metrics">
      <el-card><strong>{{ overview?.total ?? 0 }}</strong><span>任务总数</span></el-card>
      <el-card><strong>{{ overview?.pending ?? 0 }}</strong><span>待复核</span></el-card>
      <el-card><strong>{{ overview?.passed ?? 0 }}</strong><span>已通过</span></el-card>
      <el-card><strong>{{ overview?.rejected ?? 0 }}</strong><span>已驳回</span></el-card>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="质检任务" name="tasks">
        <div class="toolbar">
          <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width: 140px" @change="loadTasks">
            <el-option label="待复核" :value="1" />
            <el-option label="已通过" :value="2" />
            <el-option label="已驳回" :value="3" />
          </el-select>
          <el-input v-model="keyword" placeholder="搜索任务号、会话或客服" clearable style="width: 260px" @keyup.enter="loadTasks" />
          <el-button @click="loadTasks">查询</el-button>
          <span class="toolbar-spacer" />
          <el-button @click="openManual">新增单个质检</el-button>
          <el-button :disabled="!selected.length" :loading="busy" @click="batchAi">{{ isMock ? '模拟批量初检' : '模型批量初检' }}</el-button>
          <el-button :disabled="!selected.length" :loading="busy" @click="batchReview">批量通过</el-button>
          <el-button type="primary" :loading="busy" @click="scan">生成演示任务</el-button>
        </div>
        <el-table :data="tasks" v-loading="loading" row-key="taskNo" @selection-change="onSelection">
          <el-table-column type="selection" width="48" />
          <el-table-column prop="taskNo" label="任务号" min-width="185" />
          <el-table-column prop="sessionName" label="会话" min-width="150" />
          <el-table-column prop="agentName" label="客服" min-width="110" />
          <el-table-column prop="aiScore" label="初检分" width="95" />
          <el-table-column label="来源" width="125">
            <template #default="{ row }">{{ sourceText(row.aiSource) }}</template>
          </el-table-column>
          <el-table-column prop="riskText" label="风险" width="100" />
          <el-table-column prop="statusText" label="状态" width="100" />
          <el-table-column prop="createTime" label="创建时间" min-width="170" />
          <el-table-column label="操作" width="175" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="showDetail(row.taskNo)">详情</el-button>
              <el-button link type="primary" @click="openReview(row.taskNo)">复核</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && !tasks.length" description="暂无质检任务" />
      </el-tab-pane>

      <el-tab-pane label="规则配置" name="rules">
        <div class="toolbar">
          <span class="toolbar-spacer" />
          <el-button type="primary" @click="openRule()">新增规则</el-button>
        </div>
        <el-table :data="rules" v-loading="loadingRules">
          <el-table-column prop="ruleName" label="规则名称" min-width="160" />
          <el-table-column prop="ruleTypeText" label="类型" width="110" />
          <el-table-column prop="ruleContent" label="检查内容" min-width="260" />
          <el-table-column prop="weight" label="权重" width="80" />
          <el-table-column label="启用" width="80">
            <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="130">
            <template #default="{ row }">
              <el-button link type="primary" @click="openRule(row)">编辑</el-button>
              <el-button link type="danger" @click="removeRule(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="manualVisible" title="新增单个质检" width="520px">
      <el-form label-width="90px">
        <el-form-item label="会话名称"><el-input v-model="manual.sessionName" maxlength="100" /></el-form-item>
        <el-form-item label="接待客服"><el-input v-model="manual.agentName" maxlength="64" /></el-form-item>
        <el-form-item label="初检分"><el-input-number v-model="manual.aiScore" :min="0" :max="100" /></el-form-item>
        <el-form-item label="风险级别">
          <el-select v-model="manual.riskLevel"><el-option label="低风险" :value="1" /><el-option label="中风险" :value="2" /><el-option label="高风险" :value="3" /></el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="manual.comment" type="textarea" :rows="3" maxlength="500" /></el-form-item>
        <el-form-item label="对话文本"><el-input v-model="manual.transcript" type="textarea" :rows="5" maxlength="20000" placeholder="填写真实对话后会调用模型；留空则使用手工评分" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="manualVisible = false">取消</el-button><el-button type="primary" :loading="busy" @click="saveManual">创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="质检详情" width="680px">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="任务号">{{ detail.taskNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ detail.statusText }}</el-descriptions-item>
          <el-descriptions-item label="会话">{{ detail.sessionName }}</el-descriptions-item>
          <el-descriptions-item label="客服">{{ detail.agentName }}</el-descriptions-item>
          <el-descriptions-item label="初检分">{{ detail.aiScore ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="复核分">{{ detail.reviewScore ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="初检来源">{{ sourceText(detail.aiSource) }}</el-descriptions-item>
        </el-descriptions>
        <p class="detail-comment">{{ detail.aiComment }}</p>
        <el-table :data="detail.ruleResults">
          <el-table-column prop="name" label="规则" />
          <el-table-column prop="type" label="类型" width="100" />
          <el-table-column label="结果" width="90"><template #default="{ row }">{{ row.pass ? '通过' : '关注' }}</template></el-table-column>
          <el-table-column prop="reason" label="说明" />
        </el-table>
      </template>
    </el-dialog>

    <el-dialog v-model="reviewVisible" title="人工复核" width="460px">
      <el-form label-width="80px">
        <el-form-item label="结论"><el-select v-model="review.action"><el-option label="通过" :value="1" /><el-option label="驳回" :value="2" /><el-option label="重新复核" :value="3" /></el-select></el-form-item>
        <el-form-item label="复核分"><el-input-number v-model="review.score" :min="0" :max="100" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="review.comment" type="textarea" :rows="3" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="reviewVisible = false">取消</el-button><el-button type="primary" :loading="busy" @click="saveReview">提交</el-button></template>
    </el-dialog>

    <el-dialog v-model="ruleVisible" :title="rule.id ? '编辑规则' : '新增规则'" width="500px">
      <el-form label-width="90px">
        <el-form-item label="名称"><el-input v-model="rule.ruleName" maxlength="64" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="rule.ruleType"><el-option label="敏感词" :value="1" /><el-option label="承诺规范" :value="2" /><el-option label="必答项" :value="3" /><el-option label="情绪识别" :value="4" /></el-select></el-form-item>
        <el-form-item label="检查内容"><el-input v-model="rule.ruleContent" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="权重"><el-input-number v-model="rule.weight" :min="1" :max="100" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="rule.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="ruleVisible = false">取消</el-button><el-button type="primary" :loading="busy" @click="saveRule">保存</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  batchAiQaTasks, batchReviewQaTasks, createManualQaTask, deleteQaRule,
  fetchQaOverview, fetchQaRules, fetchQaTaskDetail, fetchQaTasks,
  reviewQaTask, saveQaRule, scanQaTasks,
  type QaOverview, type QaRuleItem, type QaTaskDetail, type QaTaskItem,
} from '../../api/customer/qa'

const tab = ref('tasks')
const isMock = import.meta.env.VITE_USE_MOCK === 'true'
const overview = ref<QaOverview>()
const tasks = ref<QaTaskItem[]>([])
const rules = ref<QaRuleItem[]>([])
const selected = ref<QaTaskItem[]>([])
const detail = ref<QaTaskDetail>()
const filterStatus = ref<number | undefined>()
const keyword = ref('')
const loading = ref(false)
const loadingRules = ref(false)
const busy = ref(false)
const manualVisible = ref(false)
const detailVisible = ref(false)
const reviewVisible = ref(false)
const ruleVisible = ref(false)
const reviewTaskNo = ref('')
const manual = reactive({ sessionName: '', agentName: '', aiScore: 80, riskLevel: 1, comment: '', transcript: '' })
const review = reactive({ action: 1, score: 80, comment: '' })
const rule = reactive({ id: '', ruleName: '', ruleType: 1, ruleContent: '', weight: 25, enabled: true })

onMounted(refresh)

async function refresh() {
  await Promise.all([loadTasks(), loadRules(), loadOverview()])
}

async function loadOverview() {
  try { overview.value = await fetchQaOverview() } catch { overview.value = undefined }
}

async function loadTasks() {
  loading.value = true
  try { tasks.value = await fetchQaTasks({ status: filterStatus.value || undefined, keyword: keyword.value || undefined }) }
  finally { loading.value = false }
}

async function loadRules() {
  loadingRules.value = true
  try { rules.value = await fetchQaRules() } finally { loadingRules.value = false }
}

function onSelection(rows: QaTaskItem[]) { selected.value = rows }
function openManual() { Object.assign(manual, { sessionName: '', agentName: '', aiScore: 80, riskLevel: 1, comment: '', transcript: '' }); manualVisible.value = true }

function sourceText(source?: string): string {
  if (source === 'llm-qwen') return '千问模型'
  if (source === 'llm-deepseek') return 'DeepSeek 模型'
  if (source === 'fallback-rule') return '规则兜底'
  if (source === 'manual') return '手工评分'
  if (source === 'demo') return '演示数据'
  return '未标记'
}

async function saveManual() {
  if (!manual.sessionName.trim() || !manual.agentName.trim()) return ElMessage.warning('请填写会话名称和接待客服')
  busy.value = true
  try {
    await createManualQaTask({ ...manual })
    manualVisible.value = false
    ElMessage.success('任务已创建')
    await Promise.all([loadTasks(), loadOverview()])
  } finally { busy.value = false }
}

async function showDetail(taskNo: string) {
  detail.value = await fetchQaTaskDetail(taskNo)
  detailVisible.value = true
}

function openReview(taskNo: string) {
  reviewTaskNo.value = taskNo
  Object.assign(review, { action: 1, score: 80, comment: '' })
  reviewVisible.value = true
}

async function saveReview() {
  busy.value = true
  try {
    await reviewQaTask(reviewTaskNo.value, { ...review })
    reviewVisible.value = false
    ElMessage.success('复核已保存')
    await Promise.all([loadTasks(), loadOverview()])
  } finally { busy.value = false }
}

async function batchReview() {
  await ElMessageBox.confirm(`确认通过选中的 ${selected.value.length} 条任务？`, '批量复核')
  busy.value = true
  try {
    await batchReviewQaTasks(selected.value.map((item) => item.taskNo), { action: 1, score: 80 })
    ElMessage.success('批量复核完成')
    await Promise.all([loadTasks(), loadOverview()])
  } finally { busy.value = false }
}

async function batchAi() {
  busy.value = true
  try {
    await batchAiQaTasks(selected.value.map((item) => item.taskNo))
    ElMessage.success(isMock ? '模拟初检完成' : '模型初检完成')
    await Promise.all([loadTasks(), loadOverview()])
  } finally { busy.value = false }
}

async function scan() {
  await ElMessageBox.confirm('将生成演示会话的质检任务，是否继续？', '演示扫描')
  busy.value = true
  try {
    const result = await scanQaTasks()
    ElMessage.success(`已生成 ${result.created} 条演示任务`)
    await Promise.all([loadTasks(), loadOverview()])
  } finally { busy.value = false }
}

function openRule(item?: QaRuleItem) {
  Object.assign(rule, item
    ? { id: item.id, ruleName: item.ruleName, ruleType: item.ruleType, ruleContent: item.ruleContent || '', weight: item.weight, enabled: item.enabled }
    : { id: '', ruleName: '', ruleType: 1, ruleContent: '', weight: 25, enabled: true })
  ruleVisible.value = true
}

async function saveRule() {
  if (!rule.ruleName.trim()) return ElMessage.warning('请填写规则名称')
  busy.value = true
  try {
    await saveQaRule({ ...rule, id: rule.id || undefined })
    ruleVisible.value = false
    ElMessage.success('规则已保存')
    await loadRules()
  } finally { busy.value = false }
}

async function removeRule(item: QaRuleItem) {
  await ElMessageBox.confirm(`确认删除规则“${item.ruleName}”？`, '删除规则')
  busy.value = true
  try {
    await deleteQaRule(item.id)
    ElMessage.success('规则已删除')
    await loadRules()
  } finally { busy.value = false }
}
</script>

<style scoped>
.qa-page { display: flex; flex-direction: column; gap: 20px; }
.page-header, .toolbar { display: flex; align-items: center; gap: 10px; }
.page-header { justify-content: space-between; }
.page-header h2 { margin: 0 0 6px; font-size: 22px; }
.page-header p { margin: 0; color: #64748b; }
.metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.metrics :deep(.el-card__body) { display: flex; flex-direction: column; gap: 6px; }
.metrics strong { font-size: 24px; }
.metrics span { color: #64748b; font-size: 13px; }
.toolbar { margin-bottom: 14px; flex-wrap: wrap; }
.toolbar-spacer { flex: 1; }
.detail-comment { margin: 16px 0; color: #475569; line-height: 1.6; }
@media (max-width: 1000px) { .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
