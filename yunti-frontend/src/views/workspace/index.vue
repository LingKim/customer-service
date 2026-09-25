<template>
  <div class="ws-page">
    <!-- 顶部：连接状态与筛选 -->
    <div class="ws-toolbar">
      <div class="ws-title">
        <span class="dot" :class="stateClass" />
        <span class="name">在线客服工作台</span>
        <el-tag size="small" :type="stateTagType" effect="light">{{ stateText }}</el-tag>
        <span v-if="reconnectTip" class="tip">{{ reconnectTip }}</span>
      </div>
      <div class="ws-filters">
        <el-input
          v-model.trim="keyword"
          placeholder="搜索会话号 / 客户名"
          clearable
          style="width: 220px"
          :prefix-icon="Search"
          @keyup.enter="loadSessions"
          @clear="loadSessions"
        />
        <el-radio-group v-model="statusFilter" size="default" @change="loadSessions">
          <el-radio-button :value="0">全部</el-radio-button>
          <el-radio-button :value="3">进行中</el-radio-button>
          <el-radio-button :value="4">已结束</el-radio-button>
        </el-radio-group>
        <el-checkbox v-model="onlyMine" @change="loadSessions">只看我的</el-checkbox>
        <el-button :icon="Refresh" circle :loading="loading" @click="loadSessions" />
      </div>
    </div>
    <div class="ws-body">
      <!-- 左：会话列表 -->
      <div class="ws-list">
        <div v-if="loading && !sessions.length" class="list-empty">
          <el-icon class="is-loading" :size="22"><Loading /></el-icon>
        </div>
        <div v-else-if="!sessions.length" class="list-empty">暂无会话</div>
        <div
          v-for="item in sessions"
          v-else
          :key="item.sessionNo"
          class="list-item"
          :class="{ active: item.sessionNo === activeSessionNo }"
          @click="openSession(item)"
        >
          <div class="li-top">
            <span class="li-name">{{ item.customerName || '访客' }}</span>
            <span class="li-time">{{ shortTime(item.lastTime || item.startTime) }}</span>
          </div>
          <div class="li-mid">{{ preview(item) }}</div>
          <div class="li-bottom">
            <el-tag size="small" :type="statusTagType(item.status)" effect="light">
              {{ statusText(item.status) }}
            </el-tag>
            <span class="li-no">{{ item.sessionNo }}</span>
          </div>
        </div>
      </div>
      <!-- 右：聊天窗口 -->
      <div class="ws-chat">
        <template v-if="activeSession">
          <div class="chat-head">
            <div>
              <div class="ch-name">{{ activeSession.customerName || '访客' }}</div>
              <div class="ch-sub">
                会话 {{ activeSession.sessionNo }} · {{ activeSession.source || '未知渠道' }} ·
                在线 {{ onlineCount }} 人
              </div>
            </div>
            <div class="ch-right">
              <el-button v-if="!isClosed" size="small" @click="loadMore">加载更早</el-button>
              <el-button v-if="!isClosed" size="small" type="danger" plain @click="closeSession">
                结束会话
              </el-button>
            </div>
          </div>
          <div ref="scrollRef" class="chat-body">
            <div v-if="loadingHistory" class="chat-tip">正在加载聊天记录…</div>
            <div
              v-for="msg in messages"
              :key="msg.msgId"
              class="msg-row"
              :class="msgRowClass(msg)"
            >
              <div class="msg-bubble">
                <div class="msg-meta">
                  {{ senderText(msg) }} · {{ fullTime(msg.sendTime) }}
                </div>
                <div class="msg-content">{{ msg.content }}</div>
              </div>
            </div>
            <div v-if="!messages.length" class="chat-tip">还没有消息，输入内容开始接待</div>
          </div>
          <div class="chat-input">
            <el-input
              v-model="draft"
              type="textarea"
              :rows="3"
              resize="none"
              maxlength="2000"
              show-word-limit
              :disabled="isClosed"
              placeholder="输入回复内容，Enter 发送，Shift + Enter 换行"
              @keydown.enter.exact.prevent="send"
            />
            <div class="ci-actions">
              <span class="ci-tip">{{ isClosed ? '会话已结束' : '消息会实时送达客户' }}</span>
              <el-button type="primary" :loading="sending" :disabled="isClosed" @click="send">
                发送
              </el-button>
            </div>
          </div>
        </template>
        <div v-else class="chat-empty">
          <el-icon :size="36"><ChatDotRound /></el-icon>
          <p>从左侧选择一条会话开始接待</p>
          <span>客户从官网挂件或渠道进入后，会话会实时出现在这里</span>
        </div>
      </div>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ChatDotRound, Loading, Refresh, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getSessionDetail,
  listSessionMessages,
  listSessions,
  type SessionItem,
  type SessionMessageItem,
} from '../../api/customer/session'
import { getToken } from '../../utils/auth'
import { RealtimeClient, type RealtimeMessage, type RealtimeState } from '../../utils/realtime'

const sessions = ref<SessionItem[]>([])
const loading = ref(false)
const keyword = ref('')
const statusFilter = ref(0)
const onlyMine = ref(false)

const activeSessionNo = ref('')
const activeSession = ref<SessionItem | null>(null)
const messages = ref<SessionMessageItem[]>([])
const loadingHistory = ref(false)
const draft = ref('')
const sending = ref(false)
const onlineCount = ref(0)
const scrollRef = ref<HTMLElement>()

const connectionState = ref<RealtimeState>('idle')
let client: RealtimeClient | null = null

const isClosed = computed(() => activeSession.value?.status === 4)
const stateText = computed(() => {
  switch (connectionState.value) {
    case 'open':
      return '已连接'
    case 'connecting':
      return '连接中'
    case 'reconnecting':
      return '重连中'
    case 'closed':
      return '已断开'
    default:
      return '未连接'
  }
})
const stateTagType = computed(() => (connectionState.value === 'open' ? 'success' : 'warning'))
const stateClass = computed(() => `dot-${connectionState.value}`)
const reconnectTip = computed(() =>
  connectionState.value === 'reconnecting' ? '网络波动，正在自动重连，消息不会丢' : '',
)

onMounted(async () => {
  await loadSessions()
  connect()
})

onUnmounted(() => {
  client?.close()
})

function connect() {
  const token = getToken()
  if (!token) {
    ElMessage.warning('登录状态已失效，请重新登录')
    return
  }
  client = new RealtimeClient({
    token,
    onMessage: handleMessage,
    onStateChange: (state) => {
      connectionState.value = state
      // 重连成功后重新订阅当前会话，缺失的消息由 JOINED 带回来
      if (state === 'open' && activeSessionNo.value) {
        client?.post({ type: 'JOIN', sessionNo: activeSessionNo.value })
      }
    },
  })
  client.connect()
}

function handleMessage(message: RealtimeMessage) {
  switch (message.type) {
    case 'JOINED': {
      onlineCount.value = message.data?.online ?? 0
      const session = message.data?.session as SessionItem | undefined
      if (session) {
        activeSession.value = { ...(activeSession.value ?? {}), ...session } as SessionItem
      }
      const history = (message.data?.messages ?? []) as SessionMessageItem[]
      messages.value = dedupe(history)
      scrollToBottom()
      break
    }
    case 'ACK': {
      appendMessage(message.data as SessionMessageItem)
      break
    }
    case 'MESSAGE': {
      appendMessage(message.data as SessionMessageItem)
      // 新消息可能来自当前会话，也可能是列表里别的会话 -> 刷新列表的最后一条
      if (message.sessionNo !== activeSessionNo.value) {
        loadSessions()
      }
      break
    }
    case 'SESSION': {
      const session = message.data as SessionItem | undefined
      if (session && session.sessionNo === activeSessionNo.value) {
        activeSession.value = { ...(activeSession.value ?? {}), ...session } as SessionItem
      }
      loadSessions()
      break
    }
    case 'PRESENCE': {
      onlineCount.value = message.data?.online ?? onlineCount.value
      break
    }
    case 'ERROR': {
      ElMessage.error(message.message || '操作失败')
      break
    }
    default:
      break
  }
}

async function loadSessions() {
  loading.value = true
  try {
    sessions.value = await listSessions({
      status: statusFilter.value === 0 ? undefined : statusFilter.value,
      keyword: keyword.value || undefined,
      mine: onlyMine.value,
    })
  } catch {
    // 请求层已提示
  } finally {
    loading.value = false
  }
}

async function openSession(item: SessionItem) {
  activeSessionNo.value = item.sessionNo
  activeSession.value = { ...item }
  messages.value = []
  onlineCount.value = 0
  loadingHistory.value = true
  try {
    const [detail, history] = await Promise.all([
      getSessionDetail(item.sessionNo),
      listSessionMessages(item.sessionNo, { limit: 30 }),
    ])
    activeSession.value = detail
    messages.value = dedupe(history)
    scrollToBottom()
  } catch {
    // 请求层已提示
  } finally {
    loadingHistory.value = false
  }
  // 接入会话：服务端会认领并广播，同时把最新历史回给我们
  client?.post({ type: 'JOIN', sessionNo: item.sessionNo })
}

async function loadMore() {
  if (!activeSessionNo.value || !messages.value.length) {
    return
  }
  const beforeId = messages.value[0].msgId
  const older = await listSessionMessages(activeSessionNo.value, { beforeId, limit: 30 })
  messages.value = dedupe([...older, ...messages.value])
}

async function send() {
  const content = draft.value.trim()
  if (!content || !activeSessionNo.value || !client) {
    return
  }
  sending.value = true
  try {
    await client.send({
      type: 'SEND',
      sessionNo: activeSessionNo.value,
      msgType: 1,
      content,
    })
    draft.value = ''
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发送失败，请重试')
  } finally {
    sending.value = false
  }
}

async function closeSession() {
  if (!activeSessionNo.value) {
    return
  }
  try {
    await ElMessageBox.confirm('结束后客户将无法继续发送消息，确定结束本次会话吗？', '结束会话', {
      type: 'warning',
      confirmButtonText: '确定结束',
      cancelButtonText: '再想想',
    })
  } catch {
    return
  }
  client?.post({ type: 'CLOSE_SESSION', sessionNo: activeSessionNo.value })
}

function appendMessage(message: SessionMessageItem | undefined) {
  if (!message) {
    return
  }
  if (messages.value.some((item) => item.msgId === message.msgId)) {
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
  if (message.senderType === 2) {
    return 'is-agent'
  }
  if (message.senderType === 1) {
    return 'is-customer'
  }
  return 'is-system'
}

function senderText(message: SessionMessageItem) {
  if (message.senderType === 2) {
    return '客服'
  }
  if (message.senderType === 1) {
    return '客户'
  }
  if (message.senderType === 3) {
    return '机器人'
  }
  return '系统'
}

function statusText(status: number) {
  return { 1: '排队中', 2: '机器人接待', 3: '人工接待', 4: '已结束' }[status] ?? '未知'
}

function statusTagType(status: number) {
  if (status === 4) {
    return 'info'
  }
  if (status === 1) {
    return 'warning'
  }
  return 'success'
}

function preview(item: SessionItem) {
  if (!item.lastContent) {
    return '暂无消息'
  }
  const prefix = item.lastSenderType === 2 ? '我：' : ''
  return prefix + item.lastContent
}

function shortTime(value?: string | null) {
  if (!value) {
    return ''
  }
  return value.length >= 16 ? value.slice(5, 16).replace('T', ' ') : value
}

function fullTime(value?: string | null) {
  if (!value) {
    return ''
  }
  return value.length >= 19 ? value.slice(5, 19).replace('T', ' ') : value
}
</script>
<style scoped>
.ws-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 108px);
  min-height: 520px;
}

.ws-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 4px 14px;
  gap: 16px;
  flex-wrap: wrap;
}

.ws-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ws-title .name {
  font-size: 17px;
  font-weight: 700;
  color: #0f172a;
}

.ws-title .tip {
  font-size: 12px;
  color: #d97706;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #cbd5e1;
}

.dot-open {
  background: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.16);
}

.dot-reconnecting,
.dot-connecting {
  background: #f59e0b;
  box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.16);
}

.ws-filters {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.ws-body {
  flex: 1;
  display: flex;
  gap: 14px;
  min-height: 0;
}

.ws-list {
  width: 330px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
  overflow-y: auto;
  padding: 8px;
}

.list-empty {
  padding: 40px 0;
  text-align: center;
  color: #94a3b8;
  font-size: 13px;
}

.list-item {
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s;
}

.list-item:hover {
  background: #f6f9ff;
}

.list-item.active {
  background: #eff6ff;
  box-shadow: inset 0 0 0 1px #bfdbfe;
}

.li-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.li-name {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
}

.li-time {
  font-size: 12px;
  color: #94a3b8;
}

.li-mid {
  margin: 4px 0 6px;
  font-size: 12px;
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.li-bottom {
  display: flex;
  align-items: center;
  gap: 8px;
}

.li-no {
  font-size: 11px;
  color: #a3aec2;
}

.ws-chat {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
}

.chat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid #eef2f7;
}

.ch-name {
  font-size: 15px;
  font-weight: 700;
  color: #0f172a;
}

.ch-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #94a3b8;
}

.ch-right {
  display: flex;
  gap: 8px;
}

.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px;
  background: #fafbfd;
}

.chat-tip {
  text-align: center;
  color: #94a3b8;
  font-size: 12px;
  padding: 12px 0;
}

.msg-row {
  display: flex;
  margin-bottom: 12px;
}

.msg-row.is-customer {
  justify-content: flex-start;
}

.msg-row.is-agent {
  justify-content: flex-end;
}

.msg-row.is-system {
  justify-content: center;
}

.msg-bubble {
  max-width: 62%;
  padding: 9px 12px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid #e8eef6;
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.03);
}

.is-agent .msg-bubble {
  background: #2563eb;
  border-color: #2563eb;
}

.is-system .msg-bubble {
  background: #f1f5f9;
  border-color: #e2e8f0;
  box-shadow: none;
}

.msg-meta {
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 3px;
}

.is-agent .msg-meta {
  color: #c7dbff;
}

.msg-content {
  font-size: 13px;
  color: #1f2937;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.is-agent .msg-content {
  color: #fff;
}

.is-system .msg-content {
  color: #64748b;
}

.chat-input {
  border-top: 1px solid #eef2f7;
  padding: 12px 16px 14px;
}

.ci-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}

.ci-tip {
  font-size: 12px;
  color: #94a3b8;
}

.chat-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #94a3b8;
}

.chat-empty p {
  margin: 6px 0 0;
  font-size: 14px;
  color: #475569;
}

.chat-empty span {
  font-size: 12px;
}
</style>
