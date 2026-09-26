<template>
  <div class="ws-page">
    <!-- 顶部：连接状态与筛选 -->
    <div class="ws-toolbar">
      <div class="ws-title">
        <span class="dot" :class="stateClass" />
        <span class="name">在线客服工作台</span>
        <el-tag size="small" :type="stateTagType" effect="light">{{ stateText }}</el-tag>
        <span class="tip">我的接待 {{ myCount }} 单 / 待接待 {{ queueCount }} 单</span>
        <span v-if="loadFull" class="tip tip-full">
          已接满 {{ myActiveCount }}/{{ myMaxConcurrency }}，不会再有新会话自动派给你
        </span>
      </div>
      <div class="ws-filters">
        <el-select v-model="myStatus" style="width: 132px" @change="changeMyStatus">
          <el-option :value="1" label="在线 · 可接单" />
          <el-option :value="2" label="忙碌 · 不接单" />
          <el-option :value="3" label="小休" />
        </el-select>
        <!-- 接待量：路由就是按"已接单数 < 上限"筛人的，接满了同样派不到 -->
        <span class="load-tip" :class="{ 'is-full': loadFull }">
          已接 {{ myActiveCount }}/{{ myMaxConcurrency }} 单
        </span>
        <!-- 能不能被自动派单，取决于三个条件：状态在线、长连接在线、还有余量 -->
        <el-tag
          v-if="!routable"
          size="small"
          type="warning"
          effect="light"
          :title="routableHint"
        >
          暂不会被派单
        </el-tag>
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
            :class="{
              active: item.sessionNo === activeSessionNo,
              waiting: !item.agentId && item.status !== 4,
              'just-assigned': item.sessionNo === justAssigned
            }"
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
                <span v-if="item.intent" class="li-brain">{{ item.intent }}</span>
                <span
                  v-if="item.emotion && item.emotion !== '中性'"
                  class="li-brain"
                  :class="`li-brain-${emotionTagType(item.emotion)}`"
                >{{ item.emotion }}</span>
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
              <div v-if="activeSession.intent || activeSession.emotion" class="ch-brain">
                <span class="ch-brain-label">智能客服识别</span>
                <el-tag v-if="activeSession.intent" size="small" effect="plain">
                  意图：{{ activeSession.intent }}
                </el-tag>
                <el-tag v-if="activeSession.emotion" size="small" :type="emotionTagType(activeSession.emotion)" effect="light">
                  情绪：{{ activeSession.emotion }}
                </el-tag>
                <span v-if="botTransferReason" class="ch-brain-reason">
                  转人工原因：{{ botTransferReason }}
                </span>
              </div>
              <!-- 人工接待期间机器人不会自动回客户，这行说明省得以为是"机器人坏了" -->
              <div v-if="!isClosed && activeSession.agentId" class="ch-bot-note">
                机器人已转辅助：人工接待期间不会自动回复客户，需要查资料点左下角「知识助手」
              </div>
            </div>
            <div class="ch-right">
              <el-button v-if="!isClosed && !activeSession.agentId" size="small" type="primary" @click="claim">
                接入会话
              </el-button>
              <el-button v-if="!isClosed && isMine" size="small" @click="release">退回队列</el-button>
              <el-button v-if="!isClosed" size="small" @click="openTransfer">转接</el-button>
              <el-button
                size="small"
                :disabled="!messages.length"
                title="把当前会话的记录复制成文本（可直接贴进工单或群里）"
                @click="copyConversation"
              >
                复制会话
              </el-button>
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
          <div v-if="pendingQaAlerts" class="qa-banner">
            <el-icon :size="16"><WarningFilled /></el-icon>
            <div class="qa-banner-main">
              <div class="qa-banner-title">
                实时质检预警 {{ pendingQaAlerts }} 条待处理
                <span class="qa-banner-sub">边聊边检命中规则，请按建议调整话术</span>
              </div>
              <div
                v-for="alert in pendingQaAlertList.slice(0, 3)"
                :key="alert.id"
                class="qa-banner-row"
              >
                <span class="qa-sev" :class="`qa-sev-${alert.severity}`">{{ alert.severityText }}</span>
                <span class="qa-rule">{{ alert.ruleName }}</span>
                <span class="qa-snippet">{{ alert.advice || alert.snippet }}</span>
                <el-button link type="primary" size="small" @click="onHandleAlert(alert)">标记已处理</el-button>
              </div>
              <div v-if="pendingQaAlerts > 3" class="qa-banner-more">
                还有 {{ pendingQaAlerts - 3 }} 条，去「质检中心 → 实时预警」查看
              </div>
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
          <div ref="scrollRef" class="chat-body" @scroll.passive="onScroll">
            <div v-if="loadingHistory" class="chat-tip">正在加载聊天记录…</div>
            <template v-for="(msg, index) in messages" :key="msg.msgId">
              <!-- 时间分割：和上一条隔得久（或第一条）才显示一次，不再每条都挂时间 -->
              <div v-if="showTimeDivider(index)" class="msg-divider">{{ dividerText(msg.sendTime) }}</div>
              <!-- 系统提示：居中灰胶囊，没有头像也没有气泡 -->
              <div v-if="msg.senderType === 4" class="msg-system">{{ msg.content }}</div>
              <div
                v-else
                class="msg-row"
                :class="[msgRowClass(msg), { 'qa-risky': riskySeqs.has(Number(msg.seq ?? -1)) }]"
              >
                <!-- 头像：客户用姓名首字，机器人用品牌标，坐席用姓名首字（和左侧列表同一套） -->
                <div
                  class="msg-avatar"
                  :class="msgAvatarClass(msg)"
                  :title="senderText(msg)"
                >
                  <img v-if="msg.senderType === 3" class="msg-avatar-bot" src="/yunti-mark.svg" alt="机器人" />
                  <template v-else>{{ msgAvatarText(msg) }}</template>
                </div>
                <div class="msg-main">
                  <div class="msg-meta">
                    {{ senderText(msg) }}
                    <span v-if="msg.visibleTo === 2" class="note-tag">内部备注</span>
                    <span v-if="riskySeqs.has(Number(msg.seq ?? -1))" class="qa-tag">质检命中</span>
                  </div>
                  <div
                    class="msg-bubble"
                    :class="{ 'is-pending': msg.pending, 'is-failed': msg.failed, 'is-image': !!imageOf(msg) }"
                  >
                    <!-- 图片消息：缩略图 + AI 判读（坐席一眼知道客户发的什么图、图里写了什么） -->
                    <template v-if="imagesOf(msg).length">
                      <!-- 客户随图说的那句话：坐席看图和看文字的顺序，和客户发的时候一致 -->
                      <div v-if="imageOf(msg)?.text" class="msg-image-text">{{ imageOf(msg)?.text }}</div>
                      <div class="msg-images" :class="{ 'is-multi': imagesOf(msg).length > 1 }">
                        <button
                          v-for="(image, index) in imagesOf(msg)"
                          :key="image.fileId || image.url || index"
                          type="button"
                          class="msg-image"
                          :title="image.name || '查看大图'"
                          @click="openImage(image)"
                        >
                          <img :src="image.url" :alt="image.name || '图片'" loading="lazy" />
                        </button>
                      </div>
                      <div v-if="imageOf(msg)?.aiSummary" class="msg-ai">
                        <div class="msg-ai-head">
                          <el-icon :size="12"><MagicStick /></el-icon>
                          AI 判读
                          <span v-if="imageOf(msg)?.aiOrderNo" class="msg-ai-tag">
                            订单号 {{ imageOf(msg)?.aiOrderNo }}
                          </span>
                          <span v-if="imageOf(msg)?.aiAmount" class="msg-ai-tag">
                            金额 {{ imageOf(msg)?.aiAmount }}
                          </span>
                          <!-- 模型判了"这单该找人工"：给坐席一个显眼的标，别靠翻 OCR 原文才发现 -->
                          <span v-if="imageOf(msg)?.aiNeedHuman" class="msg-ai-tag is-warn">
                            建议人工介入
                          </span>
                        </div>
                        <div class="msg-ai-text">{{ imageOf(msg)?.aiSummary }}</div>
                        <div v-if="imageOf(msg)?.aiErrorText" class="msg-ai-error">
                          报错原文：{{ imageOf(msg)?.aiErrorText }}
                        </div>
                        <el-collapse v-if="imageOf(msg)?.aiOcrText" class="msg-ai-ocr">
                          <el-collapse-item title="查看图中文字（OCR）" :name="msg.msgId">
                            <pre class="msg-ai-pre">{{ imageOf(msg)?.aiOcrText }}</pre>
                          </el-collapse-item>
                        </el-collapse>
                      </div>
                      <div v-else-if="imageOf(msg)?.aiAvailable === false" class="msg-ai muted">
                        图片识别暂不可用（没配视觉模型密钥）
                      </div>
                      <div v-else class="msg-ai muted">正在识别图片…</div>
                    </template>
                    <!-- 卡片消息：正文照常显示；"转人工客服"按钮是给客户点的，坐席这边只做提示 -->
                    <template v-else-if="cardOf(msg)">
                      <div class="msg-content">{{ cardOf(msg)?.text }}</div>
                    </template>
                    <div v-else class="msg-content">{{ msg.content }}</div>
                    <div v-if="cardOf(msg)?.actions?.length" class="msg-card-note">
                      已向客户提供「{{ cardOf(msg)?.actions?.[0]?.label }}」入口
                    </div>
                    <div class="msg-time">
                      <template v-if="msg.pending">发送中…</template>
                      <template v-else-if="msg.failed">发送失败，网络恢复后自动补发</template>
                      <template v-else>{{ fullTime(msg.sendTime) }}</template>
                    </div>
                  </div>
                </div>
                <!-- 复制按钮放在整行最外侧：悬停才出现，不遮正文，正文照样可以自由选中 -->
                <div class="msg-actions">
                  <el-tooltip content="复制这条消息" placement="top" :show-after="200">
                    <button class="msg-copy" type="button" @click="copyMessage(msg)">
                      <el-icon :size="13"><CopyDocument /></el-icon>
                    </button>
                  </el-tooltip>
                </div>
              </div>
            </template>
            <div v-if="!messages.length" class="chat-tip">还没有消息，输入内容开始接待</div>
            <!-- 自己翻上去看历史时，新消息不硬拽，只提示一下 -->
            <button
              v-if="hasNewBelow"
              class="scroll-new"
              type="button"
              @click="jumpToBottom"
            >
              有新消息 ↓
            </button>
            <!-- 机器人正在想：坐席能看到"客户那条已经被机器人接手了"，不用自己去抢 -->
            <div v-if="botTyping" class="msg-row is-agent">
              <div class="msg-avatar msg-avatar-bot-wrap">
                <img class="msg-avatar-bot" src="/yunti-mark.svg" alt="机器人" />
              </div>
              <div class="msg-main">
                <div class="msg-meta">{{ senderBotLabel }}</div>
                <div class="msg-bubble is-typing">
                  <div class="typing-dots"><i /><i /><i /></div>
                </div>
              </div>
            </div>
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
                <el-button
                  size="small"
                  type="primary"
                  plain
                  :disabled="isClosed"
                  title="不会答的问题，问一下知识库：AI 查完资料给你答案和出处"
                  @click="openKnowledgeAssistant"
                >
                  <el-icon class="btn-icon"><MagicStick /></el-icon>
                  知识助手
                </el-button>
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
    <!-- 知识助手：坐席接待时遇到不会答的问题，问知识库，答案可以直接填进回复 -->
    <KnowledgeAssistant
      v-model:visible="kbAskVisible"
      :default-question="kbAskDefault"
      insertable
      @insert="onInsertKnowledge"
    />

    <!-- 图片预览：客户发的截图在**本页**弹层里看大图，右上角有关闭（点遮罩、按 Esc 也能关） -->
    <el-image-viewer
      v-if="previewOpen"
      :url-list="previewUrls"
      :initial-index="previewIndex"
      :hide-on-click-modal="true"
      teleported
      @close="previewOpen = false"
    />
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  ChatDotRound,
  Clock,
  CopyDocument,
  Iphone,
  MagicStick,
  Monitor,
  Promotion,
  Refresh,
  Search,
  Service,
  Warning,
  WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElNotification } from 'element-plus'
import { listChannels, type ChannelResult } from '../../api/customer/channel'
import { fetchPresence } from '../../api/realtime'
import { fetchAgentStatuses, updateAgentStatus, type AgentStatusItem } from '../../api/customer/agentStatus'
import { fetchSessionQaAlerts, handleQaAlert, type QaAlertItem } from '../../api/customer/qa'
import KnowledgeAssistant from '../../components/KnowledgeAssistant.vue'
import { copyText } from '../../utils/clipboard'
import { useChatScroll } from '../../utils/chatScroll'
import { openVisitorTestTab } from '../../utils/visitor'
import {
  fetchSessionWorkload,
  getSessionDetail,
  imagesOf,
  messageTextOf,
  parseImageContent,
  listSessionMessages,
  listSessions,
  type ChatImageItem,
  type SessionItem,
  type SessionMessageItem,
} from '../../api/customer/session'
import { getBotSetting } from '../../api/ai/bot'
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
const botTransferReason = computed(() => activeSession.value?.botTransferReason || '')
const messages = ref<SessionMessageItem[]>([])
/** 机器人正在输入（当前会话）：让坐席知道客户那条已经被机器人接住了 */
const botTyping = ref(false)
/** 机器人显示名：坐席侧标成"小云（机器人）"，比干巴巴的"机器人"更清楚是谁在答 */
const botName = ref('')
const loadingHistory = ref(false)
/** 还有没有更早的消息：没有就把「更早消息」置灰，避免点了没反应 */
const hasMore = ref(false)
const loadingMore = ref(false)
/** 刚被智能路由分过来的会话号：列表里闪一下，让坐席知道"有新单进来了" */
const justAssigned = ref('')
const draft = ref('')
const sending = ref(false)
const noteMode = ref(false)
const assistAgentIds = ref<number[]>([])
const scrollRef = ref<HTMLElement>()

const members = ref<ColleagueOption[]>([])
const agentOnline = ref<Record<string, { online: boolean; sessionCount: number }>>({})
/** 用来做"模拟访客"的渠道：取本企业第一个生效且有密钥的渠道 */
const visitorTestChannel = ref<ChannelResult | null>(null)
/** 我的坐席状态：1-在线、2-忙碌、3-小休（智能路由据此决定是否派单） */
const myStatus = ref(1)
/**
 * 我的长连接是否在线（服务端记录的 is_connected）。
 *
 * <p>能不能被自动派单看两个条件：状态 =「在线·可接单」**且** 长连接在线。
 * 只满足一个，路由就找不到你，而界面上看不出来——那正是"我明明登录着，怎么没派给我"的来源。</p>
 */
const myConnected = ref(true)
/** 我的接待上限与当前接待量（和路由筛人用的是同一份口径） */
const myMaxConcurrency = ref(5)
const myActiveCount = ref(0)
/** 同事状态：转接选人时显示"在线 / 忙碌 / 小休" */
const agentStatuses = ref<Record<string, AgentStatusItem>>({})
/** 当前会话的实时质检告警（边聊边检命中后由长连接推过来） */
const qaAlerts = ref<QaAlertItem[]>([])
/** 知识助手：坐席问知识库（AI 查资料后回答，可把答案填进回复） */
const kbAskVisible = ref(false)
const kbAskDefault = ref('')
/** 还等着处理的告警数：>0 时聊天区顶部挂警示条 */
const pendingQaAlerts = computed(() => qaAlerts.value.filter((item) => item.status === 1).length)
/** 待处理的告警明细：警示条只展示前几条，其余引导去质检中心 */
const pendingQaAlertList = computed(() => qaAlerts.value.filter((item) => item.status === 1))
/** 命中告警的消息序号：气泡上标红，坐席一眼看到是哪句话 */
const riskySeqs = computed(() => new Set(qaAlerts.value.map((item) => Number(item.messageSeq ?? -1))))

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
const transferForm = ref<{ toAgentId?: string; remark: string }>({ remark: '' })
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
  () => !!activeSession.value?.agentId && String(activeSession.value.agentId) === String(userStore.userId),
)
const myCount = computed(
  () => sessions.value.filter((item) => String(item.agentId) === String(userStore.userId)).length,
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
    .filter((id) => String(id) !== String(activeSession.value?.agentId))
    .map((id) => agentName(id)),
)
const transferCandidates = computed(() =>
  members.value
    .filter((member) => String(member.userId) !== String(userStore.userId))
    .map((member) => {
      const status = agentOnline.value[String(member.userId)]
      const manual = agentStatuses.value[String(member.userId)]
      const state = status?.online
        ? `${manual?.statusText || '在线'} · 接待 ${status.sessionCount} 单`
        : '离线'
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
  await Promise.all([loadSessions(), loadMembers(), loadVisitorTestChannel(), loadAgentStatuses()])
  // 机器人名字：坐席侧把它标在机器人消息上（"小云（机器人）"），比"机器人"更明确是谁在答
  void loadBotName()
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

/** 取租户配置的机器人名字（拿不到就不标，退回"机器人"） */
async function loadBotName() {
  try {
    const setting = await getBotSetting()
    botName.value = setting.botName || ''
  } catch {
    botName.value = ''
  }
}

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
 * 拉一次坐席状态：我的状态用于右上角切换，同事状态用于转接选人。
 */
async function loadAgentStatuses() {
  try {
    const view = await fetchAgentStatuses()
    myStatus.value = view.mine.status
    myConnected.value = view.mine.connected !== false
    myMaxConcurrency.value = view.mine.maxConcurrency || 5
    myActiveCount.value = view.mine.activeCount || 0
    agentStatuses.value = Object.fromEntries(view.agents.map((item) => [item.agentId, item]))
  } catch {
    // 拿不到不影响接待
  }
}

/** 接待量是否已经到上限（到了就不会再被派新会话） */
const loadFull = computed(() => myActiveCount.value >= myMaxConcurrency.value)

/** 我当前能不能被自动派单：在线 + 长连接在线 + 还有余量 */
const routable = computed(() => myStatus.value === 1 && myConnected.value && !loadFull.value)

/** 不能派单时给一句能照做的说明 */
const routableHint = computed(() => {
  if (myStatus.value !== 1) {
    return '当前状态不是「在线 · 可接单」，新会话不会自动派给你'
  }
  if (!myConnected.value) {
    return '长连接还没就绪（服务端记录为离线），刷新页面或稍等重连完成即可恢复派单'
  }
  return `已接待 ${myActiveCount.value}/${myMaxConcurrency.value} 单，达到上限：结束掉已处理完的会话，`
    + '或把「最多同时接待数」调大一些'
})

/** 拉一次当前会话的实时质检告警（切会话、被派单时都补一次） */
async function loadSessionQaAlerts(sessionNo: string) {
  if (!sessionNo) {
    return
  }
  try {
    const rows = await fetchSessionQaAlerts(sessionNo)
    if (activeSessionNo.value === sessionNo) {
      qaAlerts.value = rows
    }
  } catch {
    // 拿不到预警不影响接待
  }
}

/** 长连接推来一条实时质检预警：先给反馈，再把它挂到当前会话上 */
function onQaAlert(payload: { alert?: QaAlertItem }) {
  const alert = payload?.alert
  if (!alert) {
    return
  }
  if (alert.sessionNo && alert.sessionNo !== activeSessionNo.value) {
    // 不是当前会话：只提示，不往当前会话里塞
    ElNotification({
      title: `实时质检预警 · ${alert.severityText}`,
      message: `${alert.ruleName}：${alert.snippet || ''}（会话 ${alert.sessionNo}）`,
      type: alert.severity >= 3 ? 'error' : 'warning',
      duration: 10000,
    })
    void loadSessions()
    return
  }
  qaAlerts.value = [alert, ...qaAlerts.value.filter((item) => item.id !== alert.id)]
  ElNotification({
    title: `实时质检预警 · ${alert.severityText}`,
    message: `${alert.ruleName}：${alert.snippet || ''}`,
    type: alert.severity >= 3 ? 'error' : 'warning',
    duration: 10000,
  })
}

/** 坐席当场处置完，把告警标记成已处理 */
async function onHandleAlert(alert: QaAlertItem) {
  try {
    const updated = await handleQaAlert(alert.id, '坐席已当场处理')
    qaAlerts.value = qaAlerts.value.map((item) => (item.id === updated.id ? updated : item))
    ElMessage.success('已标记处理')
  } catch {
    // 请求层已提示
  }
}

/** 切换状态：路由会立刻按新状态重新分配排队会话 */
async function changeMyStatus(status: number) {
  try {
    const mine = await updateAgentStatus({ status })
    myStatus.value = mine.status
    ElMessage.success(`状态已切换为「${mine.statusText}」`)
    await loadAgentStatuses()
  } catch {
    await loadAgentStatuses()
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
    // 重连时取最新令牌：标签页可能开了一整天，登录令牌早就换了一轮
    tokenProvider: getToken,
    onAuthFailed: () => void verifyLoginOnRealtimeFailure(),
    onMessage: handleMessage,
    onStateChange: (state) => {
      connectionState.value = state
      if (state === 'open' && activeSessionNo.value) {
        client?.post({ type: 'JOIN', sessionNo: activeSessionNo.value })
      }
      // 重连期间可能漏掉了推送，连上后补一次列表同步
      if (state === 'open') {
        scheduleListReload()
        // 长连接刚恢复：服务端那边也会把 is_connected 置回 true，这里顺手对一次账
        void loadAgentStatuses()
      }
    },
  })
  client.connect()
}

/**
 * 长连接反复连不上时，确认一次登录态。
 *
 * <p>握手被拒（令牌过期）在浏览器侧只表现为"连不上"：和"实时服务没启动""网络断"
 * 长得一模一样。这里借一次真实的 HTTP 请求去问服务端——令牌真过期，全局 401 处理会
 * 弹专业提示并把用户送回登录页；服务端一切正常就什么都不做，继续重连即可。</p>
 */
async function verifyLoginOnRealtimeFailure() {
  // 只借这次请求触发全局登录态处理，不在这里重复提示（提示由统一的 401 处理负责）
  await fetchPresence()
}

/** "正在输入"的安全绳：推送丢了也要自动熄灭，不能一直转 */
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

async function handleMessage(message: RealtimeMessage) {
  switch (message.type) {
    case 'ASSIGNED': {
      // 智能路由把会话分给了我：切到"我的会话"、刷新列表、高亮这条会话
      const payload = message.data ?? {}
      const sessionNo = (payload.sessionNo as string | undefined) || message.sessionNo
      if (!sessionNo) {
        break
      }
      // 先给反馈，不等网络：刷新列表哪怕慢/失败，坐席也能看到"被派单了"
      highlightAssigned(sessionNo)
      if (sessionNo === activeSessionNo.value) {
        void mergeLatestHistory(sessionNo)
      }
      ElNotification({
        title: '已自动接入客户',
        message: `智能路由把会话 ${sessionNo} 分配给了你，正在「我的会话」里等你接待`,
        type: 'success',
        duration: 8000,
        onClick: () => {
          const item = sessions.value.find((session) => session.sessionNo === sessionNo)
          if (item) {
            void openSession(item)
          }
        },
      })
      if (scope.value !== 'mine') {
        scope.value = 'mine'
      }
      void loadSessions()
      break
    }
    case 'QA_ALERT': {
      // 实时质检命中：客户/客服刚发的那句话踩到规则了
      onQaAlert((message.data ?? {}) as { alert?: QaAlertItem })
      break
    }
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
      // 刚进会话：必须看到最新几条，强制到底
      scrollToBottom(true)
      break
    }
    case 'ACK':
      appendMessage(message.data as SessionMessageItem)
      break
    case 'TYPING': {
      const payload = (message.data ?? {}) as { who?: string; typing?: boolean }
      if (message.sessionNo === activeSessionNo.value) {
        botTyping.value = payload.typing !== false && payload.who !== 'AGENT'
        scheduleTypingTimeout()
        // 提示气泡出现/消失都会改高度，跟着滚一下才不会"半截露在外面"
        scrollToBottom()
      }
      break
    }
    case 'MESSAGE': {
      const item = message.data as SessionMessageItem | undefined
      if (message.sessionNo === activeSessionNo.value) {
        botTyping.value = false
      }
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
        const wasMine = !!activeSession.value?.agentId
          && Number(activeSession.value.agentId) === Number(userStore.userId)
        activeSession.value = { ...(activeSession.value ?? {}), ...session } as SessionItem
        const nowMine = !!session.agentId && Number(session.agentId) === Number(userStore.userId)
        if (!wasMine && nowMine) {
          // 刚接手这条会话：把接手之前的对话（机器人接待那一段）补齐。
          // 坐席必须看到前因后果，否则只能从半截开始猜客户在问什么。
          void mergeLatestHistory(session.sessionNo)
        }
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
    // 计数按服务端口径重算（我的接待数 = 路由眼里的接待量）
    void loadWorkload()
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
  qaAlerts.value = []
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
    // 切换会话：从最新一条开始看
    scrollToBottom(true)
  } catch {
    // 请求层已提示
  } finally {
    loadingHistory.value = false
  }
  // 进入会话（订阅 + 拉最新历史），不会自动认领
  client?.post({ type: 'JOIN', sessionNo: item.sessionNo })
  // 之前错过的实时预警也要能看到，所以切会话时补拉一次
  void loadSessionQaAlerts(item.sessionNo)
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

/**
 * 把服务端最近的消息补进当前会话（按消息号去重，按序号排序）。
 *
 * <p>用在"会话刚变成我的"这一刻：坐席接手时，机器人之前跟客户聊的那几轮必须出现在窗口里，
 * 否则坐席等于从半截接手——客户说过什么、机器人承诺过什么，全都要重新问一遍。</p>
 */
async function mergeLatestHistory(sessionNo: string) {
  try {
    const latest = await listSessionMessages(sessionNo, { limit: 50 })
    if (sessionNo !== activeSessionNo.value || !latest.length) {
      return
    }
    const merged = dedupe([...messages.value, ...latest])
      .slice()
      .sort((left, right) => Number(left.seq ?? 0) - Number(right.seq ?? 0))
    messages.value = merged
    if (latest.length >= HISTORY_PAGE_SIZE) {
      hasMore.value = true
    }
  } catch {
    // 拉不到就等下一次（JOINED 里还会带一份历史），不影响正常接待
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
    senderId: String(userStore.userId),
    msgType: 1,
    content,
    visibleTo: isNote ? 2 : 1,
    sendTime: new Date().toISOString(),
    pending: true,
  })
  // 自己发的消息一定要出现在眼前（哪怕之前翻着历史）
  scrollToBottom(true)
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

/**
 * 复制单条消息。
 *
 * <p>只复制正文，不带"客服/客户"前缀和小时刻——坐席要的多半是内容本身；
 * 需要带来源的整段记录用右上角的「复制会话」。</p>
 */
async function copyMessage(msg: SessionMessageItem) {
  // 图片消息的正文是 JSON，复制 JSON 没意义——取"随图说的那句话"
  const ok = await copyText(messageTextOf(msg))
  if (ok) {
    ElMessage.success('已复制这条消息')
  } else {
    ElMessage.warning('复制失败，请手动选中文字后 Ctrl/Cmd + C')
  }
}

/**
 * 复制整段会话记录（带时间与发言人）。
 *
 * <p>真实工作场景里坐席经常要把对话贴进工单、贴到群里问人，
 * 一行行手工选太慢；这里按"时间 发言人：内容"排好再进剪贴板。</p>
 */
async function copyConversation() {
  if (!messages.value.length) {
    return
  }
  const header = `会话 ${activeSessionNo.value}`
    + (activeSession.value?.customerName ? ` · 客户 ${activeSession.value.customerName}` : '')
    + (activeSession.value?.source ? ` · 来源 ${activeSession.value.source}` : '')
  const lines = messages.value.map((msg) => {
    const time = fullTime(msg.sendTime)
    const who = senderText(msg)
    const note = msg.visibleTo === 2 ? '[内部备注] ' : ''
    return `[${time}] ${who}：${note}${messageTextOf(msg)}`
  })
  const ok = await copyText([header, ...lines].join('\n'))
  if (ok) {
    ElMessage.success(`已复制 ${lines.length} 条聊天记录`)
  } else {
    ElMessage.warning('复制失败，请手动选中文字后 Ctrl/Cmd + C')
  }
}

/**
 * 卡片消息（msgType=3）：{"text": "...", "actions": [...]}。
 *
 * <p>按钮是给客户点的，坐席端只显示"已向客户提供入口"，避免坐席误以为要自己点。
 * 解析失败就退回纯文本渲染，老消息不受影响。</p>
 */
interface MessageCardAction {
  type: string
  label: string
}
interface MessageCard {
  text: string
  actions?: MessageCardAction[]
}

/** 图片消息解析（msgType=2 的正文是 JSON：fileId / url / name + 识别结论） */
function imageOf(message: SessionMessageItem) {
  return message.msgType === 2 ? parseImageContent(message.content) : null
}

/**
 * 图片预览：点缩略图在**本页**弹层里看原图（原来 `target="_blank"` 会多开一个标签页，
 * 坐席聊到一半被带走还得找回来）。右上角有关闭，点遮罩、按 Esc 也能关；
 * 本会话里的图排成一条链，弹层里能左右切换（客户一条消息发 3 张图时，坐席能挨个看）。
 */
const previewOpen = ref(false)
const previewIndex = ref(0)
const previewUrls = computed(() =>
  messages.value
    .flatMap((item) => imagesOf(item).map((image) => image.url))
    .filter((url): url is string => !!url),
)

function openImage(image: ChatImageItem) {
  const url = image?.url
  if (!url) {
    return
  }
  const index = previewUrls.value.indexOf(url)
  previewIndex.value = index >= 0 ? index : 0
  previewOpen.value = true
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
  // 同一条消息再次推送就地更新：图片消息识别完会被回填并重推一次（带上 AI 判读与 OCR 原文）
  const existing = messages.value.findIndex((item) => item.msgId === message.msgId)
  if (existing >= 0) {
    messages.value.splice(existing, 1, { ...messages.value[existing], ...message })
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
 * 聊天区滚动交给公共工具：双 rAF 保证滚到真正的底部，
 * 而且用户翻历史时不会被新消息硬拽下去（只会提示"有新消息 ↓"）。
 */
const { hasNewBelow, scrollToBottom, onScroll, jumpToBottom } = useChatScroll(scrollRef)

function msgRowClass(message: SessionMessageItem) {
  if (message.senderType === 2) {
    return message.visibleTo === 2 ? 'is-note' : 'is-agent'
  }
  if (message.senderType === 1) {
    return 'is-customer'
  }
  return 'is-system'
}

/** 两条消息间隔超过这个时长就插一条时间分割线（和访客窗口同一口径） */
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

/** 分割线文案：今天只写时间，昨天/更早带上日期 */
function dividerText(value?: string | null) {
  const time = parseTime(value)
  if (!time) {
    return ''
  }
  const date = new Date(time)
  const clock = `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  const today = new Date()
  if (date.toDateString() === today.toDateString()) {
    return clock
  }
  const yesterday = new Date(today.getTime() - 24 * 3600 * 1000)
  if (date.toDateString() === yesterday.toDateString()) {
    return `昨天 ${clock}`
  }
  return `${date.getMonth() + 1}月${date.getDate()}日 ${clock}`
}

/**
 * 头像里显示什么：客户/坐席取姓名首字，机器人用品牌图标（不显示字）。
 *
 * <p>和左侧会话列表里的客户头像同一套规则（首字 + 按名字散列配色），
 * 这样坐席在列表里认出的那张脸，进会话还是同一张。</p>
 */
function msgAvatarText(message: SessionMessageItem) {
  if (message.senderType === 1) {
    return (activeSession.value?.customerName || '访').slice(0, 1).toUpperCase()
  }
  if (message.senderType === 2) {
    const name = senderText(message)
    // 拿不到坐席姓名时退回"服"字，别出现空白头像
    return name && name !== '客服' ? name.slice(0, 1).toUpperCase() : '服'
  }
  return ''
}

/**
 * 头像底色：客户沿用**左侧列表那一套**（按会话号散列取 av-1~6），
 * 坐席统一蓝、内部备注橙色、机器人走品牌标。
 */
function msgAvatarClass(message: SessionMessageItem) {
  if (message.senderType === 3) {
    return 'msg-avatar-bot-wrap'
  }
  if (message.senderType === 2) {
    return message.visibleTo === 2 ? 'msg-avatar-note' : 'msg-avatar-agent'
  }
  let hash = 0
  for (const char of activeSessionNo.value) {
    hash = (hash * 31 + char.charCodeAt(0)) % 997
  }
  return `av-${(hash % 6) + 1}`
}

/** 机器人名字（头像旁边的标识），和访客窗口口径一致 */
const senderBotLabel = computed(() => (botName.value ? `${botName.value}（机器人）` : '机器人'))

function senderText(message: SessionMessageItem) {
  if (message.senderType === 2) {
    return message.senderId ? agentName(message.senderId) : '客服'
  }
  if (message.senderType === 1) {
    return '客户'
  }
  if (message.senderType === 3) {
    // 坐席侧需要区分人机，所以用"机器人名字 + 机器人标记"（名字取自租户配置）
    return botName.value ? `${botName.value}（机器人）` : '机器人'
  }
  return '系统'
}

function agentName(agentId?: number | string | null) {
  if (agentId === null || agentId === undefined) {
    return ''
  }
  const member = members.value.find((item) => String(item.userId) === String(agentId))
  return member?.name || `客服#${agentId}`
}

function agentLabel(agentId?: number | string | null) {
  if (!agentId) {
    return '待接待'
  }
  return String(agentId) === String(userStore.userId) ? `我（${agentName(agentId)}）` : agentName(agentId)
}

function preview(item: SessionItem) {
  if (item.lastContent && item.lastContent.trimStart().startsWith('{')) {
    // 图片/卡片消息的正文是 JSON，直接显示会是一串大括号——列表里统一显示成人话
    try {
      const parsed = JSON.parse(item.lastContent) as { url?: string; text?: string }
      if (parsed?.url) {
        return item.lastSenderType === 1 ? '[图片] 客户发来一张图片' : '[图片]'
      }
      if (parsed?.text) {
        return parsed.text
      }
    } catch {
      // 解析不了就按普通文本走
    }
  }
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
  return !!item.agentId && String(item.agentId) === String(userStore.userId)
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
    // 2 = 机器人接待中（还没转人工），与"待接待"区分开
    return item.status === 2 ? '机器人接待中' : '待接待'
  }
  return isMySession(item) ? '我接待中' : '同事接待中'
}

/** 情绪标签配色：越负面越扎眼，坐席扫一眼就知道哪条要优先看 */
function emotionTagType(emotion?: string | null): 'danger' | 'warning' | 'info' | 'success' | 'primary' {
  if (emotion === '愤怒') return 'danger'
  if (emotion === '不满') return 'warning'
  if (emotion === '焦虑') return 'info'
  return 'success'
}

function statusClass(item: SessionItem) {
  if (item.status === 4) {
    return 'st-closed'
  }
  if (!isVisitorOnline(item)) {
    return 'st-offline'
  }
  if (!item.agentId) {
    return item.status === 2 ? 'st-bot' : 'st-wait'
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
 * 打开知识助手：默认把客户最后一句带过去。
 *
 * 坐席最常见的场景是"客户问了我不确定的问题"——让他重新打一遍问题很多余，
 * 直接把客户那句话丢进去，点一下就能看答案。
 */
function openKnowledgeAssistant() {
  const lastCustomer = [...messages.value]
    .reverse()
    .find((item) => item.senderType === 1 && (item.content || '').trim())
  kbAskDefault.value = lastCustomer?.content?.trim() || ''
  kbAskVisible.value = true
}

/** 把知识助手的答案填进回复框：只填不发，坐席确认后再发（避免 AI 的话直接发给客户） */
function onInsertKnowledge(text: string) {
  const value = (text || '').trim()
  if (!value) {
    return
  }
  draft.value = (draft.value || '').trim() ? `${draft.value.trim()}\n${value}` : value
}

/** 被自动分配的会话在列表里闪两下，提示"有新单进来了" */
function highlightAssigned(sessionNo: string) {
  justAssigned.value = sessionNo
  window.setTimeout(() => {
    if (justAssigned.value === sessionNo) {
      justAssigned.value = ''
    }
  }, 5000)
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

/* 接满提示：橙色小字，紧跟计数；点右侧「暂不会被派单」标签可以定位原因 */
.tip-full {
  color: #b45309;
  font-weight: 600;
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

/* 接待量提示：接满时变橙，和"暂不会被派单"标签呼应 */
.load-tip {
  font-size: 12px;
  color: #64748b;
  white-space: nowrap;
}

.load-tip.is-full {
  color: #b45309;
  font-weight: 600;
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

/* 刚被自动接入：蓝色呼吸两下，坐席一眼看到新单 */
.list-item.just-assigned {
  animation: assignFlash 1.1s ease-in-out 2;
}

@keyframes assignFlash {
  0%,
  100% {
    background: transparent;
    box-shadow: none;
  }

  50% {
    background: #dbeafe;
    box-shadow: inset 0 0 0 1px #60a5fa;
  }
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

/* 机器人接待中：和"待接待"（橙色，客户在等）区分开，用中性蓝表示"有人管着" */
.st-bot {
  background: #eef2ff;
  color: #4338ca;
  box-shadow: inset 0 0 0 1px #c7d2fe;
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

/* 列表上的意图 / 情绪小标签：不抢状态标签的位置，只在识别出来时补一小段 */
.li-brain {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 11px;
}

.li-brain-danger {
  background: #fef2f2;
  color: #b91c1c;
}

.li-brain-warning {
  background: #fffbeb;
  color: #b45309;
}

.li-brain-info {
  background: #eff6ff;
  color: #1d4ed8;
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

/* 智能客服识别结果：意图 / 情绪 / 转人工原因，坐席接手前先看这一行 */
.ch-brain {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 6px;
  font-size: 12px;
  color: #94a3b8;
}

.ch-brain-label {
  color: #a5b0c4;
}

.ch-brain-reason {
  color: #b45309;
}

/* 机器人角色说明：浅灰小字，只在人工接待时出现，不抢注意力 */
.ch-bot-note {
  margin-top: 4px;
  font-size: 12px;
  color: #a5b0c4;
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

/* "有新消息"提示：贴着输入框上方浮着，点了才滚到底（不硬拽用户） */
.scroll-new {
  position: sticky;
  bottom: 0;
  display: block;
  margin: 4px auto 0;
  padding: 4px 12px;
  border: 1px solid #bfdbfe;
  border-radius: 999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(29, 78, 216, 0.12);
}

.scroll-new:hover {
  background: #dbeafe;
}

.chat-tip {
  text-align: center;
  color: #94a3b8;
  font-size: 12px;
  padding: 12px 0;
}

.msg-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 14px;
}

/* 头像：和访客窗口同一套规格（32px 圆、显式锁死尺寸，三种身份必须一样大） */
.msg-avatar {
  flex-shrink: 0;
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
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  background: #94a3b8;
}

/* 机器人：浅蓝底 + 居中品牌标 */
.msg-avatar-bot-wrap {
  background: #eff6ff;
  border: 1px solid #dbeafe;
}

.msg-avatar-bot {
  width: 20px;
  height: 20px;
  object-fit: contain;
}

/* 坐席：统一蓝；内部备注：橙色，一眼区分"这句客户看不到" */
.msg-avatar-agent {
  background: linear-gradient(135deg, #1d4ed8, #3b82f6);
}

.msg-avatar-note {
  background: linear-gradient(135deg, #d97706, #f59e0b);
}

/* 客户头像的 6 套底色：和左侧会话列表用的是同一组色值 */
.msg-avatar.av-1 { background: linear-gradient(135deg, #1d4ed8, #3b82f6); }
.msg-avatar.av-2 { background: linear-gradient(135deg, #0f766e, #14b8a6); }
.msg-avatar.av-3 { background: linear-gradient(135deg, #7c3aed, #a78bfa); }
.msg-avatar.av-4 { background: linear-gradient(135deg, #b45309, #f59e0b); }
.msg-avatar.av-5 { background: linear-gradient(135deg, #be123c, #fb7185); }
.msg-avatar.av-6 { background: linear-gradient(135deg, #0369a1, #38bdf8); }

/* 名字 + 气泡竖排；气泡宽度在这里控制（扣掉头像与复制按钮） */
.msg-main {
  min-width: 0;
  max-width: 62%;
  display: flex;
  flex-direction: column;
}

.msg-row.is-customer .msg-main {
  align-items: flex-end;
}

/* 客户这条没有名字行，头像跟气泡顶部对齐 */
.msg-row.is-customer .msg-avatar {
  margin-top: 2px;
}

/* 时间分割线：居中灰字，代替"每条消息都挂时间" */
.msg-divider {
  text-align: center;
  font-size: 11px;
  color: #a3aec2;
  margin: 10px 0 8px;
}

/* 系统提示：居中灰胶囊，没有头像也没有气泡 */
.msg-system {
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

/* 悬停出现的复制按钮：常态隐藏，鼠标移到这条消息上才出现。
   放在气泡外侧（收到的消息在左、我发的在右），不会压住正文，
   正文自身仍然是普通可选文本，用鼠标划选 + Ctrl/Cmd + C 一样能用。 */
.msg-actions {
  display: flex;
  align-items: flex-start;
  padding-top: 20px;
  opacity: 0;
  transition: opacity 0.15s ease;
  order: 3;
}

.msg-row.is-customer .msg-actions {
  order: -1;
  padding-top: 2px;
}

.msg-row:hover .msg-actions,
.msg-row:focus-within .msg-actions {
  opacity: 1;
}

.msg-copy {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #fff;
  color: #64748b;
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease, background 0.15s ease;
}

.msg-copy:hover {
  color: #2563eb;
  border-color: #bfdbfe;
  background: #eff6ff;
}

/* 触屏设备没有 hover：让按钮常驻（半透明），否则平板上点不出来 */
@media (hover: none) {
  .msg-actions { opacity: 0.5; }
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
  width: fit-content;
  max-width: 100%;
  padding: 9px 12px;
  border-radius: 12px;
  /* 靠近头像的那个角收小，形成"从谁嘴里说出来"的观感 */
  border-top-left-radius: 4px;
  background: #fff;
  border: 1px solid #e8eef6;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.03);
}

/* 蓝色气泡留给访客（访客窗口里"自己说的"就是蓝色），人工客服走默认白底 */
.is-customer .msg-bubble {
  background: #2563eb;
  border-color: #2563eb;
  border-top-left-radius: 12px;
  border-top-right-radius: 4px;
}

/* 图片消息（文字 + 图）不走蓝色气泡：它是"媒体卡片"，
   白底 + 深色文字才读得清，下面的"AI 判读"也是照白底设计的 */
.is-customer .msg-bubble.is-image {
  background: #fff;
  border-color: #e8eef6;
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

/* 机器人正在输入：和访客窗口一致的三点跳动 */
.msg-bubble.is-typing {
  padding: 10px 14px;
}

.typing-dots {
  display: flex;
  gap: 4px;
  align-items: center;
  height: 14px;
}

.typing-dots i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #b6c2d4;
  animation: typing-bounce 1.2s infinite ease-in-out;
}

.typing-dots i:nth-child(2) { animation-delay: 0.15s; }
.typing-dots i:nth-child(3) { animation-delay: 0.3s; }

@keyframes typing-bounce {
  0%, 60%, 100% { transform: translateY(0); opacity: 0.55; }
  30% { transform: translateY(-4px); opacity: 1; }
}

/* 图片消息：缩略图 + AI 判读块 */
.msg-image img {
  display: block;
  max-width: 260px;
  max-height: 260px;
  border-radius: 8px;
  background: #f1f5f9;
  cursor: zoom-in;
}

/* 客户一条消息带多张图（最多 3 张）：并排一行，尺寸统一 */
.msg-images {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.msg-images.is-multi .msg-image img {
  width: 118px;
  height: 118px;
  object-fit: cover;
}

.msg-image {
  display: block;
  padding: 0;
  border: 0;
  background: none;
  text-align: left;
}

/* 客户随图说的那句话：显示在图片上方，坐席看图和看文字的顺序和客户发的时候一致 */
.msg-image-text {
  display: block;
  margin-bottom: 6px;
  color: #1f2937;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-ai {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e8eef6;
  font-size: 12px;
  color: #475569;
}

.msg-ai.muted {
  color: #94a3b8;
}

.msg-ai-head {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #1d4ed8;
  font-weight: 600;
  margin-bottom: 4px;
}

.msg-ai-tag {
  padding: 1px 6px;
  border-radius: 4px;
  background: #eff6ff;
  color: #1d4ed8;
  font-weight: 500;
}

/* "建议人工介入"：暖色，和蓝色信息标区分开 */
.msg-ai-tag.is-warn {
  background: #fef3c7;
  color: #b45309;
}

.msg-ai-text {
  line-height: 1.6;
}

.msg-ai-error {
  margin-top: 4px;
  color: #b45309;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-ai-ocr {
  margin-top: 4px;
}

.msg-ai-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  color: #475569;
  max-height: 220px;
  overflow: auto;
}

.msg-card-note {
  margin-top: 6px;
  font-size: 11px;
  color: #b45309;
}

.msg-meta {
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 3px;
}

/* 客户说的话是蓝底、气泡外面是浅色页面：名字用可读的灰，
   别再用浅蓝（原来那版在浅底上几乎看不见） */
.is-customer .msg-meta {
  color: #94a3b8;
}

/* 时间统一放在消息下方：先看内容，时间只是辅助信息 */
/* 时间直接显示：坐席要能一眼看出"客户这句是什么时候说的"，别做成悬停才出现 */
.msg-time {
  margin-top: 4px;
  font-size: 11px;
  color: #94a3b8;
  text-align: left;
}

.is-customer .msg-time {
  color: #c7dbff;
  text-align: right;
}

/* 蓝气泡上的浅蓝时间，放到白底卡片上就看不见了，单独调回来 */
.is-customer .msg-bubble.is-image .msg-time {
  color: #94a3b8;
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

/* 质检命中角标：深红底白字，蓝气泡/白气泡上都清楚 */
.qa-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: #dc2626;
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
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

/* 实时质检警示条：命中规则当场挂出来，坐席不处理就一直提醒 */
.qa-banner {
  display: flex;
  gap: 10px;
  padding: 10px 14px;
  background: #fff7ed;
  border-top: 1px solid #fed7aa;
  border-bottom: 1px solid #fed7aa;
  color: #9a3412;
}

.qa-banner-main { flex: 1; min-width: 0; }
.qa-banner-title { font-weight: 600; font-size: 13px; margin-bottom: 4px; }
.qa-banner-sub { font-weight: 400; color: #b45309; margin-left: 8px; }

.qa-banner-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
  padding: 2px 0;
}

.qa-banner-row .el-button {
  flex: none;
}

.qa-sev {
  flex: none;
  margin-top: 1px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  color: #fff;
  background: #f59e0b;
}

.qa-sev-3 { background: #dc2626; }
.qa-sev-1 { background: #64748b; }
.qa-rule { flex: none; font-weight: 600; }

/* 处置建议可能比较长（比如超时提醒），允许折成两行，别截成看不清的半句 */
.qa-snippet {
  flex: 1;
  min-width: 0;
  color: #92400e;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.qa-banner-more { font-size: 12px; color: #b45309; margin-top: 4px; }

/* 命中的那条消息：只加一圈红边，不动底色
   —— 访客气泡是蓝底白字，改底色会把白字压成看不清 */
.msg-row.qa-risky .msg-bubble {
  box-shadow: 0 0 0 2px #f87171;
}
</style>
