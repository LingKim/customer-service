<template>
  <div class="bot-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">智能机器人</h2>
        <p class="page-sub">管理机器人设置、客服意图、接待策略与模型选择</p>
      </div>
      <el-switch
        v-model="setting.isEnabled"
        active-text="机器人服务"
        :loading="saving"
        @change="saveSettings"
      />
    </div>
    <el-tabs v-model="tab">
      <el-tab-pane label="机器人设置" name="setting">
        <el-card shadow="never" class="tab-card setting-card">
          <el-form label-position="top" class="setting-form">
            <el-form-item label="机器人名称">
              <el-input v-model="setting.botName" maxlength="64" />
            </el-form-item>
            <el-form-item label="欢迎语">
              <el-input v-model="setting.welcomeMessage" type="textarea" :rows="2" maxlength="255" />
            </el-form-item>
            <el-form-item label="未识别兜底话术">
              <el-input v-model="setting.fallbackMessage" type="textarea" :rows="2" maxlength="255" />
            </el-form-item>
            <el-form-item label="转人工提示">
              <el-input v-model="setting.transferPrompt" maxlength="255" />
              <span class="policy-tip">
                机器人答不上来时，附在回答里的提示语（教客户怎么找人工），例如"如需人工客服，请回复转人工"。
              </span>
            </el-form-item>
            <el-form-item label="转接中话术">
              <el-input v-model="setting.transferMessage" maxlength="255" />
              <span class="policy-tip">
                客户真的要求转人工后回的确认语，例如"好的，正在为您转接人工客服，请稍候。"——
                和上面那条提示语是两回事，别混用。
              </span>
            </el-form-item>
            <el-button type="primary" :loading="saving" @click="saveSettings">保存设置</el-button>
          </el-form>
          <el-divider content-position="left">接待策略</el-divider>
          <el-form label-position="top" class="setting-form">
            <el-form-item label="机器人首轮接待">
              <div class="policy-line">
                <el-switch v-model="setting.receptionEnabled" />
                <span class="policy-tip">
                  开启后客户进线先由机器人接待，命中转人工条件才进入人工队列；
                  关闭后客户进线直接进人工队列（机器人只作为坐席的「知识助手」）。
                </span>
              </div>
            </el-form-item>
            <el-form-item label="客户情绪激动时自动转人工">
              <div class="policy-line">
                <el-switch v-model="setting.transferOnAnger" />
                <span class="policy-tip">
                  识别到客户愤怒（辱骂、要求投诉等）立即转人工，避免继续对着机器人越聊越火。
                </span>
              </div>
            </el-form-item>
            <el-form-item label="连续未解决轮次">
              <el-input-number v-model="setting.transferAfterUnresolved" :min="0" :max="10" />
              <span class="policy-tip policy-tip-inline">
                机器连续这么多轮答不上来就转人工，0 表示从不因为这个原因转。
              </span>
            </el-form-item>
            <el-form-item label="转人工关键词">
              <el-input
                v-model="setting.transferKeywords"
                maxlength="255"
                placeholder="转人工,人工客服,找人工"
              />
              <span class="policy-tip">客户话里命中任意一个就立刻转人工，用逗号分隔。</span>
            </el-form-item>
            <el-button type="primary" :loading="saving" @click="saveSettings">保存接待策略</el-button>
          </el-form>
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="意图管理" name="intents">
        <el-card shadow="never" class="tab-card">
          <template #header>
            <div class="card-head">
              <span>配置意图与训练语料，机器人将按语义自动匹配</span>
              <el-button type="primary" @click="openIntent()">新建意图</el-button>
            </div>
          </template>
          <el-table :data="intents" v-loading="loadingIntents">
            <el-table-column type="index" label="序号" width="70" />
            <el-table-column prop="name" label="意图名称" min-width="160" />
            <el-table-column prop="intentCode" label="意图编码" width="190" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="light">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="置信度" width="100">
              <template #default="{ row }">{{ row.confidence != null ? `${row.confidence}%` : '—' }}</template>
            </el-table-column>
            <el-table-column label="命中次数" prop="hitCount" width="100" />
            <el-table-column label="转人工" width="110">
              <template #default="{ row }">
                <el-tag v-if="row.escalate" type="danger" effect="light">直接转人工</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="samples" label="训练语料" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openIntent(row)">编辑</el-button>
                <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleIntent(row)">
                  {{ row.status === 1 ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="模型选择" name="model">
        <el-card shadow="never" class="tab-card">
          <template #header><span>选择机器人使用的对话模型与生成参数</span></template>
          <el-radio-group v-model="selectedModelKey" class="model-grid">
            <el-radio v-for="m in models" :key="m.modelKey" :value="m.modelKey" class="model-card">
              <div class="model-name">{{ m.modelName }}</div>
              <div class="model-meta">{{ m.provider }} · {{ typeText(m.modelType) }}</div>
            </el-radio>
          </el-radio-group>
          <div class="temp-line">
            <span class="temp-label">Temperature：{{ temperature }}</span>
            <el-slider v-model="temperature" :min="0" :max="1" :step="0.05" class="temp-slider" />
          </div>
          <el-button type="primary" :loading="savingModel" @click="saveModel">保存模型配置</el-button>
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="接待记录" name="dialogue">
        <div class="stat-row">
          <div class="stat-card">
            <div class="stat-value">{{ stats.totalSessions }}</div>
            <div class="stat-label">机器人接待会话</div>
          </div>
          <div class="stat-card">
            <div class="stat-value">{{ stats.totalTurns }}</div>
            <div class="stat-label">累计接待轮次</div>
          </div>
          <div class="stat-card">
            <div class="stat-value">{{ stats.transferredSessions }}</div>
            <div class="stat-label">转人工会话</div>
          </div>
          <div class="stat-card">
            <div class="stat-value">{{ transferRate }}</div>
            <div class="stat-label">转人工率</div>
          </div>
        </div>
        <div v-if="stats.byIntent.length" class="dist-card">
          <div class="dist-title">意图分布</div>
          <div class="dist-row">
            <div v-for="item in stats.byIntent" :key="item.intent" class="dist-item">
              <span class="dist-name">{{ item.intent }}</span>
              <span class="dist-count">{{ item.sessions }} 次</span>
              <span v-if="item.transferred" class="dist-transfer">转人工 {{ item.transferred }}</span>
            </div>
          </div>
        </div>
        <el-card shadow="never" class="tab-card">
          <template #header>
            <div class="card-head">
              <span>机器人接待明细：识别成什么意图、客户什么情绪、为什么转人工</span>
              <el-button :loading="loadingDialogues" @click="loadDialogues">刷新</el-button>
            </div>
          </template>
          <el-table :data="dialogues" v-loading="loadingDialogues">
            <el-table-column type="index" label="序号" width="70" />
            <el-table-column prop="sessionNo" label="会话号" width="200" />
            <el-table-column label="意图" min-width="130">
              <template #default="{ row }">
                <el-tag effect="plain">{{ row.lastIntent || '其他' }}</el-tag>
                <span v-if="row.lastConfidence != null" class="conf">{{ row.lastConfidence }}%</span>
              </template>
            </el-table-column>
            <el-table-column label="情绪" width="130">
              <template #default="{ row }">
                <el-tag :type="emotionType(row.lastEmotion)" effect="light">
                  {{ row.lastEmotion || '中性' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="轮次" width="90">
              <template #default="{ row }">{{ row.turnCount }} 轮</template>
            </el-table-column>
            <el-table-column label="识别到的信息" min-width="180">
              <template #default="{ row }">
                <template v-if="slotText(row.slots)">
                  <el-tag v-for="item in slotText(row.slots)" :key="item" size="small" effect="plain" class="slot-tag">
                    {{ item }}
                  </el-tag>
                </template>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="lastMessage" label="客户最后一句话" min-width="200" show-overflow-tooltip />
            <el-table-column label="转人工" width="110">
              <template #default="{ row }">
                <el-tag v-if="row.transferred" type="warning" effect="light">已转人工</el-tag>
                <span v-else class="muted">机器人已解决</span>
              </template>
            </el-table-column>
            <el-table-column prop="transferReason" label="转人工原因" min-width="220" show-overflow-tooltip />
            <el-table-column prop="updateTime" label="更新时间" width="170" />
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="intentVisible" :title="editingIntent ? '编辑意图' : '新建意图'" width="520px">
      <el-form label-position="top">
        <el-form-item label="意图名称">
          <el-input v-model="intentForm.name" maxlength="64" placeholder="例如：查询订单物流" />
        </el-form-item>
        <el-form-item label="训练语料示例（每行一条）">
          <el-input v-model="intentForm.samples" type="textarea" :rows="5" placeholder="我的快递到哪了\n帮我查一下订单" />
        </el-form-item>
        <el-form-item label="命中即转人工">
          <el-switch v-model="intentForm.escalate" />
          <span class="policy-tip policy-tip-inline">
            例如「人工客服」「投诉建议」这类意图，识别到就直接转人工，不让机器人继续答。
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="intentVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingIntent" @click="saveIntent">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createBotIntent,
  getBotIntentStats,
  getBotSetting,
  listBotDialogues,
  listBotIntents,
  listBotModels,
  saveBotSetting,
  selectBotModel,
  updateBotIntent,
  type BotDialogue,
  type BotIntent,
  type BotIntentStats,
  type BotModel,
  type BotSetting,
} from '../../api/ai/bot'

const tab = ref('setting')
const intents = ref<BotIntent[]>([])
const models = ref<BotModel[]>([])
const dialogues = ref<BotDialogue[]>([])
const stats = ref<BotIntentStats>({
  totalTurns: 0,
  totalSessions: 0,
  transferredSessions: 0,
  byIntent: [],
  byEmotion: [],
})
const loadingIntents = ref(false)
const loadingDialogues = ref(false)
const saving = ref(false)
const savingIntent = ref(false)
const savingModel = ref(false)
const intentVisible = ref(false)
const editingIntent = ref<BotIntent | null>(null)
const selectedModelKey = ref('')
const temperature = ref(0.3)
const intentForm = reactive({ name: '', samples: '', escalate: false })
const setting = reactive<BotSetting>({
  botName: '小云',
  welcomeMessage: '',
  fallbackMessage: '',
  transferPrompt: '',
  transferMessage: '',
  isEnabled: true,
  receptionEnabled: true,
  transferOnAnger: true,
  transferAfterUnresolved: 2,
  transferKeywords: '转人工,人工客服,找人工',
  modelKey: '',
  temperature: 0.3,
})

onMounted(loadAll)

/** 转人工率：转人工会话数 / 机器人接待会话数 */
const transferRate = computed(() => {
  const total = stats.value.totalSessions || 0
  if (!total) {
    return '—'
  }
  return `${Math.round((stats.value.transferredSessions / total) * 100)}%`
})

async function loadAll() {
  try {
    const settingData = await getBotSetting()
    Object.assign(setting, settingData)
    temperature.value = Number(settingData.temperature || 0.3)
    selectedModelKey.value = settingData.modelKey || ''
  } catch { /* ignore */ }
  await Promise.all([loadIntents(), loadModels(), loadDialogues()])
}

async function loadIntents() {
  loadingIntents.value = true
  try {
    intents.value = await listBotIntents()
  } finally {
    loadingIntents.value = false
  }
}

async function loadModels() {
  models.value = await listBotModels()
}

async function loadDialogues() {
  loadingDialogues.value = true
  try {
    const [records, overview] = await Promise.all([listBotDialogues(50), getBotIntentStats()])
    dialogues.value = records
    stats.value = overview
  } catch {
    // 记录拉不到不影响设置页：留空即可（例如 ai_db 还没跑增量脚本）
  } finally {
    loadingDialogues.value = false
  }
}

function openIntent(row?: BotIntent) {
  editingIntent.value = row || null
  intentForm.name = row?.name || ''
  intentForm.samples = row?.samples || ''
  intentForm.escalate = !!row?.escalate
  intentVisible.value = true
}

async function saveIntent() {
  if (!intentForm.name.trim()) {
    ElMessage.warning('请填写意图名称')
    return
  }
  savingIntent.value = true
  try {
    if (editingIntent.value) {
      await updateBotIntent(editingIntent.value.id, {
        name: intentForm.name,
        samples: intentForm.samples,
        escalate: intentForm.escalate,
      })
    } else {
      await createBotIntent({
        name: intentForm.name,
        samples: intentForm.samples,
        escalate: intentForm.escalate,
      })
    }
    intentVisible.value = false
    ElMessage.success('保存成功')
    await loadIntents()
  } finally {
    savingIntent.value = false
  }
}

async function toggleIntent(row: BotIntent) {
  await updateBotIntent(row.id, { status: row.status === 1 ? 2 : 1 })
  row.status = row.status === 1 ? 2 : 1
  ElMessage.success(row.status === 1 ? '意图已启用' : '意图已停用')
}

async function saveSettings() {
  saving.value = true
  try {
    const data = await saveBotSetting({ ...setting })
    Object.assign(setting, data)
    ElMessage.success('设置已保存')
  } finally {
    saving.value = false
  }
}

async function saveModel() {
  if (!selectedModelKey.value) {
    ElMessage.warning('请选择模型')
    return
  }
  savingModel.value = true
  try {
    await selectBotModel({ modelKey: selectedModelKey.value, temperature: temperature.value })
    setting.modelKey = selectedModelKey.value
    setting.temperature = temperature.value
    ElMessage.success('模型配置已保存')
  } finally {
    savingModel.value = false
  }
}

function typeText(type: number) {
  if (type === 1) return '对话'
  if (type === 2) return '视觉'
  return '轻量'
}

/** 情绪标签配色：越负面越扎眼，坐席扫一眼就知道哪条会话要优先看 */
function emotionType(emotion?: string | null) {
  if (emotion === '愤怒') return 'danger'
  if (emotion === '不满') return 'warning'
  if (emotion === '焦虑') return 'info'
  return 'success'
}

/** 多轮对话里攒下来的槽位（订单号 / 手机号 / 邮箱）→ "订单号：2026091700012345" */
const SLOT_LABELS: Record<string, string> = {
  order_no: '订单号',
  phone: '手机号',
  email: '邮箱',
}

function slotText(slots?: Record<string, string> | null) {
  if (!slots) {
    return []
  }
  return Object.entries(slots)
    .filter(([, value]) => !!value)
    .map(([key, value]) => `${SLOT_LABELS[key] || key}：${value}`)
}
</script>
<style scoped>
.bot-page { width: 100%; }
.page-title-row { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 14px; }
.page-title { margin: 0; font-size: 20px; color: #0f172a; }
.page-sub { margin: 6px 0 0; font-size: 13px; color: #94a3b8; }
.tab-card { width: 100%; }
.setting-card { max-width: 760px; }
.setting-form { width: 100%; }
.card-head { display: flex; align-items: center; justify-content: space-between; font-weight: 600; }
.policy-line { display: flex; align-items: center; gap: 10px; }
.policy-tip { color: #94a3b8; font-size: 12px; line-height: 1.6; }
.policy-tip-inline { margin-left: 10px; }
.muted { color: #cbd5e1; }
.slot-tag { margin: 2px 4px 2px 0; }
.conf { margin-left: 6px; color: #94a3b8; font-size: 12px; }
.stat-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 14px; }
.stat-card {
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 12px;
  padding: 16px 18px;
}
.stat-value { font-size: 24px; font-weight: 700; color: #0f172a; }
.stat-label { margin-top: 6px; font-size: 12px; color: #94a3b8; }
.dist-card {
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 12px;
  padding: 14px 18px;
  margin-bottom: 14px;
}
.dist-title { font-size: 13px; font-weight: 600; color: #334155; margin-bottom: 10px; }
.dist-row { display: flex; flex-wrap: wrap; gap: 10px; }
.dist-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 8px;
  background: #f8fafc;
  font-size: 12px;
  color: #475569;
}
.dist-name { color: #1e293b; font-weight: 600; }
.dist-count { color: #94a3b8; }
.dist-transfer { color: #b45309; }
.model-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-bottom: 18px; }
.model-card { border: 1px solid #e6e8ef !important; border-radius: 12px; padding: 14px; height: auto; }
.model-name { font-weight: 700; color: #1e293b; }
.model-meta { color: #94a3b8; font-size: 12px; margin-top: 4px; }
.temp-line { display: flex; align-items: center; gap: 18px; margin: 8px 0 18px; }
.temp-label { width: 150px; color: #475569; font-size: 13px; }
.temp-slider { flex: 1; }
</style>
