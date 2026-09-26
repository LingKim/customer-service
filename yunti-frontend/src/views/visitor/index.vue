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
        <div ref="scrollRef" class="v-body" @scroll.passive="onScroll">
          <div v-if="connecting" class="v-tip">
            <el-icon class="is-loading" :size="16"><Loading /></el-icon>
            正在接入客服…
          </div>
          <div v-else-if="!messages.length" class="v-tip">已经接通，直接把问题发给我们吧～</div>
          <template v-for="(msg, index) in messages" :key="msg.msgId">
            <!-- 时间分割：和上一条隔得久（或第一条）才显示一次，不再每条都挂时间 -->
            <div v-if="showTimeDivider(index)" class="v-divider">{{ dividerText(msg.sendTime) }}</div>
            <!-- 系统提示：居中灰胶囊，没有头像也没有气泡（"已接入智能客服"这类） -->
            <div v-if="msg.senderType === 4" class="v-system">{{ msg.content }}</div>
            <div v-else class="v-row" :class="msgRowClass(msg)">
              <!-- 头像：机器人用品牌标识，人工客服用工单耳机图标，自己用中性圆形 -->
              <div v-if="msg.senderType === 1" class="v-avatar v-avatar-self">我</div>
              <div v-else-if="msg.senderType === 2" class="v-avatar v-avatar-agent">
                <el-icon :size="15"><Service /></el-icon>
              </div>
              <img v-else class="v-avatar v-avatar-bot" src="/yunti-mark.svg" alt="智能客服" />

              <div class="v-main">
                <div v-if="msg.senderType !== 1 && senderText(msg)" class="v-meta">{{ senderText(msg) }}</div>
                <div class="v-bubble" :class="{ 'is-pending': msg.pending, 'is-failed': msg.failed }">
                  <!-- 卡片消息：正文 + 可点动作（比如"转人工客服"）。点了才转，不替客户做主 -->
                  <template v-if="cardOf(msg)">
                    <div class="v-content">{{ cardOf(msg)?.text }}</div>
                    <div v-if="cardOf(msg)?.actions?.length" class="v-actions">
                      <el-button
                        v-for="action in cardOf(msg)?.actions"
                        :key="action.type"
                        size="small"
                        type="primary"
                        plain
                        @click="onCardAction(action)"
                      >
                        {{ action.label }}
                      </el-button>
                    </div>
                  </template>
                  <div v-else class="v-content">{{ msg.content }}</div>
                  <!-- 时间：鼠标悬停时才显示（触屏常驻），平时的阅读节奏更干净 -->
                  <div class="v-time">
                    <template v-if="msg.pending">
                      {{ connected ? '发送中…' : '已排队，连接恢复后自动发出…' }}
                    </template>
                    <template v-else-if="msg.failed">
                      <span>发送失败（消息已保留，恢复后自动补发）</span>
                      <el-button link type="primary" size="small" @click="resend(msg)">立即重发</el-button>
                    </template>
                    <template v-else>{{ msgTime(msg.sendTime) }}</template>
                  </div>
                </div>
              </div>
              <!-- 复制按钮：鼠标移到这条消息上才出现，放在气泡外侧不挡正文 -->
              <button class="v-copy" type="button" title="复制这条消息" @click="copyMessage(msg)">
                <el-icon :size="13"><CopyDocument /></el-icon>
              </button>
            </div>
          </template>
          <!-- 自己翻上去看记录时，新消息不硬拽，只提示一下 -->
          <button v-if="hasNewBelow" class="v-new" type="button" @click="jumpToBottom">
            有新消息 ↓
          </button>
          <!-- 正在输入：紧跟在最后一条消息下面，客户一眼能看到"有人正在回我" -->
          <div v-if="botTyping" class="v-row is-other">
            <div v-if="typingWho === 'AGENT'" class="v-avatar v-avatar-agent">
              <el-icon :size="15"><Service /></el-icon>
            </div>
            <img v-else class="v-avatar v-avatar-bot" src="/yunti-mark.svg" alt="智能客服" />
            <div class="v-main">
              <div class="v-meta">{{ typingWho === 'AGENT' ? '在线客服' : botName }}</div>
              <div class="v-bubble v-typing">
                <div class="v-typing-dots"><i /><i /><i /></div>
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
            <el-button
              v-else
              type="primary"
              :loading="sending"
              :disabled="!sessionOpened"
              @click="send"
            >
              发送
            </el-button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CopyDocument, Loading, Service, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listSessionMessages, openVisitorSession, type SessionMessageItem } from '../../api/customer/session'
import { RealtimeClient, type RealtimeMessage, type RealtimeState } from '../../utils/realtime'
import { copyText } from '../../utils/clipboard'
import { useChatScroll } from '../../utils/chatScroll'

const route = useRoute()
const router = useRouter()

/** 被 widget.js 嵌进 iframe 时用紧凑布局（去掉大背景和外边距） */
const isEmbed = computed(() => String(route.query.embed ?? '') === '1')

const sessionNo = ref('')
/** 当前访客令牌：重连时要用最新的那一份 */
const visitorToken = ref('')
/**
 * 机器人显示名（来自租户配置的 bot_name，开会话时随响应回来）。
 *
 * <p>客户侧不写"机器人"三个字：大厂客服窗口给客户看的都是**助手名字**
 * （店小蜜、小云这种）或者中性的"智能客服"，不会每条消息都提醒客户"你在跟机器说话"。</p>
 */
const botName = ref('智能客服')
const messages = ref<SessionMessageItem[]>([])
const draft = ref('')
const sending = ref(false)
const connecting = ref(true)
/** 会话是否已经建立（拿到访客令牌 + 建了长连接对象）：没建立之前发送按钮是灰的 */
const sessionOpened = ref(false)
const errorText = ref('')
/** 出错时的补充说明：会话失效和"没带 appId"要给不一样的指引 */
const errorHint = ref('')
const scrollRef = ref<HTMLElement>()
const connectionState = ref<RealtimeState>('idle')
/** 对面是否正在输入（机器人/客服）：让"发完消息干等"变成看得见的等待 */
const botTyping = ref(false)
const typingWho = ref<'BOT' | 'AGENT'>('BOT')
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
      if (hasAgent.value) {
        return '客服在线'
      }
      // 状态 2 = 机器人接待中：客户对面确实有人在回话，不该显示"等待客服接入"
      return sessionStatus.value === 2 ? '智能客服为您服务' : '等待客服接入'
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
    // 机器人接待中是真有人在回话，给绿灯；等人工才是黄灯
    return sessionStatus.value === 2 ? 'dot-open' : 'dot-waiting'
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
  if (hasAgent.value) {
    return '客服通常会在 1 分钟内回复'
  }
  return sessionStatus.value === 2
    ? '智能客服会先帮您解答，需要人工时会自动转接'
    : '正在为您分配客服，请稍候'
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
    if (result.botName) {
      botName.value = result.botName
    }
    sessionOpened.value = true
    // 开会话时就知道状态了（2=机器人接待）：先用它把顶部文案定下来，
    // 不用等长连接 JOINED 回来才从"等待客服接入"跳到"智能客服为您服务"
    if (typeof result.sessionStatus === 'number') {
      sessionStatus.value = result.sessionStatus
    }
    localStorage.setItem(storageKey, result.visitorIdentityToken)
    connect(result.visitorToken)
  } catch (e) {
    connecting.value = false
    errorText.value = e instanceof Error ? e.message : '接入失败，请稍后重试'
  }
}

function connect(token: string) {
  visitorToken.value = token
  client = new RealtimeClient({
    token,
    // 重连取最新令牌：访客令牌也有有效期（12 小时），过期后要用新换的那份
    tokenProvider: () => visitorToken.value,
    onAuthFailed: () => void refreshVisitorToken(),
    onMessage: handleMessage,
    onStateChange: (state) => {
      connectionState.value = state
    },
  })
  client.connect()
}

/**
 * 访客令牌失效后重新换一个再重连。
 *
 * <p>访客不用登录，所以"续期"就是拿本地身份令牌重新开一次会话：
 * 服务端按身份令牌认出还是同一个客户、复用同一条未结束的会话，消息不会串。</p>
 */
let refreshingToken = false
async function refreshVisitorToken() {
  const appKey = String(route.query.appId ?? route.query.key ?? '').trim()
  if (refreshingToken || !appKey) {
    return
  }
  refreshingToken = true
  try {
    const storageKey = `yunti_visitor_${appKey}`
    const result = await openVisitorSession({
      appKey,
      visitorToken: localStorage.getItem(storageKey) || undefined,
      visitorName: String(route.query.name ?? '').trim() || undefined,
    })
    sessionNo.value = result.sessionNo
    localStorage.setItem(storageKey, result.visitorIdentityToken)
    client?.close()
    connect(result.visitorToken)
  } catch {
    // 换不到就先不折腾：页面上的「重新发起咨询」按钮仍然可用
  } finally {
    refreshingToken = false
  }
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
      // 刚进会话：从最新一条开始看
      scrollToBottom(true)
      // 断线期间可能漏了消息：拿本地最大序号和服务端对一下，缺的补回来
      void syncMissedMessages(message.data?.lastSeq)
      break
    }
    case 'ACK':
      appendMessage(message.data as SessionMessageItem)
      break
    case 'MESSAGE':
      // 真消息到了，输入提示就该收掉（服务端也会补一帧 typing=false）
      botTyping.value = false
      appendMessage(message.data as SessionMessageItem)
      void fillGapIfNeeded(message.data as SessionMessageItem | undefined)
      break
    case 'TYPING': {
      const payload = (message.data ?? {}) as { who?: string; typing?: boolean }
      typingWho.value = payload.who === 'AGENT' ? 'AGENT' : 'BOT'
      botTyping.value = payload.typing !== false
      scheduleTypingTimeout()
      // 提示气泡出现/消失都会改高度，跟着滚一下才不会"半截露在外面"
      scrollToBottom()
      break
    }
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

function send() {
  const content = draft.value.trim()
  if (!content) {
    return
  }
  draft.value = ''
  void sendText(content)
}

/** 真正发一条消息（卡片上的"转人工客服"也走这里，保证行为完全一致） */
async function sendText(text: string) {
  const content = text.trim()
  if (!content || sessionClosed.value) {
    return
  }
  if (!client || !sessionOpened.value) {
    ElMessage.warning('正在接入客服，稍等一下再发送')
    return
  }
  // 消息号由页面生成并挂到气泡上：长连接那边不管连没连上都先收进发件箱，
  // 连上以后自动补发；服务端按这个消息号去重，所以不会重复。
  const clientMsgNo = `v-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  // 乐观气泡：先显示"发送中"，拿到 ACK 再换成服务端消息（序号、时间都以服务端为准）
  messages.value.push({
    msgId: `local-${clientMsgNo}`,
    msgNo: `local-${clientMsgNo}`,
    clientMsgNo,
    senderType: 1,
    msgType: 1,
    content,
    sendTime: new Date().toISOString(),
    pending: true,
  })
  // 自己发的消息一定要出现在眼前
  scrollToBottom(true)
  sending.value = true
  try {
    const ack = await client.send({
      type: 'SEND',
      sessionNo: sessionNo.value,
      msgType: 1,
      content,
      clientMsgNo,
    })
    const saved = ack.data as SessionMessageItem | undefined
    if (saved) {
      appendMessage(saved)
    }
  } catch (e) {
    // 消息仍在发件箱里，网络恢复会自动补发（同一个 clientMsgNo，服务端幂等）
    markFailed(clientMsgNo)
    ElMessage.error(e instanceof Error ? e.message : '发送失败，消息已保留')
  } finally {
    sending.value = false
  }
}

/**
 * 复制一条消息。
 *
 * <p>客户经常要把客服给的答案（比如退款时效、订单处理说明）复制走，
 * 或者把聊天记录发给同事；允许选中复制是底线，再补一个悬停即用的复制按钮。</p>
 */
async function copyMessage(message: SessionMessageItem) {
  const ok = await copyText(message.content || '')
  if (ok) {
    ElMessage.success('已复制')
  } else {
    ElMessage.warning('复制失败，请手动选中文字后 Ctrl/Cmd + C')
  }
}

/**
 * 卡片消息（msgType=3）的正文结构：{"text": "...", "actions": [{"type","label"}]}。
 *
 * <p>解析失败（或历史数据不是 JSON）时返回 null，调用方会退回按纯文本渲染——
 * 老消息、脏数据都不会把页面搞崩。</p>
 */
interface MessageCardAction {
  type: string
  label: string
}
interface MessageCard {
  text: string
  actions?: MessageCardAction[]
}

function cardOf(message: SessionMessageItem): MessageCard | null {
  if (message.msgType !== 3 || !message.content) {
    return null
  }
  try {
    const parsed = JSON.parse(message.content) as MessageCard
    return typeof parsed?.text === 'string' ? parsed : null
  } catch {
    return null
  }
}

/** 卡片上的动作：目前只有"转人工客服"——点了就发一句"转人工"，交给客服大脑走转人工流程 */
function onCardAction(action: MessageCardAction) {
  if (action.type === 'TRANSFER_HUMAN') {
    void sendText('转人工')
  }
}

/** 把某条本地气泡标成发送失败（按消息号定位，别用位置或内容去猜） */
function markFailed(clientMsgNo: string) {
  messages.value = messages.value.map((item) =>
    item.clientMsgNo === clientMsgNo ? { ...item, pending: false, failed: true } : item,
  )
}

/** 失败气泡上的「立即重发」：沿用同一个消息号，服务端幂等去重 */
function resend(message: SessionMessageItem) {
  if (!message.clientMsgNo || !client) {
    return
  }
  messages.value = messages.value.map((item) =>
    item.clientMsgNo === message.clientMsgNo ? { ...item, pending: true, failed: false } : item,
  )
  client.retry(message.clientMsgNo)
}

/**
 * "正在输入"的安全绳。
 *
 * <p>推送可能丢（断线、网关重启），提示要是没人收就永远转下去，反而更糟。
 * 40 秒还没等到回复就自动熄灭——比真实响应时间留足余量，又不会一直挂着。</p>
 */
let typingTimer: number | undefined
function scheduleTypingTimeout() {
  if (typingTimer) {
    window.clearTimeout(typingTimer)
  }
  if (!botTyping.value) {
    return
  }
  typingTimer = window.setTimeout(() => {
    botTyping.value = false
  }, 40000)
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
  if (!message) {
    return
  }
  if (messages.value.some((item) => item.msgId === message.msgId)) {
    return
  }
  // 断线重发 / ACK 迟到时，服务端回来的这条和本地那条"乐观气泡"是同一个 clientMsgNo：
  // 就地替换，别让客户看到"一条发送失败 + 一条又发成功了"这两个气泡
  const localIndex = message.clientMsgNo
    ? messages.value.findIndex((item) => item.clientMsgNo === message.clientMsgNo)
    : -1
  if (localIndex >= 0) {
    messages.value.splice(localIndex, 1, message)
    scrollToBottom()
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

/**
 * 聊天区滚动交给公共工具：双 rAF 保证滚到真正的底部（消息插入、气泡换行、
 * "正在输入"提示消失都会改变高度，早一步滚就会差一点点），
 * 而且客户往上翻记录时不会被新消息硬拽下去，只提示"有新消息 ↓"。
 */
const { hasNewBelow, scrollToBottom, onScroll, jumpToBottom } = useChatScroll(scrollRef)

function msgRowClass(message: SessionMessageItem) {
  if (message.senderType === 1) {
    return 'is-self'
  }
  if (message.senderType === 4) {
    return 'is-system'
  }
  return 'is-other'
}

/**
 * 消息标签：客户看到的是"名字"，不是"角色"。
 *
 * <p>机器人显示租户配置的名字（如"小云"），人工显示"在线客服"，
 * 系统提示不带名字（就是一条灰色提示）。</p>
 */
function senderText(message: SessionMessageItem) {
  if (message.senderType === 2) {
    return '在线客服'
  }
  if (message.senderType === 3) {
    return botName.value || '智能客服'
  }
  return ''
}

/** 消息时间：展示成 09-12 12:25，客户翻历史时能对上时间点 */
/** 两条消息间隔超过这个时长就插一条时间分割线（和微信/企业微信一个口径） */
const TIME_DIVIDER_GAP_MS = 5 * 60 * 1000

/** 要不要在这条消息前面显示时间分割线 */
function showTimeDivider(index: number) {
  const current = parseTime(messages.value[index]?.sendTime)
  if (!current) {
    return false
  }
  if (index === 0) {
    return true
  }
  const previous = parseTime(messages.value[index - 1]?.sendTime)
  return !previous || current - previous > TIME_DIVIDER_GAP_MS
}

function parseTime(value?: string | null): number {
  if (!value) {
    return 0
  }
  const time = new Date(value.replace(' ', 'T')).getTime()
  return Number.isNaN(time) ? 0 : time
}

/** 分割线文案：今天 / 昨天 只写时间，更早的写日期 */
function dividerText(value?: string | null) {
  const time = parseTime(value)
  if (!time) {
    return ''
  }
  const date = new Date(time)
  const clock = `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  const today = new Date()
  const sameDay = date.toDateString() === today.toDateString()
  if (sameDay) {
    return clock
  }
  const yesterday = new Date(today.getTime() - 24 * 3600 * 1000)
  if (date.toDateString() === yesterday.toDateString()) {
    return `昨天 ${clock}`
  }
  return `${date.getMonth() + 1}月${date.getDate()}日 ${clock}`
}

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
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;
}

.v-row.is-self {
  /* 自己的消息靠右：从左到右是「复制按钮 → 气泡 → 头像」 */
  justify-content: flex-end;
}

/* 自己这条没有名字行，头像跟气泡顶部对齐即可 */
.v-row.is-self .v-avatar {
  margin-top: 2px;
}

/* 头像：机器人用品牌标识，人工客服用耳机图标，自己用中性圆形。
   32px 是客服窗口的通用尺寸（再大就喧宾夺主，再小看不清） */
.v-avatar {
  flex-shrink: 0;
  /* 三个头像必须一模一样大：显式锁死尺寸，避免某处样式或内边距把其中一种撑大 */
  width: 32px;
  height: 32px;
  min-width: 32px;
  max-width: 32px;
  min-height: 32px;
  max-height: 32px;
  padding: 0;
  margin-top: 16px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background: #fff;
  border: 1px solid #e8eef6;
}

/* 机器人：浅蓝底 + 居中品牌标，和人工客服的实心蓝保持同样的视觉重量 */
.v-avatar-bot {
  object-fit: contain;
  background: #eff6ff;
  border-color: #dbeafe;
  padding: 6px;
}

.v-avatar-agent {
  background: linear-gradient(135deg, #1d4ed8, #3b82f6);
  border-color: transparent;
  color: #fff;
}

.v-avatar-self {
  background: #eef2f7;
  border-color: #e2e8f0;
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
}

/* 名字 + 气泡竖排；气泡最大宽度在这里控制（扣掉头像和间距） */
.v-main {
  min-width: 0;
  max-width: 78%;
  display: flex;
  flex-direction: column;
}

.v-row.is-self .v-main {
  align-items: flex-end;
}

/* 时间分割线：居中灰字，代替"每条消息都挂时间" */
.v-divider {
  text-align: center;
  font-size: 11px;
  color: #a3aec2;
  margin: 10px 0 8px;
}

/* 系统提示：居中灰胶囊，没有头像也没有气泡 */
.v-system {
  margin: 8px auto;
  padding: 4px 12px;
  max-width: 88%;
  border-radius: 999px;
  background: #f1f5f9;
  color: #7c8aa5;
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
}

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
