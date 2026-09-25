<template>
  <div class="visitor-page">
    <div class="visitor-card">
      <!-- 顶部 -->
      <div class="v-head">
        <div class="v-brand">
          <img class="v-logo" src="/yunti-mark.svg" alt="云梯" />
          <div>
            <div class="v-title">在线客服</div>
            <div class="v-sub">
              <span class="dot" :class="stateClass" />
              {{ stateText }}
            </div>
          </div>
        </div>
        <div class="v-session">{{ sessionNo || '' }}</div>
      </div>
      <!-- 未配置渠道密钥 -->
      <div v-if="errorText" class="v-error">
        <el-icon :size="30"><WarningFilled /></el-icon>
        <p>{{ errorText }}</p>
        <span>请在地址后带上渠道密钥，例如：/visitor?key=你的渠道密钥</span>
      </div>
      <template v-else>
        <!-- 消息区 -->
        <div ref="scrollRef" class="v-body">
          <div v-if="connecting" class="v-tip">
            <el-icon class="is-loading" :size="16"><Loading /></el-icon>
            正在接入客服…
          </div>
          <div v-else-if="!messages.length" class="v-tip">已经接通，直接把问题发给我们吧～</div>
          <div
            v-for="msg in messages"
            :key="msg.msgId"
            class="v-row"
            :class="msgRowClass(msg)"
          >
            <div class="v-bubble">
              <div v-if="msg.senderType !== 1" class="v-meta">{{ senderText(msg) }}</div>
              <div class="v-content">{{ msg.content }}</div>
            </div>
          </div>
        </div>
        <!-- 输入区 -->
        <div class="v-input">
          <el-input
            v-model="draft"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="1000"
            placeholder="请输入你想咨询的问题，Enter 发送"
            @keydown.enter.exact.prevent="send"
          />
          <div class="vi-actions">
            <span class="vi-tip">{{ connectionTip }}</span>
            <el-button type="primary" :loading="sending" @click="send">发送</el-button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { openVisitorSession, type SessionMessageItem } from '../../api/customer/session'
import { RealtimeClient, type RealtimeMessage, type RealtimeState } from '../../utils/realtime'

const route = useRoute()

const sessionNo = ref('')
const messages = ref<SessionMessageItem[]>([])
const draft = ref('')
const sending = ref(false)
const connecting = ref(true)
const errorText = ref('')
const scrollRef = ref<HTMLElement>()
const connectionState = ref<RealtimeState>('idle')

let client: RealtimeClient | null = null

const stateText = computed(() => {
  switch (connectionState.value) {
    case 'open':
      return '客服在线'
    case 'connecting':
      return '正在接入'
    case 'reconnecting':
      return '网络波动，正在重连'
    case 'closed':
      return '已断开'
    default:
      return '未连接'
  }
})
const stateClass = computed(() => `dot-${connectionState.value}`)
const connectionTip = computed(() =>
  connectionState.value === 'open'
    ? '客服通常会在 1 分钟内回复'
    : '连接中，消息会自动重发，不会丢',
)

onMounted(openSession)

onUnmounted(() => client?.close())

async function openSession() {
  const appKey = String(route.query.key ?? '').trim()
  if (!appKey) {
    connecting.value = false
    errorText.value = '缺少渠道密钥，无法发起会话'
    return
  }
  const name = String(route.query.name ?? '').trim()
  const storageKey = `yunti_visitor_${appKey}`
  const visitorKey = localStorage.getItem(storageKey) || undefined

  try {
    const result = await openVisitorSession({
      appKey,
      visitorKey,
      visitorName: name || undefined,
    })
    sessionNo.value = result.sessionNo
    localStorage.setItem(storageKey, result.customerNo)
    connect(result.visitorToken)
  } catch (e) {
    connecting.value = false
    errorText.value = e instanceof Error ? e.message : '接入失败，请稍后重试'
  }
}

function connect(token: string) {
  client = new RealtimeClient({
    token,
    onMessage: handleMessage,
    onStateChange: (state) => {
      connectionState.value = state
    },
  })
  client.connect()
}

function handleMessage(message: RealtimeMessage) {
  switch (message.type) {
    case 'CONNECTED':
      connecting.value = false
      break
    case 'JOINED': {
      connecting.value = false
      messages.value = dedupe((message.data?.messages ?? []) as SessionMessageItem[])
      scrollToBottom()
      break
    }
    case 'ACK':
      appendMessage(message.data as SessionMessageItem)
      break
    case 'MESSAGE':
      appendMessage(message.data as SessionMessageItem)
      break
    case 'SESSION': {
      const status = message.data?.status
      if (status === 4) {
        ElMessage.info('本次咨询已结束，如需继续请刷新页面重新发起')
      }
      break
    }
    case 'ERROR':
      ElMessage.error(message.message || '操作失败')
      break
    default:
      break
  }
}

async function send() {
  const content = draft.value.trim()
  if (!content || !client) {
    return
  }
  sending.value = true
  try {
    await client.send({ type: 'SEND', sessionNo: sessionNo.value, msgType: 1, content })
    draft.value = ''
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发送失败，请重试')
  } finally {
    sending.value = false
  }
}

function appendMessage(message: SessionMessageItem | undefined) {
  if (!message || messages.value.some((item) => item.msgId === message.msgId)) {
    return
  }
  messages.value.push(message)
  scrollToBottom()
}

function dedupe(list: SessionMessageItem[]) {
  const seen = new Set<string>()
  return list.filter((item) => {
    if (seen.has(item.msgId)) {
      return false
    }
    seen.add(item.msgId)
    return true
  })
}

function scrollToBottom() {
  void nextTick(() => {
    const el = scrollRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

function msgRowClass(message: SessionMessageItem) {
  if (message.senderType === 1) {
    return 'is-self'
  }
  if (message.senderType === 4) {
    return 'is-system'
  }
  return 'is-other'
}

function senderText(message: SessionMessageItem) {
  if (message.senderType === 2) {
    return '客服'
  }
  if (message.senderType === 3) {
    return '机器人'
  }
  return '系统'
}
</script>
<style scoped>
.visitor-page {
  height: 100%;
  min-height: 620px;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(900px 500px at 10% -10%, rgba(59, 130, 246, 0.22), transparent 60%),
    #f6f7f9;
  padding: 24px;
}

.visitor-card {
  width: 520px;
  height: 640px;
  display: flex;
  flex-direction: column;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 18px 50px rgba(15, 23, 42, 0.08);
  overflow: hidden;
}

.v-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  background: linear-gradient(135deg, #1d4ed8, #3b82f6);
  color: #fff;
}

.v-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.v-logo {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: #fff;
}

.v-title {
  font-size: 15px;
  font-weight: 700;
}

.v-sub {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #dbeafe;
  margin-top: 3px;
}

.v-session {
  font-size: 11px;
  color: #dbeafe;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #facc15;
}

.dot-open {
  background: #4ade80;
}

.v-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px;
  background: #fafbfd;
}

.v-tip {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #94a3b8;
  font-size: 12px;
  padding: 14px 0;
}

.v-row {
  display: flex;
  margin-bottom: 12px;
}

.v-row.is-other {
  justify-content: flex-start;
}

.v-row.is-self {
  justify-content: flex-end;
}

.v-row.is-system {
  justify-content: center;
}

.v-bubble {
  max-width: 74%;
  padding: 9px 12px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid #e8eef6;
}

.is-self .v-bubble {
  background: #2563eb;
  border-color: #2563eb;
}

.is-system .v-bubble {
  background: #f1f5f9;
  border-color: #e2e8f0;
}

.v-meta {
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 3px;
}

.v-content {
  font-size: 13px;
  line-height: 1.65;
  color: #1f2937;
  white-space: pre-wrap;
  word-break: break-word;
}

.is-self .v-content {
  color: #fff;
}

.is-system .v-content {
  color: #64748b;
}

.v-input {
  border-top: 1px solid #eef2f7;
  padding: 12px 16px 14px;
}

.vi-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}

.vi-tip {
  font-size: 12px;
  color: #94a3b8;
}

.v-error {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #f59e0b;
  padding: 0 40px;
  text-align: center;
}

.v-error p {
  margin: 6px 0 0;
  font-size: 14px;
  color: #475569;
}

.v-error span {
  font-size: 12px;
  color: #94a3b8;
}
</style>
