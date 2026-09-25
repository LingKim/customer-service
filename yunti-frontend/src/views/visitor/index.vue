<template>
  <div class="visitor-page" :class="{ 'is-embed': isEmbed }">
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
        <span>{{ errorHint || '请在地址后带上渠道应用标识，例如：/visitor?appId=你的渠道密钥' }}</span>
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
            <div class="v-bubble" :class="{ 'is-pending': msg.pending, 'is-failed': msg.failed }">
              <div v-if="msg.senderType !== 1" class="v-meta">{{ senderText(msg) }}</div>
              <div class="v-content">{{ msg.content }}</div>
              <div class="v-time">
                <template v-if="msg.pending">发送中…</template>
                <template v-else-if="msg.failed">
                  发送失败，网络恢复后自动补发
                </template>
                <template v-else>{{ msgTime(msg.sendTime) }}</template>
              </div>
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
            :disabled="sessionClosed"
            :placeholder="sessionClosed ? '本次咨询已结束，如需继续请点右下角重新发起' : '请输入你想咨询的问题，Enter 发送'"
            @keydown.enter.exact.prevent="send"
          />
          <div class="vi-actions">
            <span class="vi-tip">{{ connectionTip }}</span>
            <el-button v-if="sessionClosed" @click="restart">重新发起咨询</el-button>
            <el-button v-else type="primary" :loading="sending" :disabled="!connected" @click="send">
              发送
            </el-button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Loading, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listSessionMessages, openVisitorSession, type SessionMessageItem } from '../../api/customer/session'
import { RealtimeClient, type RealtimeMessage, type RealtimeState } from '../../utils/realtime'

const route = useRoute()
const router = useRouter()

/** 被 widget.js 嵌进 iframe 时用紧凑布局（去掉大背景和外边距） */
const isEmbed = computed(() => String(route.query.embed ?? '') === '1')

const sessionNo = ref('')
const messages = ref<SessionMessageItem[]>([])
const draft = ref('')
const sending = ref(false)
const connecting = ref(true)
const errorText = ref('')
/** 出错时的补充说明：会话失效和"没带 appId"要给不一样的指引 */
const errorHint = ref('')
const scrollRef = ref<HTMLElement>()
const connectionState = ref<RealtimeState>('idle')
/** 会话对象里我们会用到的两个字段（状态、负责人） */
interface VisitorSessionState {
  status?: number
  agentId?: number | null
}

/** 会话状态：4-已结束（客服结束会话后，客户这边要立刻反映出来） */
const sessionStatus = ref(1)
/** 这条会话有没有人工客服接手（没接手就别说"客服在线"） */
const hasAgent = ref(false)

let client: RealtimeClient | null = null

const connected = computed(() => connectionState.value === 'open')
const sessionClosed = computed(() => sessionStatus.value === 4)

const stateText = computed(() => {
  switch (connectionState.value) {
    case 'open':
      // 长连接正常不代表客服在线：会话结束了说"已结束"，没人接手说"等待接入"
      if (sessionClosed.value) {
        return '会话已结束'
      }
      return hasAgent.value ? '客服在线' : '等待客服接入'
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
const stateClass = computed(() => {
  if (sessionClosed.value) {
    return 'dot-closed'
  }
  if (connectionState.value === 'open' && !hasAgent.value) {
    return 'dot-waiting'
  }
  return `dot-${connectionState.value}`
})
const connectionTip = computed(() => {
  if (sessionClosed.value) {
    return '本次咨询已结束，历史消息已保存在客服系统里'
  }
  if (connectionState.value !== 'open') {
    return '连接中，消息会自动重发，不会丢'
  }
  return hasAgent.value ? '客服通常会在 1 分钟内回复' : '正在为您分配客服，请稍候'
})

onMounted(openSession)

onUnmounted(() => client?.close())

async function openSession() {
  // appId 是正式参数名；key 是早期版本留下的别名，继续兼容老地址
  const appKey = String(route.query.appId ?? route.query.key ?? '').trim()
  if (!appKey) {
    connecting.value = false
    errorText.value = '缺少渠道密钥，无法发起会话'
    return
  }
  const name = String(route.query.name ?? '').trim()
  const storageKey = `yunti_visitor_${appKey}`
  // fresh=1：这次要模拟一个新客户，先清掉本地身份；参数只用一次，随即从地址里摘掉
  if (String(route.query.fresh ?? '') === '1') {
    localStorage.removeItem(storageKey)
    await router.replace({
      path: '/visitor',
      query: { appId: route.query.appId, key: route.query.key, name: route.query.name },
    })
  }
  // 身份令牌由服务端签发，本地只负责保存和回传；过期或伪造的令牌服务端会当新访客处理
  const visitorToken = localStorage.getItem(storageKey) || undefined

  try {
    const result = await openVisitorSession({
      appKey,
      visitorToken,
      visitorName: name || undefined,
    })
    sessionNo.value = result.sessionNo
    localStorage.setItem(storageKey, result.visitorIdentityToken)
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
      applySession(message.data?.session)
      scrollToBottom()
      // 断线期间可能漏了消息：拿本地最大序号和服务端对一下，缺的补回来
      void syncMissedMessages(message.data?.lastSeq)
      break
    }
    case 'ACK':
      appendMessage(message.data as SessionMessageItem)
      break
    case 'MESSAGE':
      appendMessage(message.data as SessionMessageItem)
      void fillGapIfNeeded(message.data as SessionMessageItem | undefined)
      break
    case 'SESSION': {
      // 人工客服接入 / 转接 / 结束都会推这条事件，客户端的在线状态跟着它变
      const wasClosed = sessionClosed.value
      applySession(message.data)
      if (!wasClosed && sessionClosed.value) {
        ElMessage.info('本次咨询已结束，如需继续可点右下角重新发起')
      }
      break
    }
    case 'ERROR': {
      const code = message.code ?? 0
      // 会话不存在 / 已结束属于"这条连接没救了"：停掉重连，提示刷新重新发起，
      // 否则客户端会不停重连、服务端跟着刷一堆错误日志
      if (code === 40401 || code === 40301) {
        connecting.value = false
        errorText.value = message.message || '会话已结束'
        errorHint.value = '刷新页面即可重新发起咨询；历史消息已经保存在客服系统里'
        client?.close()
        break
      }
      ElMessage.error(message.message || '操作失败')
      break
    }
    default:
      break
  }
}

async function send() {
  const content = draft.value.trim()
  if (!content || !client || sessionClosed.value) {
    return
  }
  const localId = `local-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  // 乐观气泡：先显示"发送中"，拿到 ACK 再换成服务端消息（序号、时间都以服务端为准）
  messages.value.push({
    msgId: localId,
    msgNo: localId,
    senderType: 1,
    msgType: 1,
    content,
    sendTime: new Date().toISOString(),
    pending: true,
  })
  scrollToBottom()
  draft.value = ''
  sending.value = true
  try {
    const ack = await client.send({ type: 'SEND', sessionNo: sessionNo.value, msgType: 1, content })
    const saved = ack.data as SessionMessageItem | undefined
    messages.value = messages.value.filter((item) => item.msgId !== localId)
    if (saved) {
      appendMessage(saved)
    }
  } catch (e) {
    // 消息仍在发件箱里，网络恢复会自动补发（同一个 clientMsgNo，服务端幂等）
    messages.value = messages.value.map((item) =>
      item.msgId === localId ? { ...item, pending: false, failed: true } : item,
    )
    ElMessage.error(e instanceof Error ? e.message : '发送失败，请重试')
  } finally {
    sending.value = false
  }
}

/** 同步会话状态：是否已结束、有没有人工客服接手 */
function applySession(session?: VisitorSessionState | null) {
  if (!session) {
    return
  }
  if (typeof session.status === 'number') {
    sessionStatus.value = session.status
  }
  if (session.agentId !== undefined) {
    hasAgent.value = !!session.agentId
  }
}

/** 重新发起：刷新即由服务端为同一个客户开一条新会话 */
function restart() {
  window.location.reload()
}

/** 本地已拿到的最大消息序号 */
function maxSeq(items: SessionMessageItem[]) {
  return items.reduce((max, item) => Math.max(max, Number(item.seq ?? 0)), 0)
}

/**
 * 断线重连补偿：把本地最大序号和服务端的最大序号对一下，缺的用 afterSeq 增量补回来。
 * 只拉差值，不会把整段历史重拉一遍。
 */
async function syncMissedMessages(lastSeq?: number | null) {
  const local = maxSeq(messages.value)
  const target = Number(lastSeq ?? 0)
  if (!sessionNo.value || target <= local) {
    return
  }
  try {
    const missed = await listSessionMessages(sessionNo.value, { afterSeq: local, limit: 100 })
    if (missed.length) {
      messages.value = dedupe([...messages.value, ...missed])
      scrollToBottom()
    }
  } catch {
    // 拉不到就等下一次（下一条推送或下一次进入会话时再补）
  }
}

/** 推送的序号和本地不连续，说明中间漏了，立刻补一次 */
async function fillGapIfNeeded(message?: SessionMessageItem) {
  if (!message?.seq) {
    return
  }
  const local = maxSeq(messages.value.filter((item) => item.msgId !== message.msgId))
  if (Number(message.seq) > local + 1) {
    await syncMissedMessages(message.seq)
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

/** 消息时间：展示成 09-12 12:25，客户翻历史时能对上时间点 */
function msgTime(value?: string | null) {
  if (!value) {
    return ''
  }
  return value.length >= 16 ? value.slice(5, 16).replace('T', ' ') : value
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

/* 挂件内嵌模式：撑满 iframe，不要外层留白和背景 */
.visitor-page.is-embed {
  min-height: 0;
  padding: 0;
  background: #fff;
}

.visitor-page.is-embed .visitor-card {
  width: 100%;
  height: 100%;
  max-width: none;
  border-radius: 0;
  box-shadow: none;
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

.dot-waiting {
  background: #fbbf24;
}

.dot-closed {
  background: #cbd5e1;
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
  /* 系统消息左对齐，跟客户/客服消息保持同一条阅读线 */
  justify-content: flex-start;
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

.v-time {
  margin-top: 4px;
  font-size: 11px;
  color: #a3aec2;
  text-align: right;
}

.is-self .v-time {
  color: #c7dbff;
}

.is-system .v-time {
  color: #94a3b8;
  text-align: left;
}

/* 发送中/发送失败的气泡给一点视觉提示（消息仍在发件箱，会自动补发） */
.v-bubble.is-pending {
  opacity: 0.72;
}

.v-bubble.is-failed {
  border-color: #fca5a5;
}

.v-bubble.is-failed .v-time {
  color: #f87171;
  text-align: left;
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
