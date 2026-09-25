---
title: "15 打造实时通信底座：WebSocket 长连接网关与会话消息链路（二）"
source: "https://articles.zsxq.com/id_gbrh3qt8abdk.html"
author:
  - "[[苏三]]"
published:
created: 2026-09-13
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

## 六、前端：长连接客户端与两个工作台

### 6.1 WebSocket 客户端封装

把心跳、重连、发送确认这些"脏活"收在一个类里，页面只关心业务消息。

### 文件：yunti-frontend/src/utils/realtime.ts

``` code-block-container
/**
 * 实时通信客户端：长连接、心跳、断线重连、发送确认。
 *
 * 和后端的约定见 yunti-realtime-service：
 * - 连接：ws://host:9096/ws/realtime?token=xxx（坐席用登录令牌，访客用访客令牌）
 * - 心跳：客户端 30 秒发一次 PING，服务端回 PONG，超时判定掉线
 * - 可靠：每条消息带 clientMsgNo，服务端落库后回 ACK；没等到 ACK 的消息重连后自动重发
 */

export type RealtimeState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface RealtimeMessage {
  type: string
  sessionNo?: string
  clientMsgNo?: string
  msgType?: number
  content?: string
  beforeId?: number
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  data?: any
  serverTime?: number
  code?: number
  message?: string
}

export interface RealtimeClientOptions {
  /** 访问令牌：坐席用登录令牌，访客用访客令牌 */
  token: string
  /** 收到服务端消息 */
  onMessage: (message: RealtimeMessage) => void
  /** 连接状态变化 */
  onStateChange?: (state: RealtimeState) => void
  /** 心跳间隔（毫秒），默认 30 秒 */
  heartbeatMs?: number
  /** 单条消息等待 ACK 的超时（毫秒），默认 8 秒 */
  ackTimeoutMs?: number
}

interface PendingMessage {
  payload: RealtimeMessage
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  resolve: (value: any) => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  reject: (reason?: any) => void
  timer: number
}

const MAX_RECONNECT_DELAY = 15000
const BASE_RECONNECT_DELAY = 1000

export class RealtimeClient {
  private readonly options: Required<Pick<RealtimeClientOptions, 'heartbeatMs' | 'ackTimeoutMs'>> &
    RealtimeClientOptions
  private socket: WebSocket | null = null
  private state: RealtimeState = 'idle'
  private heartbeatTimer: number | undefined
  private pongDeadline: number | undefined
  private reconnectTimer: number | undefined
  private reconnectAttempt = 0
  private manualClosed = false
  private seq = 0
  private readonly pending = new Map<string, PendingMessage>()

  constructor(options: RealtimeClientOptions) {
    this.options = {
      heartbeatMs: 30000,
      ackTimeoutMs: 8000,
      ...options,
    }
  }

  get currentState(): RealtimeState {
    return this.state
  }

  connect(): void {
    this.manualClosed = false
    this.open()
  }

  /** 主动断开（离开页面时调用），不再自动重连 */
  close(): void {
    this.manualClosed = true
    this.clearTimers()
    this.socket?.close(1000, 'client close')
    this.socket = null
    this.pending.forEach((item) => {
      window.clearTimeout(item.timer)
      item.reject(new Error('连接已关闭'))
    })
    this.pending.clear()
    this.setState('closed')
  }

  /**
   * 发送消息并等待服务端 ACK。
   */
  send(payload: Omit<RealtimeMessage, 'clientMsgNo'>): Promise<RealtimeMessage> {
    const clientMsgNo = this.nextClientMsgNo()
    const message: RealtimeMessage = { ...payload, clientMsgNo }
    return new Promise<RealtimeMessage>((resolve, reject) => {
      const timer = window.setTimeout(() => {
        this.pending.delete(clientMsgNo)
        reject(new Error('发送超时，请检查网络后重试'))
      }, this.options.ackTimeoutMs)
      this.pending.set(clientMsgNo, { payload: message, resolve, reject, timer })
      this.flush(clientMsgNo)
    })
  }

  /** 不需要 ACK 的控制类消息（心跳、拉历史等） */
  post(payload: RealtimeMessage): void {
    this.rawSend(payload)
  }

  private open(): void {
    this.setState(this.reconnectAttempt === 0 ? 'connecting' : 'reconnecting')
    const url = `${resolveWsBase()}/ws/realtime?token=${encodeURIComponent(this.options.token)}`
    let socket: WebSocket
    try {
      socket = new WebSocket(url)
    } catch {
      this.scheduleReconnect()
      return
    }
    this.socket = socket

    socket.onopen = () => {
      this.reconnectAttempt = 0
      this.setState('open')
      this.startHeartbeat()
      // 断线期间没发出去的消息，连上后立刻补发
      this.pending.forEach((item, clientMsgNo) => this.flush(clientMsgNo, item))
    }

    socket.onmessage = (event) => {
      let message: RealtimeMessage
      try {
        message = JSON.parse(event.data as string) as RealtimeMessage
      } catch {
        return
      }
      if (message.type === 'PONG') {
        this.pongDeadline = undefined
        return
      }
      if (message.type === 'ACK' && message.clientMsgNo) {
        const item = this.pending.get(message.clientMsgNo)
        if (item) {
          window.clearTimeout(item.timer)
          this.pending.delete(message.clientMsgNo)
          item.resolve(message)
        }
      }
      this.options.onMessage(message)
    }

    socket.onclose = () => {
      this.stopHeartbeat()
      if (this.manualClosed) {
        this.setState('closed')
        return
      }
      this.scheduleReconnect()
    }

    socket.onerror = () => {
      // 具体原因由 onclose 统一处理，这里只保证状态不悬空
      if (this.state === 'open') {
        this.setState('reconnecting')
      }
    }
  }

  private scheduleReconnect(): void {
    if (this.manualClosed || this.reconnectTimer) {
      return
    }
    this.reconnectAttempt += 1
    const delay = Math.min(BASE_RECONNECT_DELAY * 2 ** (this.reconnectAttempt - 1), MAX_RECONNECT_DELAY)
    this.setState('reconnecting')
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = undefined
      this.open()
    }, delay)
  }

  private startHeartbeat(): void {
    this.stopHeartbeat()
    this.heartbeatTimer = window.setInterval(() => {
      if (this.socket?.readyState !== WebSocket.OPEN) {
        return
      }
      // 上一次心跳没等到 PONG：认为链路半死，主动断开触发重连
      if (this.pongDeadline && Date.now() > this.pongDeadline) {
        this.pongDeadline = undefined
        this.socket.close(4000, 'heartbeat timeout')
        return
      }
      this.pongDeadline = Date.now() + this.options.heartbeatMs
      this.rawSend({ type: 'PING' })
    }, this.options.heartbeatMs)
  }

  private stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      window.clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = undefined
    }
    this.pongDeadline = undefined
  }

  private flush(clientMsgNo: string, item?: PendingMessage): void {
    const target = item ?? this.pending.get(clientMsgNo)
    if (!target || this.socket?.readyState !== WebSocket.OPEN) {
      return
    }
    this.rawSend(target.payload)
  }

  private rawSend(payload: RealtimeMessage): void {
    if (this.socket?.readyState !== WebSocket.OPEN) {
      return
    }
    this.socket.send(JSON.stringify(payload))
  }

  private nextClientMsgNo(): string {
    this.seq += 1
    return `${Date.now()}-${this.seq}`
  }

  private setState(state: RealtimeState): void {
    if (this.state === state) {
      return
    }
    this.state = state
    this.options.onStateChange?.(state)
  }

  private clearTimers(): void {
    this.stopHeartbeat()
    if (this.reconnectTimer) {
      window.clearTimeout(this.reconnectTimer)
      this.reconnectTimer = undefined
    }
  }
}

/** 实时网关地址：默认走同源 /ws（开发环境由 Vite 代理到 9096） */
function resolveWsBase(): string {
  const configured = import.meta.env.VITE_WS_BASE_URL as string | undefined
  if (configured) {
    return configured.replace(/\/+$/, '')
  }
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${protocol}://${window.location.host}`
}
```

### 6.2 会话接口

### 文件：yunti-frontend/src/api/customer/session.ts

``` code-block-container
import { request } from '../request'

/** 会话状态：1-排队中、2-机器人接待、3-人工接待、4-已结束 */
export interface SessionItem {
  sessionNo: string
  status: number
  agentId?: number | null
  channelId?: number | null
  customerId?: number | null
  customerName?: string | null
  customerLevel?: number | null
  source?: string | null
  intent?: string | null
  emotion?: string | null
  startTime?: string
  endTime?: string
  lastContent?: string | null
  lastSenderType?: number | null
  lastTime?: string | null
  msgCount?: number | null
}

/** 消息：senderType 1-客户、2-坐席、3-机器人、4-系统 */
export interface SessionMessageItem {
  msgId: string
  msgNo: string
  sessionId?: number
  senderType: number
  senderId?: number | null
  msgType: number
  content: string
  sendTime: string
}

/** 访客开会话结果 */
export interface OpenSessionResult {
  sessionNo: string
  customerNo: string
  customerName: string
  visitorToken: string
  tokenExpireAt: string
  sessionStatus: number
  created: boolean
}

/** 坐席会话列表 */
export function listSessions(params: {
  status?: number
  keyword?: string
  mine?: boolean
} = {}): Promise<SessionItem[]> {
  return request<SessionItem[]>({
    url: '/customer/sessions',
    method: 'get',
    params,
  })
}

/** 会话详情 */
export function getSessionDetail(sessionNo: string): Promise<SessionItem> {
  return request<SessionItem>({
    url: `/customer/sessions/${sessionNo}`,
    method: 'get',
  })
}

/** 聊天记录（beforeId 用于向上翻页） */
export function listSessionMessages(
  sessionNo: string,
  params: { beforeId?: string; limit?: number } = {},
): Promise<SessionMessageItem[]> {
  return request<SessionMessageItem[]>({
    url: `/customer/sessions/${sessionNo}/messages`,
    method: 'get',
    params,
  })
}

/** 访客用渠道密钥开会话（无需登录） */
export function openVisitorSession(data: {
  appKey: string
  visitorKey?: string
  visitorName?: string
}): Promise<OpenSessionResult> {
  return request<OpenSessionResult>({
    url: '/customer/sessions/open',
    method: 'post',
    data,
    silent: true,
  })
}
```

### 6.3 坐席工作台

### 文件：yunti-frontend/src/views/workspace/index.vue

``` code-block-container
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
```

### 6.4 访客端

### 文件：yunti-frontend/src/views/visitor/index.vue

``` code-block-container
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
```

### 6.5 路由与开发代理

`/modules/workspace` 从占位页换成真实工作台，新增公开路由 `/visitor`；开发环境让 Vite 把 `/ws` 转发到 9096。

路由文件只需要**改三处**，不用替换整个文件：

``` code-block-container
// 1) 在后台布局的 children 里加一条：在线客服工作台
{
  path: 'modules/workspace',
  name: 'Workspace',
  component: () => import('../views/workspace/index.vue'),
  meta: { title: '在线客服' },
},

// 2) 同时把占位菜单数组里的 'workspace' 去掉（它已经有真实页面了）
...[
  ['kb', '知识库'],
  ['tickets', '工单管理'],
  // ...其余不变
].map(([path, title]) => ({ ... }))

// 3) 在公开路由区（/guide 之后）加一条：访客端
{
  path: '/visitor',
  name: 'Visitor',
  component: () => import('../views/visitor/index.vue'),
  meta: { title: '在线客服', public: true },
},
```

访客端开会话失败时要自己展示错误状态，不想再叠一个全局提示，所以请求层补一个 `silent` 开关（置 true 时失败不弹全局提示，交给调用方处理）。这是访客端唯一依赖的额外改动：

### 文件：yunti-frontend/src/api/request.ts

``` code-block-container
import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import { clearToken, getToken, getTenantCode } from '../utils/auth'
import type { ApiResponse } from '../types'

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
})

/** 请求配置：silent=true 时失败不弹全局提示（用于权限探测等可预期的失败） */
export interface RequestConfig extends AxiosRequestConfig {
  silent?: boolean
}

let unauthorizedHandling = false

/** 登录失效统一处理：专业提示 → 清空登录态 → 跳转登录页 */
function handleUnauthorized(message?: string) {
  if (unauthorizedHandling) return
  unauthorizedHandling = true
  ElMessageBox.alert(
    message === '未认证或登录已过期' ? '当前登录状态已过期或账号已在其他设备登录，请重新登录后继续使用。' : (message || '登录状态已失效，请重新登录'),
    '登录状态已失效',
    {
      type: 'warning',
      confirmButtonText: '重新登录',
      closeOnClickModal: false,
      showClose: false,
    },
  )
    .finally(() => {
      clearToken()
      localStorage.removeItem('yunti_admin_tenant')
      localStorage.removeItem('yunti_onboard_done')
      localStorage.removeItem('yunti_onboard_guide_hidden')
      window.location.href = '/login'
    })
}

// 请求拦截：携带 token 与租户上下文
service.interceptors.request.use((config) => {
  const token = getToken()
  const tenantCode = getTenantCode()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  if (tenantCode) {
    config.headers['X-Tenant-Code'] = tenantCode
  }
  return config
})

// 响应拦截：仅处理 HTTP 层错误
service.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error?.response?.status === 401) {
      handleUnauthorized()
      return Promise.reject(error)
    }
    const message = error?.response?.data?.message || error?.message || '网络异常，请稍后重试'
    if (!(error?.config as RequestConfig | undefined)?.silent) {
      ElMessage.error(message)
    }
    return Promise.reject(error)
  },
)

/** 统一请求方法：自动解包业务码，成功返回 data */
export async function request<T = unknown>(config: RequestConfig): Promise<T> {
  const response = await service.request<ApiResponse<T>>(config)
  const body = response.data
  if (body.code !== 0) {
    if (body.code === 40100 || body.code === 40110) {
      handleUnauthorized(body.message)
      throw new Error(body.message || 'request failed')
    }
    if (!config.silent) {
      ElMessage.error(body.message || '请求失败')
    }
    throw new Error(body.message || 'request failed')
  }
  return body.data
}

export default service
```

### 文件：yunti-frontend/vite.config.ts

``` code-block-container
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  // 默认走本地网关 9090；联调不同后端时可用 VITE_PROXY_TARGET 覆盖
  const apiTarget = env.VITE_PROXY_TARGET || 'http://127.0.0.1:9090'
  // 实时网关（WebSocket 长连接）单独一个端口，默认 9096
  const wsTarget = env.VITE_WS_PROXY_TARGET || 'http://127.0.0.1:9096'
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        // 开发环境代理到后端服务；生产环境由 API 网关统一转发
        '/api/user': {
          target: apiTarget,
          changeOrigin: true,
        },
        '/api/tenant': {
          target: apiTarget,
          changeOrigin: true,
        },
        '/api/customer': {
          target: apiTarget,
          changeOrigin: true,
        },
        '/api/ai': {
          target: apiTarget,
          changeOrigin: true,
        },
        // WebSocket 长连接：开发环境由 Vite 转发到实时网关
        '/ws': {
          target: wsTarget,
          ws: true,
          changeOrigin: true,
        },
      },
    },
  }
})
```

## 七、运行与验证

### 7.1 后端

打开四个终端，按下面顺序启动（都在 yunti-backend 目录下执行）：

``` code-block-container
mvn -pl yunti-common install -DskipTests          # 新模块依赖 common，先装一次
```

``` code-block-container
mvn -pl yunti-customer-service spring-boot:run    # 9093  会话与消息落库
```

``` code-block-container
mvn -pl yunti-realtime-service spring-boot:run    # 9096  实时网关（长连接）
```

``` code-block-container
mvn -pl yunti-gateway spring-boot:run             # 9090  HTTP 网关，前端 /api 都走它
```

为什么实时网关要单独起：HTTP 网关无状态、可以随便扩容；长连接有状态，按连接数扩容，两者分开互不拖累。前端请求 `/api/**` 走 9090，连 WebSocket 走 9096（开发环境由 Vite 把 `/ws` 代理过去）。

实时网关启动后控制台会打印业务入口 `http://localhost:9096/api/realtime`（actuator 探活用），真正的入口是 `ws://localhost:9096/ws/realtime?token=xxx`。

### 7.2前端

``` code-block-container
cd yunti-frontend
npm install
npm run dev        # http://localhost:5173
```

### 7.3 界面上怎么试

1.  找一条启用中的渠道密钥：`select app_key from channel_key where status = 1 and is_deleted = false;`

2.  浏览器开两个标签：一个 `http://localhost:5173/visitor?key=<渠道密钥>` 当访客，另一个 `http://localhost:5173/modules/workspace` 当坐席；

      

<img src="https://article-images.zsxq.com/FoESiqq1JVY-t6qwM-zELeP6Br9s" class="tiptap-image" alt="图片.png" />

1.  访客先发一句，坐席工作台左侧立刻出现会话，点进去就能回；

      

<img src="https://article-images.zsxq.com/FiT5y0bDreKHIf0GediCZKvAslcC" class="tiptap-image" alt="图片.png" />

1.  把访客那个标签的网络断掉几秒再恢复，消息不会丢，连上后自动补发。

  

<img src="https://article-images.zsxq.com/Fp_Xj9M82SzRfzNB4PAvWsOx73Cn" class="tiptap-image" alt="图片.png" />

## 总结

这一篇做完，"客户能找客服聊天"这条主线终于闭环了：

1.  **双网关各司其职**：HTTP 无状态走 9090，长连接有状态走 9096，谁都不会拖累谁；

2.  **一个库一个写入口**：实时网关不碰客户库，会话与消息统一由 customer-service 落库，边界清楚；

3.  **消息不丢**：先落库再 ACK 再广播，客户端未确认的消息重连后重发，坐席上线自动补历史；

4.  **连接可管**：握手鉴权、心跳保活、空闲回收、单连接限流，异常连接不会赖着不走；

5.  **前端只管业务**：心跳、重连、发送确认都封装在 RealtimeClient 里，页面代码很干净。

更重要的是，这条链路一通，很多功能就有了数据源头：质检中心可以用真实会话质检、报表可以统计真实会话量、知识库可以从真实问答里挖掘话术。
