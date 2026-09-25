<template>
  <div class="setup-page">
    <div class="page-head">
      <el-button text @click="router.push('/channels')">← 返回渠道列表</el-button>
      <h1>{{ stage === 'create' ? '新增渠道' : '渠道密钥' }}</h1>
    </div>
    <el-card v-if="stage === 'create'" shadow="never">
      <el-form label-position="top" class="form">
        <el-form-item label="渠道类型">
          <el-radio-group v-model="channelType">
            <el-radio-button :value="1">官网 / 网站</el-radio-button>
            <el-radio-button :value="2">微信公众号</el-radio-button>
            <el-radio-button :value="3">微信小程序</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="渠道名称">
          <el-input v-model.trim="name" maxlength="64" placeholder="例如：官网在线客服" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model.trim="description" type="textarea" maxlength="255" :rows="3" />
        </el-form-item>
        <el-button type="primary" :loading="saving" :disabled="!name" @click="create">创建并生成密钥</el-button>
      </el-form>
    </el-card>
    <el-card v-else v-loading="loading" shadow="never">
      <template v-if="current">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="渠道名称">{{ current.name }}</el-descriptions-item>
          <el-descriptions-item label="渠道 ID">{{ current.channelId }}</el-descriptions-item>
          <el-descriptions-item label="渠道密钥">
            <span class="secret">{{ showSecret ? secret : current.maskedKey || '********' }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <p class="hint">密钥用于 SDK 或服务端验签。重新生成后旧密钥立即失效。</p>
        <div class="actions">
          <el-button :loading="revealing" @click="reveal">{{ showSecret ? '隐藏密钥' : '显示完整密钥' }}</el-button>
          <el-button :loading="revealing" @click="copy">复制完整密钥</el-button>
          <el-button type="warning" :loading="rotating" @click="rotate">重新生成</el-button>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createChannel, listChannels, revealChannelKey, rotateChannelKey, type ChannelResult,
} from '../../api/customer/channel'

const route = useRoute()
const router = useRouter()
const stage = computed(() => String(route.params.stage || 'create'))
const channelId = computed(() => String(route.params.channelId || ''))
const channelType = ref(Number(route.query.type || 1))
const name = ref('')
const description = ref('')
const current = ref<ChannelResult | null>(null)
const secret = ref('')
const showSecret = ref(false)
const loading = ref(false)
const saving = ref(false)
const revealing = ref(false)
const rotating = ref(false)

onMounted(load)

async function load() {
  if (stage.value === 'create') return
  loading.value = true
  try {
    current.value = (await listChannels()).find((channel) => channel.id === channelId.value) || null
    if (!current.value) {
      ElMessage.warning('渠道不存在')
      await router.replace('/channels')
    }
  } finally { loading.value = false }
}

async function create() {
  if (!name.value.trim()) return
  saving.value = true
  try {
    const result = await createChannel({ channelType: channelType.value, name: name.value.trim(), desc: description.value.trim() })
    localStorage.setItem('yunti_onboard_done', 'true')
    ElMessage.success('渠道已创建')
    await router.push(`/channels/setup/key/${result.id}`)
    current.value = result
    secret.value = result.appKey || ''
    showSecret.value = !!secret.value
  } finally { saving.value = false }
}

async function getSecret() {
  if (!current.value) return ''
  if (!secret.value) secret.value = await revealChannelKey(current.value.id)
  return secret.value
}

async function reveal() {
  if (showSecret.value) { showSecret.value = false; return }
  revealing.value = true
  try { showSecret.value = !!(await getSecret()) } finally { revealing.value = false }
}

async function copy() {
  revealing.value = true
  try {
    const value = await getSecret()
    if (!value) return
    await navigator.clipboard.writeText(value)
    ElMessage.success('密钥已复制')
  } finally { revealing.value = false }
}

async function rotate() {
  if (!current.value) return
  try { await ElMessageBox.confirm('重新生成后旧密钥立即失效，确定继续？', '重新生成密钥', { type: 'warning' }) } catch { return }
  rotating.value = true
  try {
    current.value = await rotateChannelKey(current.value.id)
    secret.value = current.value.appKey || ''
    showSecret.value = !!secret.value
    ElMessage.success('密钥已重新生成')
  } finally { rotating.value = false }
}
</script>

<style scoped>
.setup-page { width: 100%; }
.page-head { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; }
.page-head h1 { margin: 0; font-size: 22px; color: #172033; }
.form { width: 100%; max-width: 680px; }
.secret { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; word-break: break-all; }
.hint { margin: 20px 0 12px; color: #768197; font-size: 13px; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
</style>
