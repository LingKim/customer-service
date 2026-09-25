<template>
  <div class="bot-page">
    <div class="page-title-row">
      <div>
        <h2 class="page-title">智能机器人</h2>
        <p class="page-sub">管理机器人设置、客服意图与模型选择</p>
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
            </el-form-item>
            <el-button type="primary" :loading="saving" @click="saveSettings">保存设置</el-button>
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
    </el-tabs>
    <el-dialog v-model="intentVisible" :title="editingIntent ? '编辑意图' : '新建意图'" width="520px">
      <el-form label-position="top">
        <el-form-item label="意图名称">
          <el-input v-model="intentForm.name" maxlength="64" placeholder="例如：查询订单物流" />
        </el-form-item>
        <el-form-item label="训练语料示例（每行一条）">
          <el-input v-model="intentForm.samples" type="textarea" :rows="5" placeholder="我的快递到哪了\n帮我查一下订单" />
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  createBotIntent,
  getBotSetting,
  listBotIntents,
  listBotModels,
  saveBotSetting,
  selectBotModel,
  updateBotIntent,
  type BotIntent,
  type BotModel,
  type BotSetting,
} from '../../api/ai/bot'

const tab = ref('setting')
const intents = ref<BotIntent[]>([])
const models = ref<BotModel[]>([])
const loadingIntents = ref(false)
const saving = ref(false)
const savingIntent = ref(false)
const savingModel = ref(false)
const intentVisible = ref(false)
const editingIntent = ref<BotIntent | null>(null)
const selectedModelKey = ref('')
const temperature = ref(0.3)
const intentForm = reactive({ name: '', samples: '' })
const setting = reactive<BotSetting>({
  botName: '小云',
  welcomeMessage: '',
  fallbackMessage: '',
  transferPrompt: '',
  isEnabled: true,
  modelKey: '',
  temperature: 0.3,
})

onMounted(loadAll)

async function loadAll() {
  try {
    const settingData = await getBotSetting()
    Object.assign(setting, settingData)
    temperature.value = Number(settingData.temperature || 0.3)
    selectedModelKey.value = settingData.modelKey || ''
  } catch { /* ignore */ }
  await Promise.all([loadIntents(), loadModels()])
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

function openIntent(row?: BotIntent) {
  editingIntent.value = row || null
  intentForm.name = row?.name || ''
  intentForm.samples = row?.samples || ''
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
      await updateBotIntent(editingIntent.value.id, { name: intentForm.name, samples: intentForm.samples })
    } else {
      await createBotIntent({ name: intentForm.name, samples: intentForm.samples })
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
.model-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-bottom: 18px; }
.model-card { border: 1px solid #e6e8ef !important; border-radius: 12px; padding: 14px; height: auto; }
.model-name { font-weight: 700; color: #1e293b; }
.model-meta { color: #94a3b8; font-size: 12px; margin-top: 4px; }
.temp-line { display: flex; align-items: center; gap: 18px; margin: 8px 0 18px; }
.temp-label { width: 150px; color: #475569; font-size: 13px; }
.temp-slider { flex: 1; }
</style>
