<template>
  <div class="ws-page">
    <!-- 顶部：连接状态与筛选 -->
    <div class="ws-toolbar">
      <div class="ws-title">
        <span class="dot" :class="stateClass" />
        <span class="name">在线客服工作台</span>
        <el-tag size="small" :type="stateTagType" effect="light">{{ stateText }}</el-tag>
        <span class="tip">我的接待 {{ myCount }} 单 / 待接待 {{ queueCount }} 单</span>
      </div>
      <div class="ws-filters">
        <el-radio-group v-model="scope" size="default" @change="onScopeChange">
          <el-radio-button value="queue">待接待</el-radio-button>
          <el-radio-button value="mine">我的会话</el-radio-button>
          <el-radio-button value="all">全部</el-radio-button>
        </el-radio-group>
        <el-input
          v-model.trim="keyword"
          placeholder="搜索会话号 / 客户名"
          clearable
          style="width: 200px"
          :prefix-icon="Search"
          @keyup.enter="loadSessions"
          @clear="loadSessions"
        />
        <el-button
          v-if="visitorTestChannel"
          :icon="Promotion"
          title="以新访客身份打开测试会话，每次都是一条新的待接待"
          @click="openVisitorTest"
        >
          模拟访客
        </el-button>
        <el-button :icon="Refresh" circle :loading="loading" @click="loadSessions" />
      </div>
    </div>
    <div class="ws-body">
      <!-- 左：会话列表 -->
      <div class="ws-list">
        <div class="list-head">
          <span class="lh-title">{{ scopeLabel }}</span>
          <span class="lh-count">{{ sessions.length }}</span>
          <span class="lh-sort">最新消息优先</span>
        </div>
        <div v-if="loading && !sessions.length" class="list-skeleton">
          <div v-for="n in 4" :key="n" class="sk-item">
            <div class="sk-avatar" />
            <div class="sk-lines">
              <div class="sk-line" style="width: 52%" />
              <div class="sk-line" style="width: 92%" />
              <div class="sk-line" style="width: 38%" />
            </div>
          </div>
        </div>
        <div v-else-if="!sessions.length" class="list-empty">
          <el-icon :size="30"><ChatDotRound /></el-icon>
          <p>{{ scope === 'queue' ? '当前没有等待接入的客户' : '这里还没有会话' }}</p>
          <span>{{ scope === 'queue' ? '客户发起咨询后会实时出现在这里' : '换个筛选条件或关键词试试' }}</span>
        </div>
        <div v-else class="list-body">
          <div
            v-for="item in sessions"
            :key="item.sessionNo"
            class="list-item"
            :class="{ active: item.sessionNo === activeSessionNo, waiting: !item.agentId && item.status !== 4 }"
            role="button"
            tabindex="0"
            @click="openSession(item)"
            @keydown.enter.prevent="openSession(item)"
          >
            <div class="li-avatar" :class="avatarClass(item)">
              {{ avatarText(item) }}
              <i class="li-state" :class="avatarStateClass(item)" />
            </div>
            <div class="li-main">
              <div class="li-top">
                <span class="li-name">{{ item.customerName || '访客' }}</span>
                <span v-if="levelTag(item.customerLevel)" class="li-level">{{ levelTag(item.customerLevel) }}</span>
                <span class="li-time">{{ shortTime(item.lastTime || item.startTime) }}</span>
              </div>
              <div class="li-mid">
                <span class="li-preview">{{ preview(item) }}</span>
                <span v-if="unreadOf(item)" class="li-unread">{{ unreadText(item) }}</span>
              </div>
              <div class="li-bottom">
                <span class="li-chip" :class="statusClass(item)">{{ statusLabel(item) }}</span>
                <span class="li-channel">
                  <el-icon :size="12"><component :is="channelIcon(item)" /></el-icon>
                  {{ channelText(item) }}
                </span>
                <span class="li-extra">
                  <el-icon v-if="isWaitingSession(item)" :size="12"><Clock /></el-icon>
                  {{ metaText(item) }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>
      <!-- 右：聊天窗口 -->
      <div class="ws-chat">
        <template v-if="activeSession">
          <div class="chat-head">
            <div>
              <div class="ch-name">
                {{ activeSession.customerName || '访客' }}
                <el-tag size="small" :type="activeSessionTag.type" effect="light">
                  {{ activeSessionTag.text }}
                </el-tag>
              </div>
              <div class="ch-sub">
                会话 {{ activeSession.sessionNo }} · {{ activeSession.source || '未知渠道' }} ·
                <span :class="`sub-${activeChatState}`">{{ activeChatStateText }}</span><template
                  v-if="!isClosed && assistAgents.length"
                > · 协助中：{{ assistAgents.join('、') }}</template>
              </div>
            </div>
            <div class="ch-right">
              <el-button v-if="!isClosed && !activeSession.agentId" size="small" type="primary" @click="claim">
                接入会话
              </el-button>
              <el-button v-if="!isClosed && isMine" size="small" @click="release">退回队列</el-button>
              <el-button v-if="!isClosed" size="small" @click="openTransfer">转接</el-button>
              <el-button
                v-if="!isClosed"
                size="small"
                :loading="loadingMore"
                :disabled="!hasMore"
                :title="hasMore ? '往上翻，看更早的聊天记录' : '已经是最早的消息了'"
                @click="loadMore"
              >
                更早消息
              </el-button>
              <el-button v-if="!isClosed" size="small" type="danger" plain @click="openClose">
                结束会话
              </el-button>
            </div>
          </div>
          <div v-if="activeVisitorOffline" class="chat-offline">
            <el-icon :size="14"><Warning /></el-icon>
            <span>
              客户已离线{{ activeOfflineText ? ` ${activeOfflineText}` : '' }}，消息会保留、客户回来仍能看到；
              离线超过 {{ offlineCloseMinutes }} 分钟将自动结束会话。
            </span>
            <el-button link type="primary" size="small" @click="openClose">立即结束</el-button>
          </div>
          <div ref="scrollRef" class="chat-body">
            <div v-if="loadingHistory" class="chat-tip">正在加载聊天记录…</div>
            <div
              v-for="msg in messages"
              :key="msg.msgId"
              class="msg-row"
              :class="msgRowClass(msg)"
            >
              <div class="msg-bubble" :class="{ 'is-pending': msg.pending, 'is-failed': msg.failed }">
                <div class="msg-meta">
                  {{ senderText(msg) }}
                  <span v-if="msg.visibleTo === 2" class="note-tag">内部备注</span>
                </div>
                <div class="msg-content">{{ msg.content }}</div>
                <div class="msg-time">
                  <template v-if="msg.pending">发送中…</template>
                  <template v-else-if="msg.failed">发送失败，网络恢复后自动补发</template>
                  <template v-else>{{ fullTime(msg.sendTime) }}</template>
                </div>
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
              :placeholder="noteMode ? '写一条内部备注，只有客服同事能看到' : '输入回复内容，Enter 发送，Shift + Enter 换行'"
              @keydown.enter.exact.prevent="send"
            />
            <div class="ci-actions">
              <div class="ci-left">
                <el-switch v-model="noteMode" :disabled="isClosed" active-text="内部备注" />
                <span class="ci-tip">
                  {{ noteMode ? '备注不会发给客户' : isClosed ? '会话已结束' : '消息会实时送达客户' }}
                </span>
              </div>
              <el-button type="primary" :loading="sending" :disabled="isClosed" @click="send">
                {{ noteMode ? '记录备注' : '发送' }}
              </el-button>
            </div>
          </div>
        </template>
        <div v-else class="chat-empty">
          <el-icon :size="36"><ChatDotRound /></el-icon>
          <p>从左侧选择一条会话开始接待</p>
          <span>待接待里的会话可以「接入会话」认领，认领后就是你的客户了</span>
        </div>
      </div>
    </div>
    <!-- 转接弹窗 -->
    <el-dialog v-model="transferVisible" title="转接会话" width="460px" :close-on-click-modal="false">
      <div class="dialog-tip">转接后由新的客服负责接待，客户会看到一条转接提示。</div>
      <el-form label-position="top">
        <el-form-item label="转接给">
          <el-select v-model="transferForm.toAgentId" placeholder="请选择客服" style="width: 100%">
            <el-option
              v-for="agent in transferCandidates"
              :key="agent.userId"
              :value="agent.userId"
              :label="agent.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="转接说明（选填）">
          <el-input v-model="transferForm.remark" maxlength="255" placeholder="例如：客户要咨询发票，麻烦你跟一下" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" @click="transfer">确认转接</el-button>
      </template>
    </el-dialog>
    <!-- 结束会话弹窗 -->
    <el-dialog v-model="closeVisible" title="结束会话" width="460px" :close-on-click-modal="false">
      <div class="dialog-tip">结束后客户无法继续发送消息，可以顺手记一句小结，方便质检复盘。</div>
      <el-input
        v-model="closeRemark"
        type="textarea"
        :rows="3"
        maxlength="255"
        show-word-limit
        resize="none"
        placeholder="例如：退款已提交，预计 3 个工作日到账"
      />
      <template #footer>
        <el-button @click="closeVisible = false">取消</el-button>
        <el-button type="danger" @click="closeSession">确认结束</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import {
  ChatDotRound,
  Clock,
  Iphone,
  Monitor,
  Promotion,
  Refresh,
  Search,
  Service,
  Warning,
} from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listChannels, type ChannelResult } from '../../api/customer/channel'
import { fetchPresence } from '../../api/realtime'
import { openVisitorTestTab } from '../../utils/visitor'
import {
  getSessionDetail,
  listSessionMessages,
  listSessions,
  type SessionItem,
  type SessionMessageItem,
} from '../../api/customer/session'
import { listColleagues, type ColleagueOption } from '../../api/member'
import { getToken } from '../../utils/auth'
import { useUserStore } from '../../stores/user'
import { RealtimeClient, type RealtimeMessage, type RealtimeState } from '../../utils/realtime'

const userStore = useUserStore()

/** 聊天记录分页大小（与后端接口默认值一致） */
const HISTORY_PAGE_SIZE = 30
/** 后端 JOIN 时默认回多少条历史消息；老版本没带 hasMore 时用它兜底判断 */
const JOIN_HISTORY_HINT = 50

const sessions = ref<SessionItem[]>([])
const loading = ref(false)
const keyword = ref('')
const scope = ref<'queue' | 'mine' | 'all'>('queue')

const activeSessionNo = ref('')
const activeSession = ref<SessionItem | null>(null)
const messages = ref<SessionMessageItem[]>([])
const loadingHistory = ref(false)
/** 还有没有更早的消息：没有就把「更早消息」置灰，避免点了没反应 */
const hasMore = ref(false)
const loadingMore = ref(false)
const draft = ref('')
const sending = ref(false)
const noteMode = ref(false)
const assistAgentIds = ref<number[]>([])
const scrollRef = ref<HTMLElement>()

const members = ref<ColleagueOption[]>([])
const agentOnline = ref<Record<string, { online: boolean; sessionCount: number }>>({})
/** 用来做"模拟访客"的渠道：取本企业第一个生效且有密钥的渠道 */
const visitorTestChannel = ref<ChannelResult | null>(null)

/** 访客在线状态：sessionNo → 是否在线（来自长连接的上下线事件，不用刷新页面） */
const visitorPresence = ref<Record<string, boolean>>({})
/** 是否已经收到过服务端的在线快照（收到前不猜"离线"，避免刚进页面一片灰） */
const presenceSynced = ref(false)
/** 访客离线时刻（前端收到离线通知的时间），用于显示"已离线 X 分钟" */
const offlineSince = ref<Record<string, number>>({})
/** 访客离线多久自动结束会话（分钟），由服务端下发 */
const offlineCloseMinutes = ref(10)

/** 每个会话的未读消息数（本次工作台会话内累计，打开会话即清零） */
const unreadMap = ref<Record<string, number>>({})
/** 用于「等待 3 分钟」这类相对时间的定时刷新 */
const nowTick = ref(Date.now())

const transferVisible = ref(false)
const transferForm = ref<{ toAgentId?: number; remark: string }>({ remark: '' })
const closeVisible = ref(false)
const closeRemark = ref('')

const connectionState = ref<RealtimeState>('idle')
let client: RealtimeClient | null = null
let listReloadTimer: number | undefined
let waitTimer: number | undefined
let syncTimer: number | undefined

/** 兜底对账间隔（毫秒） */
const SYNC_INTERVAL_MS = 30000

const isClosed = computed(() => activeSession.value?.status === 4)
const isMine = computed(
  () => !!activeSession.value?.agentId && Number(activeSession.value.agentId) === Number(userStore.userId),
)
const myCount = computed(
  () => sessions.value.filter((item) => Number(item.agentId) === Number(userStore.userId)).length,
)
const queueCount = computed(() => sessions.value.filter((item) => !item.agentId).length)
const scopeLabel = computed(() => {
  if (scope.value === 'queue') {
    return '待接待'
  }
  return scope.value === 'mine' ? '我的会话' : '全部会话'
})
/** 当前会话的客户是否已经离线（会话已结束就不提示了） */
const activeVisitorOffline = computed(() => {
  const sessionNo = activeSessionNo.value
  if (!sessionNo || isClosed.value) {
    return false
  }
  return visitorPresence.value[sessionNo] === false
})
const activeOfflineText = computed(() =>
  activeVisitorOffline.value ? offlineDurationText(activeSessionNo.value) : '',
)
/**
 * 聊天头部状态：已结束 / 客户在线 / 客户已离线。
 *
 * <p>先前这里只有"离线 / 否则在线"两种，已结束的会话会落进"在线"那一支，
 * 于是出现"会话已结束还显示客户在线"的假象。</p>
 */
const activeChatState = computed<'closed' | 'online' | 'offline'>(() => {
  if (isClosed.value) {
    return 'closed'
  }
  return activeVisitorOffline.value ? 'offline' : 'online'
})
const activeChatStateText = computed(
  () => ({ closed: '会话已结束', online: '客户在线', offline: '客户已离线' })[activeChatState.value],
)
/** 会话头部的负责人标签：已结束的会话不该再写"待接待" */
const activeSessionTag = computed<{ text: string; type: 'info' | 'warning' | 'success' }>(() => {
  if (isClosed.value) {
    return { text: '已结束', type: 'info' }
  }
  if (!activeSession.value?.agentId) {
    return { text: '待接待', type: 'warning' }
  }
  return { text: agentLabel(activeSession.value.agentId), type: 'success' }
})
const assistAgents = computed(() =>
  assistAgentIds.value
    .filter((id) => Number(id) !== Number(activeSession.value?.agentId))
    .map((id) => agentName(id)),
)
const transferCandidates = computed(() =>
  members.value
    .filter((member) => Number(member.userId) !== Number(userStore.userId))
    .map((member) => {
      const status = agentOnline.value[String(member.userId)]
      const state = status?.online ? `在线 · 接待 ${status.sessionCount} 单` : '离线'
      return { userId: member.userId, label: `${member.name}（${state}）` }
    }),
)

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

onMounted(async () => {
  await Promise.all([loadSessions(), loadMembers(), loadVisitorTestChannel()])
  connect()
  // 首屏也走一次 HTTP 对账：万一长连接连不上（或还没建好），在线状态也不会先错一屏
  void syncWorkspace()
  // 「等待 X 分钟」需要跟着时间走，30 秒刷一次即可
  waitTimer = window.setInterval(() => {
    nowTick.value = Date.now()
  }, 30000)
  // 兜底对账：后台标签页会被浏览器节流，长连接也可能悄悄断过，
  // 定期重新拉一次列表 + 在线快照，页面不会一直停在旧状态
  syncTimer = window.setInterval(() => {
    void syncWorkspace()
  }, SYNC_INTERVAL_MS)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  if (listReloadTimer) {
    window.clearTimeout(listReloadTimer)
    listReloadTimer = undefined
  }
  if (waitTimer) {
    window.clearInterval(waitTimer)
    waitTimer = undefined
  }
  if (syncTimer) {
    window.clearInterval(syncTimer)
    syncTimer = undefined
  }
  document.removeEventListener('visibilitychange', onVisibilityChange)
  client?.close()
})

async function loadMembers() {
  try {
    members.value = await listColleagues()
  } catch {
    // 成员列表拿不到不影响接待，只是转接时看不到名字
  }
}

/** 有渠道才能模拟访客；没有就干脆不显示按钮，别给坐席一个点了报错的入口 */
async function loadVisitorTestChannel() {
  try {
    const channels = await listChannels()
    visitorTestChannel.value = channels.find((item) => !!item.appKey && item.status !== 2) || null
  } catch {
    visitorTestChannel.value = null
  }
}

/**
 * 模拟访客：以本企业渠道身份打开访客页，坐席自己就能造一条测试会话。
 *
 * <p>固定用"新访客"打开：连点几次就是几条不同的待接待，方便测多会话；
 * appId 是渠道的公开应用标识，正式接入由官网挂件（widget.js）注入。</p>
 */
function openVisitorTest() {
  const channel = visitorTestChannel.value
  if (!channel || !openVisitorTestTab(channel, { fresh: true })) {
    ElMessage.warning('还没有可用渠道，请先到「渠道接入」创建渠道')
  }
}

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
      if (state === 'open' && activeSessionNo.value) {
        client?.post({ type: 'JOIN', sessionNo: activeSessionNo.value })
      }
      // 重连期间可能漏掉了推送，连上后补一次列表同步
      if (state === 'open') {
        scheduleListReload()
      }
    },
  })
  client.connect()
}

function handleMessage(message: RealtimeMessage) {
  switch (message.type) {
    case 'CONNECTED': {
      // 坐席上线：服务端给一份"哪些会话的客户此刻在线"的快照。
      // 这份快照是权威的——快照里没有的会话就是离线，
      // 不能像以前那样把"没听说过"的会话默认当成在线，
      // 否则客户早关了窗口、坐席刷新后还会看到"客户在线"。
      applyPresenceSnapshot((message.data?.onlineSessions ?? []) as string[])
      if (typeof message.data?.visitorOfflineCloseMinutes === 'number') {
        offlineCloseMinutes.value = message.data.visitorOfflineCloseMinutes
      }
      break
    }
    case 'JOINED': {
      assistAgentIds.value = (message.data?.agents ?? []) as number[]
      const session = message.data?.session as SessionItem | undefined
      if (session) {
        activeSession.value = { ...(activeSession.value ?? {}), ...session } as SessionItem
      }
      messages.value = dedupe((message.data?.messages ?? []) as SessionMessageItem[])
      // 断线期间可能漏了消息：拿本地最大序号和服务端对一下，缺的增量补回来
      void syncMissedMessages(message.data?.lastSeq)
      // 服务端在 JOINED 里带了 hasMore 就以它为准；老版本没这个字段时只做"保守升级"，
      // 不要把 HTTP 首屏算出来的结果覆盖成错的值
      if (typeof message.data?.hasMore === 'boolean') {
        hasMore.value = message.data.hasMore
      } else if (messages.value.length >= JOIN_HISTORY_HINT) {
        hasMore.value = true
      }
      scrollToBottom()
      break
    }
    case 'ACK':
      appendMessage(message.data as SessionMessageItem)
      break
    case 'MESSAGE': {
      const item = message.data as SessionMessageItem | undefined
      // 坐席可能同时订阅多个会话，只有当前打开的那条才往对话窗口里塞，避免串台
      if (message.sessionNo === activeSessionNo.value) {
        appendMessage(item)
        void fillGapIfNeeded(item)
        clearUnread(message.sessionNo)
      } else if (item?.senderType === 1) {
        markUnread(message.sessionNo)
      }
      // 访客新消息会改变列表上的最后一条与排序，统一走合并刷新
      scheduleListReload()
      break
    }
    case 'SESSION': {
      const session = message.data as SessionItem | undefined
      if (session && session.sessionNo === activeSessionNo.value) {
        activeSession.value = { ...(activeSession.value ?? {}), ...session } as SessionItem
      }
      scheduleListReload()
      break
    }
    case 'QUEUE': {
      // 服务端提醒：有会话进出排队、访客上下线或访客发了新消息
      const payload = message.data ?? {}
      const sessionNo = (payload.sessionNo as string | undefined) || message.sessionNo
      if (typeof payload.visitorOnline === 'boolean' && sessionNo) {
        setVisitorPresence(sessionNo, payload.visitorOnline)
      }
      if (payload.reason === 'VISITOR_MESSAGE') {
        markUnread(sessionNo)
      }
      scheduleListReload()
      break
    }
    case 'PRESENCE': {
      assistAgentIds.value = (message.data?.agents ?? []) as number[]
      break
    }
    case 'AGENTS': {
      const map: Record<string, { online: boolean; sessionCount: number }> = {}
      for (const item of message.data?.agents ?? []) {
        map[String(item.agentId)] = { online: !!item.online, sessionCount: item.sessionCount ?? 0 }
      }
      agentOnline.value = map
      break
    }
    case 'ERROR':
      ElMessage.error(message.message || '操作失败')
      break
    default:
      break
  }
}

async function loadSessions(options: { silent?: boolean } = {}) {
  if (!options.silent) {
    loading.value = true
  }
  try {
    sessions.value = await listSessions({
      scope: scope.value,
      keyword: keyword.value || undefined,
    })
    // 已经不在当前列表里的会话，未读角标一起清掉
    const visible = new Set(sessions.value.map((item) => item.sessionNo))
    unreadMap.value = Object.fromEntries(
      Object.entries(unreadMap.value).filter(([sessionNo]) => visible.has(sessionNo)),
    )
    // 拿到过快照之后，列表里第一次出现的会话按离线记（在线的一定会推 VISITOR_ONLINE 过来）
    if (presenceSynced.value) {
      const presence = { ...visitorPresence.value }
      for (const item of sessions.value) {
        if (presence[item.sessionNo] === undefined) {
          presence[item.sessionNo] = false
        }
      }
      visitorPresence.value = presence
    }
  } catch {
    // 请求层已提示
  } finally {
    if (!options.silent) {
      loading.value = false
    }
  }
}

/**
 * 兜底对账：静默拉一次列表，并向服务端要一份在线快照。
 * 标签页在后台时直接跳过，回到前台会立刻对一次账。
 */
async function syncWorkspace() {
  if (document.visibilityState === 'hidden') {
    return
  }
  await loadSessions({ silent: true })
  const presence = await fetchPresence()
  if (presence) {
    applyPresenceSnapshot(presence.onlineSessions ?? [])
  }
}

function onVisibilityChange() {
  if (document.visibilityState === 'visible') {
    void syncWorkspace()
  }
}

/**
 * 会话列表变更后的合并刷新：访客连续发消息时会收到多条 QUEUE / MESSAGE，
 * 300ms 内只拉一次列表，避免把接口打爆。
 */
function scheduleListReload() {
  if (listReloadTimer) {
    return
  }
  listReloadTimer = window.setTimeout(() => {
    listReloadTimer = undefined
    void loadSessions()
  }, 300)
}

function onScopeChange() {
  void loadSessions()
}

async function openSession(item: SessionItem) {
  clearUnread(item.sessionNo)
  activeSessionNo.value = item.sessionNo
  activeSession.value = { ...item }
  messages.value = []
  hasMore.value = false
  assistAgentIds.value = []
  noteMode.value = false
  loadingHistory.value = true
  try {
    const [detail, history] = await Promise.all([
      getSessionDetail(item.sessionNo),
      listSessionMessages(item.sessionNo, { limit: 30 }),
    ])
    activeSession.value = detail
    messages.value = dedupe(history)
    hasMore.value = history.length >= HISTORY_PAGE_SIZE
    scrollToBottom()
  } catch {
    // 请求层已提示
  } finally {
    loadingHistory.value = false
  }
  // 进入会话（订阅 + 拉最新历史），不会自动认领
  client?.post({ type: 'JOIN', sessionNo: item.sessionNo })
}

async function loadMore() {
  if (!activeSessionNo.value || !messages.value.length || loadingMore.value) {
    return
  }
  if (!hasMore.value) {
    ElMessage.info('已经是最早的消息了')
    return
  }
  loadingMore.value = true
  try {
    const beforeId = messages.value[0].msgId
    const older = await listSessionMessages(activeSessionNo.value, { beforeId, limit: HISTORY_PAGE_SIZE })
    if (!older.length) {
      hasMore.value = false
      ElMessage.info('已经是最早的消息了')
      return
    }
    messages.value = dedupe([...older, ...messages.value])
    hasMore.value = older.length >= HISTORY_PAGE_SIZE
  } catch {
    // 请求层已经提示过失败原因
  } finally {
    loadingMore.value = false
  }
}

/** 本地已拿到的最大消息序号 */
function maxSeq(items: SessionMessageItem[]) {
  return items.reduce((max, item) => Math.max(max, Number(item.seq ?? 0)), 0)
}

/**
 * 断线重连补偿：本地最大序号落后于服务端时，只增量拉差值那段。
 */
async function syncMissedMessages(lastSeq?: number | null) {
  const sessionNo = activeSessionNo.value
  const local = maxSeq(messages.value)
  const target = Number(lastSeq ?? 0)
  if (!sessionNo || target <= local) {
    return
  }
  try {
    const missed = await listSessionMessages(sessionNo, { afterSeq: local, limit: 100 })
    if (missed.length) {
      messages.value = dedupe([...messages.value, ...missed])
      scrollToBottom()
    }
  } catch {
    // 拉不到就等下一次推送或下一次进入会话时再补
  }
}

/** 推送的序号不连续说明中间漏了，立刻补一次 */
async function fillGapIfNeeded(message?: SessionMessageItem) {
  if (!message?.seq) {
    return
  }
  const local = maxSeq(messages.value.filter((item) => item.msgId !== message.msgId))
  if (Number(message.seq) > local + 1) {
    await syncMissedMessages(message.seq)
  }
}

async function send() {
  const content = draft.value.trim()
  if (!content || !activeSessionNo.value || !client) {
    return
  }
  const isNote = noteMode.value
  const sessionNo = activeSessionNo.value
  const localId = `local-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  // 乐观气泡：先显示"发送中"，ACK 回来再换成服务端消息（序号/时间以服务端为准）
  messages.value.push({
    msgId: localId,
    msgNo: localId,
    senderType: 2,
    senderId: Number(userStore.userId),
    msgType: 1,
    content,
    visibleTo: isNote ? 2 : 1,
    sendTime: new Date().toISOString(),
    pending: true,
  })
  scrollToBottom()
  draft.value = ''
  sending.value = true
  try {
    const ack = await client.send({
      type: isNote ? 'NOTE' : 'SEND',
      sessionNo,
      msgType: 1,
      content,
    })
    const saved = ack.data as SessionMessageItem | undefined
    messages.value = messages.value.filter((item) => item.msgId !== localId)
    if (saved) {
      appendMessage(saved)
    }
  } catch (e) {
    messages.value = messages.value.map((item) =>
      item.msgId === localId ? { ...item, pending: false, failed: true } : item,
    )
    ElMessage.error(e instanceof Error ? e.message : '发送失败，请重试')
  } finally {
    sending.value = false
  }
}

function claim() {
  if (!activeSessionNo.value) {
    return
  }
  client?.post({ type: 'CLAIM', sessionNo: activeSessionNo.value })
}

function release() {
  if (!activeSessionNo.value) {
    return
  }
  client?.post({ type: 'RELEASE', sessionNo: activeSessionNo.value })
}

function openTransfer() {
  if (!activeSessionNo.value) {
    return
  }
  transferForm.value = { remark: '' }
  transferVisible.value = true
}

function transfer() {
  if (!activeSessionNo.value) {
    return
  }
  if (!transferForm.value.toAgentId) {
    ElMessage.warning('请选择要转接的客服')
    return
  }
  client?.post({
    type: 'TRANSFER',
    sessionNo: activeSessionNo.value,
    toAgentId: transferForm.value.toAgentId,
    remark: transferForm.value.remark || undefined,
  })
  transferVisible.value = false
}

function openClose() {
  closeRemark.value = ''
  closeVisible.value = true
}

function closeSession() {
  if (!activeSessionNo.value) {
    return
  }
  client?.post({
    type: 'CLOSE_SESSION',
    sessionNo: activeSessionNo.value,
    remark: closeRemark.value || undefined,
  })
  closeVisible.value = false
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
    return message.visibleTo === 2 ? 'is-note' : 'is-agent'
  }
  if (message.senderType === 1) {
    return 'is-customer'
  }
  return 'is-system'
}

function senderText(message: SessionMessageItem) {
  if (message.senderType === 2) {
    return message.senderId ? agentName(message.senderId) : '客服'
  }
  if (message.senderType === 1) {
    return '客户'
  }
  if (message.senderType === 3) {
    return '机器人'
  }
  return '系统'
}

function agentName(agentId?: number | string | null) {
  if (agentId === null || agentId === undefined) {
    return ''
  }
  const member = members.value.find((item) => Number(item.userId) === Number(agentId))
  return member?.name || `客服#${agentId}`
}

function agentLabel(agentId?: number | null) {
  if (!agentId) {
    return '待接待'
  }
  return Number(agentId) === Number(userStore.userId) ? `我（${agentName(agentId)}）` : agentName(agentId)
}

function preview(item: SessionItem) {
  if (!item.lastContent) {
    return '暂无消息'
  }
  const text = item.lastContent.replace(/\s+/g, ' ').trim()
  if (item.lastSenderType === 2) {
    return `${isMySession(item) ? '我' : '客服'}：${text}`
  }
  if (item.lastSenderType === 3) {
    return `机器人：${text}`
  }
  return text
}

/** 这张会话是不是我在接待 */
function isMySession(item: SessionItem) {
  return !!item.agentId && Number(item.agentId) === Number(userStore.userId)
}

/** 还没人认领、且没结束：属于「待接待」 */
function isWaitingSession(item: SessionItem) {
  return !item.agentId && item.status !== 4
}

function statusLabel(item: SessionItem) {
  if (item.status === 4) {
    return '已结束'
  }
  if (!isVisitorOnline(item)) {
    return '客户已离线'
  }
  if (!item.agentId) {
    return '待接待'
  }
  return isMySession(item) ? '我接待中' : '同事接待中'
}

function statusClass(item: SessionItem) {
  if (item.status === 4) {
    return 'st-closed'
  }
  if (!isVisitorOnline(item)) {
    return 'st-offline'
  }
  if (!item.agentId) {
    return 'st-wait'
  }
  return isMySession(item) ? 'st-mine' : 'st-other'
}

/** 会员等级：金卡及以上给个醒目标记 */
function levelTag(level?: number | null) {
  if (!level || level < 3) {
    return ''
  }
  return level === 5 ? '企业' : level === 4 ? '铂金' : '金卡'
}

function channelText(item: SessionItem) {
  return item.source || '在线客服'
}

/** 不同渠道用不同图标，一眼能看出客户从哪来 */
function channelIcon(item: SessionItem) {
  const source = item.source || ''
  if (source.includes('小程序')) {
    return Iphone
  }
  if (source.includes('微信')) {
    return ChatDotRound
  }
  if (source.includes('官网') || source.includes('网站')) {
    return Monitor
  }
  return Service
}

/** 头像里的一个字：中文取姓，英文取首字母 */
function avatarText(item: SessionItem) {
  const name = (item.customerName || '').trim()
  return name ? name.slice(0, 1).toUpperCase() : '访'
}

/** 头像底色按会话号散列，同一个人每次进来颜色都一样 */
function avatarClass(item: SessionItem) {
  let hash = 0
  for (const char of item.sessionNo) {
    hash = (hash * 31 + char.charCodeAt(0)) % 997
  }
  return `av-${(hash % 6) + 1}`
}

function avatarStateClass(item: SessionItem) {
  if (item.status === 4) {
    return 'is-closed'
  }
  if (!isVisitorOnline(item)) {
    return 'is-offline'
  }
  return isWaitingSession(item) ? 'is-waiting' : 'is-serving'
}

function metaText(item: SessionItem) {
  if (item.status === 4) {
    return `共 ${item.msgCount ?? 0} 条`
  }
  if (!isVisitorOnline(item)) {
    const offline = offlineDurationText(item.sessionNo)
    // 知道离线多久就显示时长；只从快照知道离线（可能刚打开页面）就退回消息条数，别和状态标签重复
    return offline ? `已离线 ${offline}` : `${item.msgCount ?? 0} 条消息`
  }
  if (isWaitingSession(item)) {
    return `等待 ${waitingText(item.startTime)}`
  }
  return `${item.msgCount ?? 0} 条消息`
}

/**
 * 访客在线状态：以服务端快照 + 上下线事件为准。
 * 快照到达之前先按"在线"显示，避免刚打开页面满屏"客户已离线"；
 * 快照到达后没标记过的会话就是离线。
 */
function isVisitorOnline(item: SessionItem) {
  const known = visitorPresence.value[item.sessionNo]
  if (known !== undefined) {
    return known
  }
  return !presenceSynced.value
}

function offlineDurationText(sessionNo: string) {
  const since = offlineSince.value[sessionNo]
  if (!since) {
    return ''
  }
  const minutes = Math.max(0, Math.floor((nowTick.value - since) / 60000))
  return minutes < 1 ? '刚刚' : `${minutes} 分钟`
}

function setVisitorPresence(sessionNo: string, online: boolean) {
  visitorPresence.value = { ...visitorPresence.value, [sessionNo]: online }
  const next = { ...offlineSince.value }
  if (online) {
    delete next[sessionNo]
  } else {
    next[sessionNo] = Date.now()
  }
  offlineSince.value = next
}

/**
 * 用服务端快照重建在线状态：快照里有的算在线，其余（列表里看得见的）算离线。
 */
function applyPresenceSnapshot(onlineSessions: string[]) {
  const online = new Set(onlineSessions)
  const next: Record<string, boolean> = {}
  for (const item of sessions.value) {
    next[item.sessionNo] = online.has(item.sessionNo)
  }
  visitorPresence.value = next
  offlineSince.value = {}
  presenceSynced.value = true
}

function waitingText(startTime?: string | null) {
  if (!startTime) {
    return '中'
  }
  const started = new Date(startTime.replace(' ', 'T')).getTime()
  if (Number.isNaN(started)) {
    return '中'
  }
  const minutes = Math.max(0, Math.floor((nowTick.value - started) / 60000))
  if (minutes < 1) {
    return '不到 1 分钟'
  }
  if (minutes < 60) {
    return `${minutes} 分钟`
  }
  return `${Math.floor(minutes / 60)} 小时${minutes % 60} 分钟`
}

function unreadOf(item: SessionItem) {
  return unreadMap.value[item.sessionNo] ?? 0
}

function unreadText(item: SessionItem) {
  const count = unreadOf(item)
  return count > 99 ? '99+' : String(count)
}

function markUnread(sessionNo?: string) {
  if (!sessionNo || sessionNo === activeSessionNo.value) {
    return
  }
  // 只给列表里看得见的会话计数，避免把无关会话的未读带出来
  if (!sessions.value.some((item) => item.sessionNo === sessionNo)) {
    return
  }
  unreadMap.value = { ...unreadMap.value, [sessionNo]: (unreadMap.value[sessionNo] ?? 0) + 1 }
}

function clearUnread(sessionNo?: string) {
  if (!sessionNo || !unreadMap.value[sessionNo]) {
    return
  }
  const next = { ...unreadMap.value }
  delete next[sessionNo]
  unreadMap.value = next
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
  color: #64748b;
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
  width: 340px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

/* 列表头：统计与排序口径常驻，滚动时也看得见 */
.list-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid #eef2f7;
  background: linear-gradient(180deg, #fbfdff 0%, #f6f9ff 100%);
}

.lh-title {
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
}

.lh-count {
  min-width: 20px;
  height: 18px;
  padding: 0 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  background: #e8f0ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 600;
}

.lh-sort {
  margin-left: auto;
  font-size: 12px;
  color: #94a3b8;
}

.list-body {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
}

/* 细滚动条：默认的粗条在大屏工作台上很出戏 */
.list-body::-webkit-scrollbar {
  width: 6px;
}

.list-body::-webkit-scrollbar-thumb {
  background: #dbe3ef;
  border-radius: 3px;
}

.list-body::-webkit-scrollbar-thumb:hover {
  background: #c3cee0;
}

.list-body::-webkit-scrollbar-track {
  background: transparent;
}

.list-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 40px 20px;
  color: #b6c0d0;
}

.list-empty p {
  margin: 4px 0 0;
  font-size: 13px;
  font-weight: 500;
  color: #475569;
}

.list-empty span {
  font-size: 12px;
  color: #94a3b8;
}

/* 骨架屏：首屏加载用占位块，比转圈更接近成品形态 */
.list-skeleton {
  padding: 10px;
}

.sk-item {
  display: flex;
  gap: 10px;
  padding: 8px 4px;
}

.sk-avatar {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  flex-shrink: 0;
}

.sk-lines {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 7px;
}

.sk-line {
  height: 10px;
  border-radius: 5px;
}

.sk-avatar,
.sk-line {
  background: linear-gradient(90deg, #eef2f7 25%, #f7fafd 37%, #eef2f7 63%);
  background-size: 400% 100%;
  animation: sk-shimmer 1.4s ease infinite;
}

@keyframes sk-shimmer {
  0% {
    background-position: 100% 50%;
  }

  100% {
    background-position: 0 50%;
  }
}

.list-item {
  position: relative;
  display: flex;
  gap: 10px;
  padding: 10px 10px 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.16s ease, box-shadow 0.16s ease;
}

.list-item + .list-item {
  margin-top: 2px;
}

.list-item:hover {
  background: #f5f9ff;
}

.list-item:focus-visible {
  outline: 2px solid #93c5fd;
  outline-offset: -1px;
}

.list-item.active {
  background: #eff6ff;
  box-shadow: inset 0 0 0 1px #bfdbfe;
}

/* 选中态左侧蓝色标记条 */
.list-item.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: #2563eb;
}

.li-avatar {
  position: relative;
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  color: #fff;
  font-size: 15px;
  font-weight: 600;
}

.av-1 {
  background: linear-gradient(135deg, #60a5fa, #2563eb);
}

.av-2 {
  background: linear-gradient(135deg, #34d399, #059669);
}

.av-3 {
  background: linear-gradient(135deg, #a78bfa, #7c3aed);
}

.av-4 {
  background: linear-gradient(135deg, #fbbf24, #d97706);
}

.av-5 {
  background: linear-gradient(135deg, #22d3ee, #0891b2);
}

.av-6 {
  background: linear-gradient(135deg, #fb7185, #e11d48);
}

.li-state {
  position: absolute;
  right: -3px;
  bottom: -3px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 2px solid #fff;
}

.li-state.is-waiting {
  background: #f59e0b;
}

.li-state.is-serving {
  background: #10b981;
}

.li-state.is-closed {
  background: #cbd5e1;
}

.li-state.is-offline {
  background: #94a3b8;
}

.li-main {
  flex: 1;
  min-width: 0;
}

.li-top {
  display: flex;
  align-items: center;
  gap: 6px;
}

.li-name {
  max-width: 132px;
  font-size: 13.5px;
  font-weight: 600;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.li-level {
  flex-shrink: 0;
  height: 16px;
  padding: 0 5px;
  line-height: 16px;
  border-radius: 4px;
  border: 1px solid #fcd34d;
  background: linear-gradient(135deg, #fef3c7, #fde68a);
  color: #b45309;
  font-size: 11px;
  font-weight: 600;
}

.li-time {
  margin-left: auto;
  flex-shrink: 0;
  font-size: 11.5px;
  color: #94a3b8;
}

.li-mid {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 3px 0 6px;
}

.li-preview {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.li-unread {
  flex-shrink: 0;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(239, 68, 68, 0.35);
}

.li-bottom {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
}

.li-chip {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 500;
}

.st-wait {
  background: #fff7ed;
  color: #b45309;
  box-shadow: inset 0 0 0 1px #fed7aa;
}

.st-mine {
  background: #ecfdf5;
  color: #047857;
  box-shadow: inset 0 0 0 1px #a7f3d0;
}

.st-other {
  background: #eff6ff;
  color: #1d4ed8;
  box-shadow: inset 0 0 0 1px #bfdbfe;
}

.st-closed {
  background: #f1f5f9;
  color: #64748b;
  box-shadow: inset 0 0 0 1px #e2e8f0;
}

/* 客户已经离开：灰得明显，但不至于像报错 */
.st-offline {
  background: #f8fafc;
  color: #475569;
  box-shadow: inset 0 0 0 1px #cbd5e1;
}

.li-channel,
.li-extra {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.li-channel {
  max-width: 92px;
}

.li-extra {
  margin-left: auto;
  flex-shrink: 0;
  color: #7c8aa5;
}

.list-item.waiting .li-extra {
  color: #b45309;
}

.chat-offline {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border-bottom: 1px solid #fed7aa;
  background: #fff7ed;
  color: #b45309;
  font-size: 12px;
}

.chat-offline span {
  flex: 1;
}

.sub-online {
  color: #10b981;
}

.sub-offline {
  color: #f59e0b;
}

.sub-closed {
  color: #94a3b8;
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
  padding: 12px 18px;
  border-bottom: 1px solid #eef2f7;
  gap: 12px;
  flex-wrap: wrap;
}

.ch-name {
  display: flex;
  align-items: center;
  gap: 8px;
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
  flex-wrap: wrap;
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
  /* 和访客窗口保持一致：访客说的话在右边 */
  justify-content: flex-end;
}

.msg-row.is-agent {
  /* 人工客服的回复在左边 */
  justify-content: flex-start;
}

.msg-row.is-note {
  justify-content: flex-start;
}

.msg-row.is-system {
  /* 系统消息跟着会话流从左边开始，居中会打断阅读节奏 */
  justify-content: flex-start;
}

.msg-bubble {
  max-width: 62%;
  padding: 9px 12px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid #e8eef6;
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.03);
}

/* 蓝色气泡留给访客（访客窗口里"自己说的"就是蓝色），人工客服走默认白底 */
.is-customer .msg-bubble {
  background: #2563eb;
  border-color: #2563eb;
}

.is-note .msg-bubble {
  background: #fff7e6;
  border: 1px dashed #f0b429;
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

.is-customer .msg-meta {
  color: #c7dbff;
}

/* 时间统一放在消息下方：先看内容，时间只是辅助信息 */
.msg-time {
  margin-top: 4px;
  font-size: 11px;
  color: #a3aec2;
  text-align: left;
}

.is-customer .msg-time {
  color: #c7dbff;
  text-align: right;
}

.is-system .msg-time {
  color: #94a3b8;
  text-align: left;
}

/* 发送中/发送失败的气泡（消息仍在发件箱，网络恢复自动补发） */
.msg-bubble.is-pending {
  opacity: 0.72;
}

.msg-bubble.is-failed {
  border-color: #fca5a5;
}

.msg-bubble.is-failed .msg-time {
  color: #f87171;
}

.note-tag {
  margin-left: 6px;
  color: #d97706;
  font-weight: 600;
}

.msg-content {
  font-size: 13px;
  color: #1f2937;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}

.is-customer .msg-content {
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
  gap: 12px;
}

.ci-left {
  display: flex;
  align-items: center;
  gap: 10px;
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

.dialog-tip {
  font-size: 13px;
  color: #64748b;
  margin-bottom: 14px;
}
</style>
