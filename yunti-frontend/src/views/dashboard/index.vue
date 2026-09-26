<template>
  <div class="dashboard">
    <!-- 页头：问候 + 今日摘要 + 快捷动作（原型 dashboard 的 page-head） -->
    <div class="page-head">
      <div>
        <h2>{{ greeting }}，{{ userName || '你好' }} 👋</h2>
        <div class="sub">
          {{ todayText }}
          <template v-if="canViewMetrics && data">
            · 当前 {{ data.queuingSessions }} 个会话等待接待 · {{ slaWarnCount }} 个工单临近 SLA
          </template>
        </div>
      </div>
      <div class="page-head-actions">
        <el-button class="btn-outline" @click="onToday">
          <el-icon class="btn-icon"><Calendar /></el-icon>
          今日
        </el-button>
        <el-button v-if="ticketCanOperate" class="btn-primary" type="primary" @click="newTicket">
          <el-icon class="btn-icon"><Plus /></el-icon>
          新建工单
        </el-button>
        <el-button :loading="loading" circle title="刷新" @click="load">
          <el-icon><Refresh /></el-icon>
        </el-button>
      </div>
    </div>
    <OnboardingGuide />

    <!-- 经营数据只有管理员 / 主管能看：没权限就不摆数字，改给一句说明 -->
    <el-alert
      v-if="!canViewMetrics"
      class="mt-18"
      type="info"
      show-icon
      :closable="false"
      title="经营数据只有企业管理员 / 客服主管能看"
    >
      <div class="alert-tip">
        你仍然可以正常接待客户：去「在线客服」接待、在「工单中心」跟进、在「客户 360」看客户资料。
      </div>
    </el-alert>
    <el-alert
      v-else-if="error"
      class="mt-18"
      type="error"
      show-icon
      :closable="false"
      :title="`经营数据加载失败：${error}`"
    >
      <div class="alert-tip">
        如果提示 <code>404</code> / 系统内部错误，说明 customer-service 还没重启（本页依赖指标接口）；
        如果是"没有数据"，先执行 <code>bash scripts/init-metrics-demo.sh</code>，或到坐席绩效页点一次「重算今天」。
      </div>
    </el-alert>
    <template v-if="canViewMetrics && data">
      <!-- KPI：口径与坐席绩效一致，全部来自本系统自己的业务数据 -->
      <div class="kpi-grid">
        <div v-for="item in kpis" :key="item.label" class="kpi">
          <div class="k-top">
            <span class="k-label">{{ item.label }}</span>
            <span class="k-icon" :class="item.tone">
              <el-icon :size="17"><component :is="ICONS[item.icon]" /></el-icon>
            </span>
          </div>
          <div class="k-value">
            {{ item.value }}<small v-if="item.unit">{{ item.unit }}</small>
          </div>
          <div class="k-foot">
            <span v-if="item.delta" class="delta" :class="item.deltaClass">{{ item.delta }}</span>
            <span>{{ item.foot }}</span>
            <span v-if="item.spark" class="spark">
              <svg viewBox="0 0 110 34" preserveAspectRatio="none">
                <polyline
                  :points="item.spark"
                  fill="none"
                  :stroke="item.sparkColor"
                  stroke-width="2.2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </span>
          </div>
        </div>
      </div>
      <div class="grid-12">
        <!-- 会话趋势：近 14 天会话总量 + 机器人接待量 -->
        <div class="card col-8">
          <div class="card-head">
            <div>
              <div class="card-title">会话趋势</div>
              <div class="card-sub">近 14 天会话总量与机器人接待量</div>
            </div>
            <div class="legend">
              <span class="li"><i class="sw sw-total" />会话总量</span>
              <span class="li"><i class="sw sw-bot" />机器人接待</span>
            </div>
          </div>
          <div class="card-body">
            <svg class="chart" :viewBox="`0 0 ${trendChart.W} ${trendChart.H}`" preserveAspectRatio="xMidYMid meet">
              <defs>
                <linearGradient id="dash-chart-bg" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="#2563eb" />
                  <stop offset="1" stop-color="#0ea5e9" />
                </linearGradient>
              </defs>
              <rect x="0" y="0" :width="trendChart.W" :height="trendChart.H" rx="14" fill="url(#dash-chart-bg)" />
              <g class="grid">
                <template v-for="line in trendChart.grid" :key="`g-${line.y}`">
                  <line :x1="trendChart.pad.l" :y1="line.y" :x2="trendChart.W - trendChart.pad.r" :y2="line.y" />
                  <text :x="trendChart.pad.l - 9" :y="line.y + 4" text-anchor="end">{{ line.label }}</text>
                </template>
              </g>
              <template v-for="s in trendChart.series" :key="s.name">
                <path v-if="s.area" :d="s.area" fill="#ffffff" opacity="0.13" />
                <polyline
                  :points="s.path"
                  fill="none"
                  :stroke="s.color"
                  :stroke-dasharray="s.dash"
                  :opacity="s.opacity"
                  stroke-width="2.4"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
                <circle
                  v-for="dot in s.dots"
                  :key="`${s.name}-${dot.label}`"
                  :cx="dot.cx"
                  :cy="dot.cy"
                  r="3.2"
                  fill="#ffffff"
                  :stroke="s.color"
                  stroke-width="1.6"
                >
                  <title>{{ dot.label }} · {{ s.name }}：{{ dot.v }}</title>
                </circle>
              </template>
              <g class="x-labels">
                <text v-for="x in trendChart.xLabels" :key="x.label" :x="x.x" :y="trendChart.H - 8" text-anchor="middle">
                  {{ x.label }}
                </text>
              </g>
            </svg>
          </div>
        </div>
        <!-- 渠道分布 -->
        <div class="card col-4">
          <div class="card-head">
            <div class="card-title">渠道分布</div>
          </div>
          <div class="card-body">
            <div v-if="channelSegments.length" class="donut">
              <svg viewBox="0 0 120 120">
                <circle
                  v-for="seg in channelSegments"
                  :key="seg.name"
                  r="44"
                  cx="60"
                  cy="60"
                  fill="none"
                  :stroke="seg.color"
                  stroke-width="15"
                  :stroke-dasharray="seg.dash"
                  :stroke-dashoffset="seg.offset"
                  transform="rotate(-90 60 60)"
                />
              </svg>
              <div class="donut-center">
                <div class="donut-value">{{ data.sessionToday }}</div>
                <div class="donut-label">今日会话</div>
              </div>
            </div>
            <el-empty v-else description="今天还没有会话" :image-size="70" />
            <div class="legend center">
              <span v-for="seg in channelSegments" :key="seg.name" class="li">
                <i class="sw" :style="{ background: seg.color }" />{{ seg.name }}
                <b class="num">{{ seg.percent }}%</b>
              </span>
            </div>
          </div>
        </div>
        <!-- 满意度趋势（好评率 = 4~5 星占比） -->
        <div class="card col-4">
          <div class="card-head">
            <div>
              <div class="card-title">满意度趋势</div>
              <div class="card-sub">近 7 日好评率</div>
            </div>
            <span class="badge b-green">{{ csatRateText }}</span>
          </div>
          <div class="card-body">
            <svg class="chart" :viewBox="`0 0 ${csatChart.W} ${csatChart.H}`" preserveAspectRatio="xMidYMid meet">
              <defs>
                <linearGradient id="dash-csat-bg" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="#2563eb" />
                  <stop offset="1" stop-color="#0ea5e9" />
                </linearGradient>
              </defs>
              <rect x="0" y="0" :width="csatChart.W" :height="csatChart.H" rx="14" fill="url(#dash-csat-bg)" />
              <g class="grid">
                <template v-for="line in csatChart.grid" :key="`g-${line.y}`">
                  <line :x1="csatChart.pad.l" :y1="line.y" :x2="csatChart.W - csatChart.pad.r" :y2="line.y" />
                  <text :x="csatChart.pad.l - 9" :y="line.y + 4" text-anchor="end">{{ line.label }}</text>
                </template>
              </g>
              <template v-for="s in csatChart.series" :key="s.name">
                <path v-if="s.area" :d="s.area" fill="#ffffff" opacity="0.13" />
                <polyline
                  :points="s.path"
                  fill="none"
                  :stroke="s.color"
                  stroke-width="2.4"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
                <circle
                  v-for="dot in s.dots"
                  :key="`${s.name}-${dot.label}`"
                  :cx="dot.cx"
                  :cy="dot.cy"
                  r="3.2"
                  fill="#ffffff"
                  :stroke="s.color"
                  stroke-width="1.6"
                >
                  <title>{{ dot.label }} · {{ dot.v.toFixed(1) }}%</title>
                </circle>
              </template>
              <g class="x-labels">
                <text v-for="x in csatChart.xLabels" :key="x.label" :x="x.x" :y="csatChart.H - 8" text-anchor="middle">
                  {{ x.label }}
                </text>
              </g>
            </svg>
          </div>
        </div>
        <!-- 坐席实时状态 -->
        <div class="card col-4">
          <div class="card-head">
            <div>
              <div class="card-title">坐席实时状态</div>
              <div class="card-sub">今日服务量 Top 6</div>
            </div>
            <span class="badge b-blue">{{ data.onlineAgents }}/{{ data.totalAgents || data.onlineAgents }} 在线</span>
          </div>
          <div class="card-body">
            <div v-for="(agent, i) in agentRows" :key="agent.agentId || agent.agentName" class="rank-row">
              <div class="avatar a-30" :class="`av-${(i % 8) + 1}`">
                {{ agent.agentName.slice(0, 1) }}
                <span class="st" :class="statusClass(agent)" />
              </div>
              <div class="rank-main">
                <div class="rank-name">{{ agent.agentName }}</div>
                <div class="rank-sub">{{ agent.statusText }} · 在接 {{ agent.activeCount }}</div>
              </div>
              <div class="rank-right">
                <div class="rank-num">{{ agent.sessionCount }}<span> 单</span></div>
                <div class="rank-csat" :class="{ good: agentRate(agent) >= 96 }">{{ agentRateText(agent) }}</div>
              </div>
            </div>
            <el-empty v-if="!agentRows.length" description="今天还没有坐席接待" :image-size="70" />
          </div>
        </div>
        <!-- 实时动态 -->
        <div class="card col-4">
          <div class="card-head">
            <div>
              <div class="card-title">实时动态</div>
              <div class="card-sub">全渠道事件流 · 今日</div>
            </div>
            <span class="dot green pulse" />
          </div>
          <div class="card-body">
            <div v-for="(item, i) in activityRows" :key="`${item.time}-${i}`" class="list-item">
              <span class="k-icon" :class="item.tone">
                <el-icon :size="16"><component :is="ICONS[item.icon]" /></el-icon>
              </span>
              <div class="li-main">{{ item.text }}</div>
              <span class="li-time">{{ item.time }}</span>
            </div>
            <el-empty v-if="!activityRows.length" description="今天还没有动态" :image-size="70" />
          </div>
        </div>
        <!-- 待办工单 -->
        <div class="card col-5">
          <div class="card-head">
            <div>
              <div class="card-title">待办工单</div>
              <div class="card-sub">按 SLA 优先级排序</div>
            </div>
            <button class="btn-ghost-sm" @click="router.push('/modules/tickets')">
              查看全部
              <el-icon><ArrowRight /></el-icon>
            </button>
          </div>
          <div class="table-wrap">
            <table class="table">
              <thead>
                <tr><th>工单</th><th>优先级</th><th>状态</th><th>SLA</th></tr>
              </thead>
              <tbody>
                <tr v-for="row in slaTickets" :key="row.ticketNo" class="row-click" @click="openTicket(row.ticketNo)">
                  <td>
                    <div class="cell-main">{{ row.title }}</div>
                    <div class="cell-sub">{{ row.ticketNo }} · {{ row.customerName || '访客' }} · {{ row.categoryText }}</div>
                  </td>
                  <td><span class="badge" :class="priorityClass(row.priority)">{{ row.priorityText }}</span></td>
                  <td><span class="badge" :class="statusBadgeClass(row.status)">{{ row.statusText }}</span></td>
                  <td>
                    <span class="sla" :class="slaClass(row.slaState)">
                      <el-icon :size="11"><Clock /></el-icon>{{ slaText(row) }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
            <el-empty v-if="!slaTickets.length" description="没有待办工单" :image-size="70" />
          </div>
        </div>
        <!-- 待办中心 -->
        <div class="card col-4">
          <div class="card-head">
            <div>
              <div class="card-title">待办中心</div>
              <div class="card-sub">SLA 预警 · 质检复核 · 实时预警 · 排队会话</div>
            </div>
            <span v-if="todoRows.length" class="dot red pulse" />
          </div>
          <div class="card-body">
            <div v-for="item in todoRows" :key="item.title" class="list-item" @click="router.push(item.path)">
              <span class="k-icon" :class="item.tone">
                <el-icon :size="16"><component :is="ICONS[item.icon]" /></el-icon>
              </span>
              <div class="li-main">
                <div class="li-title">{{ item.title }}</div>
                <div class="li-sub">{{ item.sub }}</div>
              </div>
              <el-icon class="li-arrow"><ArrowRight /></el-icon>
            </div>
            <el-empty v-if="!todoRows.length" description="暂无待办，一切都在掌控中" :image-size="70" />
          </div>
        </div>
        <!-- TOP 高频问题 -->
        <div class="card col-3">
          <div class="card-head">
            <div class="card-title">TOP 高频问题</div>
          </div>
          <div class="card-body">
            <div v-for="(item, i) in issueRows" :key="item.name" class="stat-line">
              <span class="sl-name">
                <span class="rank-pos" :class="`r${i + 1}`">{{ i + 1 }}</span>{{ item.name }}
              </span>
              <span class="sl-right">
                <span class="progress">
                  <span class="progress-bar" :class="i === 0 ? 'red' : 'amber'" :style="{ width: item.width + '%' }" />
                </span>
                <span class="sl-val">{{ item.count }}</span>
              </span>
            </div>
            <el-empty v-if="!issueRows.length" description="今天还没有识别到意图" :image-size="70" />
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, type Component } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowRight,
  Bell,
  Calendar,
  ChatDotRound,
  Clock,
  MagicStick,
  Plus,
  Refresh,
  Service,
  Star,
  SwitchButton,
  Tickets,
  Timer,
  UserFilled,
  WarningFilled,
} from '@element-plus/icons-vue'
import OnboardingGuide from '../../components/OnboardingGuide.vue'
import {
  fetchMetricsAccess,
  fetchRealtime,
  type RealtimeMetrics,
} from '../../api/customer/metrics'
import {
  fetchTicketAccess,
  fetchTicketOverview,
  fetchTickets,
  type TicketItem,
  type TicketOverview,
} from '../../api/customer/ticket'
import { fetchQaOverview, type QaOverview } from '../../api/customer/qa'
import { useUserStore } from '../../stores/user'

/**
 * 数据概览（原型 dashboard）。
 *
 * <p>排版与配色照着产品原型 index.html#/dashboard 来做：6 张 KPI + 8 张卡片，
 * 颜色全部取自原型的设计变量（css/design-system.css 的 token）——
 * 卡片边框 #e6e8ef、正文 #0f172a / #475569 / #94a3b8、图标底色 blue-soft #eaf2fe 这一套；
 * 两张折线图的画法是原型的 Chart.line：**品牌蓝渐变面板 + 白色曲线**，不是白底蓝线。</p>
 *
 * <p>和原型唯一的区别是数字：原型里写的是一组写死的 mock（会话量、满意率、人名），
 * 这里全部换成后端真实指标——所以口径必须能在系统里自证：
 * 会话、消息、评价、坐席状态都来自本系统自己的业务表。</p>
 */

type IconKey =
  | 'session'
  | 'bot'
  | 'zap'
  | 'star'
  | 'users'
  | 'clock'
  | 'transfer'
  | 'ticket'
  | 'alert'
  | 'qa'
  | 'queue'

const ICONS: Record<IconKey, Component> = {
  session: ChatDotRound,
  bot: MagicStick,
  zap: Timer,
  star: Star,
  users: UserFilled,
  clock: Clock,
  transfer: SwitchButton,
  ticket: Tickets,
  alert: WarningFilled,
  qa: Service,
  queue: Bell,
}

/** 环形图配色：原型的 CHART_DATA.channelDist 色板，和渠道接入页保持一致 */
const DONUT_COLORS = ['#3b82f6', '#10b981', '#0ea5e9', '#06b6d4', '#f59e0b', '#94a3b8']

/** 折线图配色：原型里第一条是白线，第二条是浅蓝虚线（图例的小色块就是这两个色） */
const LINE_TOTAL = '#ffffff'
const LINE_BOT = '#bfe3ff'

/** 迷你折线颜色：原型按 KPI 色调取色，未列出的色调统一用主蓝 */
const SPARK_COLORS: Record<string, string> = {
  green: '#10b981',
  red: '#ef4444',
  amber: '#f59e0b',
  blue: '#3b82f6',
}
const sparkColorOf = (tone: string) => SPARK_COLORS[tone] ?? '#3b82f6'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const error = ref('')
const data = ref<RealtimeMetrics | null>(null)
/** 经营数据只有管理员 / 主管能看：没权限就不摆数字 */
const canViewMetrics = ref(true)
const ticketCanOperate = ref(false)
const ticketOverview = ref<TicketOverview | null>(null)
const pendingTickets = ref<TicketItem[]>([])
const qaOverview = ref<QaOverview | null>(null)
let timer: number | undefined

const userName = computed(() => userStore.name)

const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '凌晨好'
  if (hour < 12) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const todayText = computed(() => {
  const now = new Date()
  return `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日`
})

/** 临近 / 已超 SLA 的工单数（页头摘要与待办中心都用它） */
const slaWarnCount = computed(() => {
  const o = ticketOverview.value
  return o ? o.warning + o.overdue : 0
})

/** 百分比展示：分母为 0 时给"—"，不要编一个 100% 出来 */
function rateText(part?: number | null, total?: number | null): string {
  if (!total || total <= 0 || part == null) return '—'
  return `${((part / total) * 100).toFixed(1)}%`
}

function percentOf(part?: number | null, total?: number | null): number | null {
  if (!total || total <= 0 || part == null) return null
  return (part / total) * 100
}

/** KPI 卡里的迷你折线（原型是 110 x 34 的 svg polyline） */
function sparkPoints(values: Array<number | null | undefined>): string {
  const list = values.filter((v): v is number => v != null)
  if (list.length < 2) return ''
  const max = Math.max(...list)
  const min = Math.min(...list)
  return list
    .map((v, i) => {
      const x = (i / (list.length - 1)) * 110
      const y = 30 - ((v - min) / (max - min || 1)) * 24
      return `${x.toFixed(1)},${y.toFixed(1)}`
    })
    .join(' ')
}

/** 与前一天的差值文案：只有"昨天也有数"时才给，避免拿 0 冒充增长 */
function deltaText(current?: number | null, previous?: number | null, suffix = '%'): string {
  if (current == null || previous == null || previous <= 0) return ''
  const diff = current - previous
  if (Math.abs(diff) < 0.05) return ''
  const arrow = diff > 0 ? '↑' : '↓'
  const text = Math.abs(diff) >= 10 ? Math.abs(diff).toFixed(0) : Math.abs(diff).toFixed(1)
  return `${arrow}${text}${suffix}`
}

/** 昨天的趋势点：agent_daily_metric 里已结束的那一天 */
function yesterday() {
  const list = data.value?.trend ?? []
  return list.length >= 2 ? list[list.length - 2] : null
}

const kpis = computed(() => {
  const d = data.value
  if (!d) return []
  const trend = d.trend ?? []
  const y = yesterday()
  const todayRate = percentOf(d.csatGoodToday, d.csatCountToday)
  const yesterdayRate = y ? percentOf(y.goodCsatCount, y.csatCount) : null
  const offline = Math.max(0, (d.totalAgents || 0) - d.onlineAgents - d.busyAgents)

  return [
    {
      label: '今日会话',
      value: String(d.sessionToday),
      unit: '次',
      tone: 'blue',
      icon: 'session' as IconKey,
      delta: deltaText(d.sessionToday, y ? y.sessionCount : null),
      deltaClass: d.sessionToday >= (y?.sessionCount ?? 0) ? 'up' : 'down',
      foot: '较昨日',
      spark: sparkPoints(trend.map((item) => item.sessionCount).slice(-8)),
      sparkColor: sparkColorOf('blue'),
    },
    {
      label: '机器人接待率',
      value: String(d.botRatio),
      unit: '%',
      tone: 'purple',
      icon: 'bot' as IconKey,
      delta: deltaText(d.botRatio, y ? percentOf(y.botCount, y.sessionCount) : null),
      deltaClass: d.botRatio >= (y ? (percentOf(y.botCount, y.sessionCount) ?? 0) : 0) ? 'up' : 'down',
      foot: '机器人独立接待（未转人工）',
      spark: sparkPoints(
        trend.map((item) => percentOf(item.botCount, item.sessionCount)).slice(-8),
      ),
      sparkColor: sparkColorOf('purple'),
    },
    {
      label: '平均首响时长',
      value: String(d.firstResponseSeconds),
      unit: '秒',
      tone: 'amber',
      icon: 'zap' as IconKey,
      delta: deltaText(d.firstResponseSeconds, y ? y.firstResponseSeconds : null, ' 秒'),
      deltaClass: d.firstResponseSeconds <= (y?.firstResponseSeconds ?? Number.MAX_SAFE_INTEGER) ? 'up' : 'down',
      foot: '较昨日（越短越好）',
      spark: sparkPoints(trend.map((item) => item.firstResponseSeconds).slice(-8)),
      sparkColor: sparkColorOf('amber'),
    },
    {
      label: '客户满意度',
      value: todayRate == null ? '—' : todayRate.toFixed(1),
      unit: todayRate == null ? '' : '%',
      tone: 'green',
      icon: 'star' as IconKey,
      delta: deltaText(todayRate, yesterdayRate),
      deltaClass: (todayRate ?? 0) >= (yesterdayRate ?? 0) ? 'up' : 'down',
      foot: `今日 ${d.csatCountToday} 条评价`,
      spark: sparkPoints(
        trend.map((item) => percentOf(item.goodCsatCount, item.csatCount)).slice(-8),
      ),
      sparkColor: sparkColorOf('green'),
    },
    {
      label: '在线坐席',
      value: `${d.onlineAgents}/${d.totalAgents || d.onlineAgents}`,
      unit: '',
      tone: 'cyan',
      icon: 'users' as IconKey,
      delta: d.busyAgents ? `${d.busyAgents} 人忙碌` : '',
      deltaClass: 'flat',
      foot: offline ? `${offline} 人离线` : '全部在线',
      spark: '',
      sparkColor: sparkColorOf('cyan'),
    },
    {
      label: '排队会话',
      value: String(d.queuingSessions),
      unit: '',
      tone: 'red',
      icon: 'clock' as IconKey,
      delta: '',
      deltaClass: 'flat',
      foot: `进行中 ${d.activeSessions} 个会话`,
      spark: '',
      sparkColor: sparkColorOf('red'),
    },
  ]
})

const PAD = { l: 46, r: 16, t: 16, b: 28 }
const CHART_W = 760

/**
 * 手写 SVG 折线图（项目没有引图表库，原型也是手写的）。
 *
 * @param labels x 轴刻度
 * @param series 一条或多条曲线，data 长度要和 labels 一致
 * @param height svg 高度
 * @param yMax 固定 y 轴上限（满意度要固定 100，否则曲线会被拉满屏看不出波动）
 * @param format y 轴刻度文案
 */
function buildLineChart(
  labels: string[],
  series: Array<{ name: string; color: string; data: number[]; dash?: string; area?: boolean; opacity?: number }>,
  options: { height?: number; yMax?: number; format?: (v: number) => string } = {},
) {
  const H = options.height ?? 240
  const iw = CHART_W - PAD.l - PAD.r
  const ih = H - PAD.t - PAD.b
  const all = series.flatMap((s) => s.data)
  const max = options.yMax ?? Math.max(1, ...all) * 1.15
  const format = options.format ?? ((v: number) => String(v))
  const xOf = (i: number) => (labels.length <= 1 ? PAD.l + iw / 2 : PAD.l + (i / (labels.length - 1)) * iw)
  const yOf = (v: number) => PAD.t + ih - (v / max) * ih

  return {
    W: CHART_W,
    H,
    pad: PAD,
    grid: [0, 0.25, 0.5, 0.75, 1].map((g) => ({
      y: PAD.t + ih * g,
      label: format(Math.round(max * (1 - g))),
    })),
    xLabels: labels.map((label, i) => ({ x: xOf(i), label })),
    series: series.map((s) => ({
      name: s.name,
      color: s.color,
      dash: s.dash,
      opacity: s.opacity ?? 1,
      path: s.data.map((v, i) => `${xOf(i).toFixed(1)},${yOf(v).toFixed(1)}`).join(' '),
      area:
        s.area === false
          ? ''
          : `M${xOf(0).toFixed(1)},${(PAD.t + ih).toFixed(1)} ` +
            s.data.map((v, i) => `L${xOf(i).toFixed(1)},${yOf(v).toFixed(1)}`).join(' ') +
            ` L${xOf(s.data.length - 1).toFixed(1)},${(PAD.t + ih).toFixed(1)} Z`,
      dots: s.data.map((v, i) => ({ cx: xOf(i), cy: yOf(v), v, label: labels[i] })),
    })),
  }
}

const trendChart = computed(() => {
  const trend = data.value?.trend ?? []
  return buildLineChart(
    trend.map((item) => item.day),
    [
      { name: '会话总量', color: LINE_TOTAL, data: trend.map((item) => item.sessionCount) },
      {
        name: '机器人接待',
        color: LINE_BOT,
        data: trend.map((item) => item.botCount),
        dash: '6 5',
        area: false,
      },
    ],
    { height: 240 },
  )
})

const csatChart = computed(() => {
  // 近 7 日：好评率（4~5 星占比）；当天没有评价的点按 0 画，tooltip 仍是真实条数
  const last7 = (data.value?.trend ?? []).slice(-7)
  return buildLineChart(
    last7.map((item) => item.day),
    [
      {
        name: '好评率',
        color: LINE_TOTAL,
        data: last7.map((item) => percentOf(item.goodCsatCount, item.csatCount) ?? 0),
      },
    ],
    { height: 200, yMax: 100, format: (v) => `${v}%` },
  )
})

/** 近 7 日整体好评率（卡片右上角 badge） */
const csatRateText = computed(() => {
  const last7 = (data.value?.trend ?? []).slice(-7)
  const good = last7.reduce((sum, item) => sum + item.goodCsatCount, 0)
  const total = last7.reduce((sum, item) => sum + item.csatCount, 0)
  return rateText(good, total)
})

const channelSegments = computed(() => {
  const list = data.value?.channels ?? []
  const total = list.reduce((sum, item) => sum + item.count, 0)
  const radius = 44
  const circumference = 2 * Math.PI * radius
  let offset = 0
  return list.map((item, i) => {
    const fraction = total > 0 ? item.count / total : 0
    const segment = {
      name: item.name,
      count: item.count,
      percent: Math.round(fraction * 100),
      color: DONUT_COLORS[i % DONUT_COLORS.length],
      dash: `${Math.max(0, fraction * circumference - 2).toFixed(2)} ${(circumference - fraction * circumference + 2).toFixed(2)}`,
      offset: (-offset * circumference).toFixed(2),
    }
    offset += fraction
    return segment
  })
})

const agentRows = computed(() => (data.value?.agentLive ?? []).slice(0, 6))

function agentRate(agent: { csatScore?: number | null }): number {
  // 实时状态卡里的满意度是 1~5 分制，折算成百分制方便和 KPI 对齐
  return agent.csatScore == null ? 0 : (agent.csatScore / 5) * 100
}

function agentRateText(agent: { csatScore?: number | null }): string {
  return agent.csatScore == null ? '暂无评价' : `满意度 ${agentRate(agent).toFixed(0)}%`
}

/** 头像上的状态点：原型只有 online / busy / off 三种 */
function statusClass(agent: { connected: boolean; status: number }): string {
  if (!agent.connected) return 'off'
  return agent.status === 1 ? 'online' : 'busy'
}

const ACTIVITY_STYLE: Record<string, { icon: IconKey; tone: string }> = {
  session: { icon: 'session', tone: 'blue' },
  transfer: { icon: 'transfer', tone: 'amber' },
  csat: { icon: 'star', tone: 'green' },
  ticket: { icon: 'ticket', tone: 'purple' },
  alert: { icon: 'alert', tone: 'red' },
}

const activityRows = computed(() =>
  (data.value?.activities ?? []).map((item) => {
    const style = ACTIVITY_STYLE[item.kind] ?? { icon: 'session' as IconKey, tone: 'blue' }
    const raw = item.eventTime ? String(item.eventTime).replace('T', ' ') : ''
    return {
      text: item.text,
      icon: style.icon,
      tone: style.tone,
      time: raw.length >= 16 ? raw.slice(11, 16) : '',
    }
  }),
)

/** 待办工单：先按 SLA 状态（超时 > 临近 > 正常）再按剩余时间排 */
const slaTickets = computed(() => {
  const list = [...pendingTickets.value]
  list.sort((a, b) => {
    if (a.slaState !== b.slaState) return b.slaState - a.slaState
    return (a.remainMinutes ?? Number.MAX_SAFE_INTEGER) - (b.remainMinutes ?? Number.MAX_SAFE_INTEGER)
  })
  return list.slice(0, 5)
})

const todoRows = computed(() => {
  const rows: Array<{ icon: IconKey; tone: string; title: string; sub: string; path: string }> = []
  const warn = slaWarnCount.value
  if (warn > 0) {
    rows.push({
      icon: 'alert',
      tone: 'red',
      title: `工单 SLA 预警：${warn} 张`,
      sub: `其中已超时 ${ticketOverview.value?.overdue ?? 0} 张，请尽快处理或转派`,
      path: '/modules/tickets',
    })
  }
  const qaPending = qaOverview.value?.pending ?? 0
  if (qaPending > 0) {
    rows.push({
      icon: 'qa',
      tone: 'amber',
      title: `质检待复核：${qaPending} 条`,
      sub: 'AI 初检已完成，等待人工复核确认',
      path: '/modules/qa',
    })
  }
  const alertPending = qaOverview.value?.alertPending ?? 0
  if (alertPending > 0) {
    rows.push({
      icon: 'alert',
      tone: 'amber',
      title: `实时质检告警待处理：${alertPending} 条`,
      sub: `严重告警 ${qaOverview.value?.alertSevere ?? 0} 条，边聊边检命中`,
      path: '/modules/qa/alerts',
    })
  }
  const queuing = data.value?.queuingSessions ?? 0
  if (queuing > 0) {
    rows.push({
      icon: 'queue',
      tone: 'blue',
      title: `排队等待接待：${queuing} 个会话`,
      sub: `在线坐席 ${data.value?.onlineAgents ?? 0} 人，尽快接入或调整路由`,
      path: '/modules/workspace',
    })
  }
  return rows
})

const issueRows = computed(() => {
  const list = data.value?.intents ?? []
  const max = Math.max(1, ...list.map((item) => item.count))
  return list.slice(0, 5).map((item) => ({
    name: item.name,
    count: item.count,
    width: Math.round((item.count / max) * 100),
  }))
})

function priorityClass(priority: number): string {
  return { 4: 'b-red', 3: 'b-amber', 2: 'b-blue', 1: 'b-gray' }[priority] ?? 'b-gray'
}

function statusBadgeClass(status: number): string {
  // 1-待处理、2-处理中、3-待客户确认、4-已解决、5-已关闭
  return { 1: 'b-amber', 2: 'b-blue', 3: 'b-gray', 4: 'b-green', 5: 'b-green' }[status] ?? 'b-gray'
}

/** SLA 徽标：原型是 ok / warn / danger 三档 */
function slaClass(slaState: number): string {
  return { 3: 'danger', 2: 'warn', 1: 'ok' }[slaState] ?? 'ok'
}

function slaText(row: TicketItem): string {
  if (row.slaState === 3) return row.remainMinutes != null ? `已超时 ${Math.abs(row.remainMinutes)} 分` : '已超时'
  if (row.remainMinutes != null) return `剩余 ${row.remainMinutes} 分`
  return row.slaState === 2 ? '即将超时' : '正常'
}

function openTicket(ticketNo: string) {
  void router.push({ path: '/modules/tickets', query: { ticketNo } })
}

function newTicket() {
  void router.push({ path: '/modules/tickets', query: { create: '1' } })
}

function onToday() {
  ElMessage.info('已切换至今日实时数据')
  void load()
}

async function loadTickets() {
  try {
    const [overview, access] = await Promise.all([fetchTicketOverview(), fetchTicketAccess()])
    ticketOverview.value = overview
    ticketCanOperate.value = access.canOperate
  } catch {
    // 工单接口不可用不影响首页其它卡片
  }
  try {
    // 首页只挑"还在办"的工单看：取最近 30 条，前面再按 SLA 排
    const page = await fetchTickets({ ticketType: 1, page: 1, pageSize: 30 })
    pendingTickets.value = page.list.filter((item) => item.status !== 4 && item.status !== 5)
  } catch {
    pendingTickets.value = []
  }
}

async function loadQa() {
  try {
    qaOverview.value = await fetchQaOverview()
  } catch {
    qaOverview.value = null
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const access = await fetchMetricsAccess()
    canViewMetrics.value = access.canView !== false
    if (!canViewMetrics.value) {
      data.value = null
      return
    }
    data.value = await fetchRealtime()
    await Promise.all([loadTickets(), loadQa()])
  } catch (e) {
    // 接口不可用（后端没重启时是 404）不等于没权限：按"加载失败"提示，不要静默空着
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
  // 首页不需要秒级刷新，15 秒一次够用（大屏另有 5 秒轮询的实时大屏页）
  timer = window.setInterval(load, 15000)
})

onUnmounted(() => {
  if (timer) {
    window.clearInterval(timer)
  }
})
</script>
<!--
  配色来源：原型 css/design-system.css 的 token（--text-1/2/3、--border、--blue-soft…）与
  js/app.js 的 Chart.line（品牌渐变面板 #2563eb → #0ea5e9 + 白色曲线）。
  为了不和 Element Plus 的变量撞名，这里统一用 --ds-* 前缀声明在本页根节点上。
-->
<style scoped>
.dashboard {
  --ds-bg: #f5f6f8;
  --ds-surface: #ffffff;
  --ds-surface-2: #fafbfc;
  --ds-text-1: #0f172a;
  --ds-text-2: #475569;
  --ds-text-3: #94a3b8;
  --ds-border: #e6e8ef;
  --ds-border-strong: #d4d8e0;
  --ds-border-soft: #f1f3f6;
  --ds-primary: #2563eb;
  --ds-primary-strong: #1d4ed8;
  --ds-primary-soft: #eff6ff;
  --ds-green: #10b981;
  --ds-green-soft: #e7f8f0;
  --ds-amber: #f59e0b;
  --ds-amber-soft: #fef4e6;
  --ds-red: #ef4444;
  --ds-red-soft: #feecec;
  --ds-blue: #3b82f6;
  --ds-blue-soft: #eaf2fe;
  --ds-sky: #0ea5e9;
  --ds-sky-soft: #e0f2fe;
  --ds-cyan: #06b6d4;
  --ds-cyan-soft: #e6f9fc;
  --ds-r-lg: 12px;
  --ds-shadow-xs: 0 1px 2px rgba(15, 23, 42, 0.04);
  --ds-shadow-md: 0 6px 18px -6px rgba(15, 23, 42, 0.12);

  width: 100%;
  color: var(--ds-text-1);
}

.dashboard :deep(.el-button--primary) { background: var(--ds-primary); border-color: var(--ds-primary); }
.dashboard :deep(.el-button--primary:hover) { background: var(--ds-primary-strong); border-color: var(--ds-primary-strong); }

/* 页头 */
.page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 18px; flex-wrap: wrap; }
.page-head h2 { margin: 0; font-size: 20px; font-weight: 700; letter-spacing: -0.2px; }
.page-head .sub { color: var(--ds-text-3); font-size: 12.5px; margin-top: 3px; }
.page-head-actions { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.btn-icon { margin-right: 4px; }
.mt-18 { margin-top: 18px; }
.alert-tip { margin-top: 4px; font-size: 12px; line-height: 1.8; }
.alert-tip code { padding: 1px 5px; border-radius: 4px; background: #fff; color: #dc2626; font-size: 12px; }

/* KPI 卡（原型 .kpi） */
.kpi-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 14px; margin-bottom: 18px; }
.kpi {
  position: relative;
  overflow: hidden;
  background: var(--ds-surface);
  border: 1px solid var(--ds-border);
  border-radius: var(--ds-r-lg);
  box-shadow: var(--ds-shadow-xs);
  padding: 16px 16px 14px;
  transition: box-shadow .18s cubic-bezier(.4, 0, .2, 1), transform .18s cubic-bezier(.4, 0, .2, 1);
}
.kpi:hover { box-shadow: var(--ds-shadow-md); transform: translateY(-1px); }
.kpi::after {
  content: '';
  position: absolute;
  right: -24px;
  top: -24px;
  width: 76px;
  height: 76px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(59, 130, 246, .08), transparent 70%);
}
.k-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.k-label { font-size: 12.5px; color: var(--ds-text-2); font-weight: 500; }
.k-value { font-size: 26px; font-weight: 700; letter-spacing: -.5px; line-height: 1.1; color: var(--ds-text-1); }
.k-value small { font-size: 13px; font-weight: 600; color: var(--ds-text-3); margin-left: 2px; }
.k-foot { display: flex; align-items: center; gap: 6px; margin-top: 8px; font-size: 12px; color: var(--ds-text-3); }
.spark { margin-left: auto; width: 62px; height: 22px; }
.spark svg { width: 100%; height: 100%; }

/* 图标底色（原型 .k-icon 六种色调） */
.k-icon { width: 36px; height: 36px; border-radius: 10px; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.k-icon.blue { background: var(--ds-blue-soft); color: var(--ds-blue); }
.k-icon.green { background: var(--ds-green-soft); color: var(--ds-green); }
.k-icon.amber { background: var(--ds-amber-soft); color: var(--ds-amber); }
.k-icon.red { background: var(--ds-red-soft); color: var(--ds-red); }
.k-icon.purple { background: var(--ds-sky-soft); color: var(--ds-sky); }
.k-icon.cyan { background: var(--ds-cyan-soft); color: var(--ds-cyan); }

/* 涨跌胶囊（原型 .delta） */
.delta { display: inline-flex; align-items: center; gap: 3px; font-size: 11.5px; font-weight: 600; padding: 2px 7px; border-radius: 999px; }
.delta.up { background: var(--ds-green-soft); color: var(--ds-green); }
.delta.down { background: var(--ds-red-soft); color: var(--ds-red); }
.delta.flat { background: var(--ds-border-soft); color: var(--ds-text-3); }

/* 12 栅格与卡片（原型 .card / .card-head / .card-body） */
.grid-12 { display: grid; grid-template-columns: repeat(12, 1fr); gap: 16px; }
.card { display: flex; flex-direction: column; overflow: hidden; background: var(--ds-surface); border: 1px solid var(--ds-border); border-radius: var(--ds-r-lg); box-shadow: var(--ds-shadow-xs); }
.col-8 { grid-column: span 8; }
.col-5 { grid-column: span 5; }
.col-4 { grid-column: span 4; }
.col-3 { grid-column: span 3; }
.card-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 15px 18px; border-bottom: 1px solid var(--ds-border-soft); }
.card-title { font-size: 14.5px; font-weight: 600; color: var(--ds-text-1); }
.card-sub { font-size: 12px; color: var(--ds-text-3); margin-top: 2px; }
.card-body { flex: 1; display: flex; flex-direction: column; padding: 18px; }

/* 图例（原型 .legend） */
.legend { display: flex; gap: 14px; flex-wrap: wrap; font-size: 12px; color: var(--ds-text-2); }
.legend.center { justify-content: center; margin-top: 12px; }
.legend .li { display: flex; align-items: center; gap: 6px; }
.legend .sw { width: 9px; height: 9px; border-radius: 3px; flex-shrink: 0; }
.legend .num { color: var(--ds-text-1); font-weight: 600; }
.sw-total { background: #fff; border: 1px solid #c7d2fe; }
.sw-bot { background: #bfe3ff; border: 1px solid #c7d2fe; }

/* 徽标 / 状态点（原型 .badge / .dot） */
.badge { display: inline-flex; align-items: center; gap: 4px; height: 21px; padding: 0 8px; border-radius: 999px; font-size: 11.5px; font-weight: 600; line-height: 1; white-space: nowrap; }
.b-blue { background: var(--ds-blue-soft); color: #2563eb; }
.b-green { background: var(--ds-green-soft); color: #059669; }
.b-amber { background: var(--ds-amber-soft); color: #b45309; }
.b-red { background: var(--ds-red-soft); color: #dc2626; }
.b-gray { background: var(--ds-border-soft); color: var(--ds-text-2); }
.dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; flex-shrink: 0; }
.dot.green { background: var(--ds-green); color: var(--ds-green); }
.dot.red { background: var(--ds-red); color: var(--ds-red); }
.dot.pulse { position: relative; }
.dot.pulse::after { content: ''; position: absolute; inset: -3px; border-radius: 50%; background: currentColor; opacity: .35; animation: ds-pulse 1.8s infinite; }
@keyframes ds-pulse {
  0% { transform: scale(.6); opacity: .5; }
  70% { transform: scale(1.5); opacity: 0; }
  100% { opacity: 0; }
}

/* 图表：面板就是 SVG 里的品牌渐变矩形，网格/刻度都是白色半透明（原型 Chart.line） */
.chart { display: block; width: 100%; height: auto; }
.chart .grid line { stroke: rgba(255, 255, 255, .2); stroke-width: 1; }
.chart .grid text, .chart .x-labels text { font-size: 10.5px; fill: rgba(255, 255, 255, .82); }

/* 环形图（原型 Charts.donut：120 x 120，r=44，描边 15） */
.donut { position: relative; width: 120px; height: 120px; margin: 0 auto; }
.donut svg { width: 120px; height: 120px; }
.donut-center { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.donut-value { font-size: 19px; font-weight: 700; color: var(--ds-text-1); }
.donut-label { font-size: 10.5px; color: var(--ds-text-3); }

/* 坐席实时状态（原型 .rank-row + .avatar.a-30 + .st） */
.rank-row { display: flex; align-items: center; gap: 12px; padding: 9px 0; border-bottom: 1px solid var(--ds-border-soft); }
.rank-row:last-child { border-bottom: none; }
.avatar { display: inline-flex; align-items: center; justify-content: center; border-radius: 50%; color: #fff; font-weight: 600; letter-spacing: .5px; position: relative; flex-shrink: 0; }
.a-30 { width: 30px; height: 30px; font-size: 11.5px; }
.avatar .st { position: absolute; bottom: 0; right: 0; width: 9px; height: 9px; border-radius: 50%; border: 2px solid #fff; background: var(--ds-green); }
.avatar .st.online { background: var(--ds-green); }
.avatar .st.busy { background: var(--ds-amber); }
.avatar .st.off { background: #cbd5e1; }
.av-1 { background: linear-gradient(135deg, #3b82f6, #0ea5e9); }
.av-2 { background: linear-gradient(135deg, #3b82f6, #06b6d4); }
.av-3 { background: linear-gradient(135deg, #10b981, #34d399); }
.av-4 { background: linear-gradient(135deg, #f59e0b, #f97316); }
.av-5 { background: linear-gradient(135deg, #ec4899, #f43f5e); }
.av-6 { background: linear-gradient(135deg, #14b8a6, #0ea5e9); }
.av-7 { background: linear-gradient(135deg, #0ea5e9, #38bdf8); }
.av-8 { background: linear-gradient(135deg, #64748b, #475569); }
.rank-main { flex: 1; min-width: 0; }
.rank-name { font-size: 13px; font-weight: 600; color: var(--ds-text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rank-sub { font-size: 11.5px; color: var(--ds-text-3); margin-top: 1px; }
.rank-right { text-align: right; }
.rank-num { font-size: 13px; font-weight: 600; color: var(--ds-text-1); }
.rank-num span { font-size: 11px; font-weight: 400; color: var(--ds-text-3); }
.rank-csat { font-size: 11px; color: var(--ds-amber); margin-top: 1px; }
.rank-csat.good { color: var(--ds-green); }

/* 动态 / 待办列表（原型 .list-item） */
.list-item { display: flex; align-items: center; gap: 12px; padding: 11px 0; border-bottom: 1px solid var(--ds-border-soft); cursor: pointer; }
.list-item:last-child { border-bottom: none; }
.li-main { flex: 1; min-width: 0; font-size: 12.5px; color: var(--ds-text-2); }
.li-title { font-size: 13px; font-weight: 600; color: var(--ds-text-1); }
.li-sub { font-size: 12px; color: var(--ds-text-3); margin-top: 1px; }
.li-time { font-size: 11px; color: var(--ds-text-3); flex-shrink: 0; }
.li-arrow { color: var(--ds-text-3); }

/* TOP 高频问题（原型 .stat-line + .rank-pos + .progress） */
.stat-line { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 9px 0; font-size: 13px; border-bottom: 1px dashed var(--ds-border-soft); }
.stat-line:last-child { border-bottom: none; }
.sl-name { display: flex; align-items: center; gap: 8px; color: var(--ds-text-2); min-width: 0; }
.sl-right { display: inline-flex; align-items: center; gap: 10px; }
.sl-val { font-weight: 600; color: var(--ds-text-1); }
.rank-pos { width: 26px; height: 26px; border-radius: 8px; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 700; background: var(--ds-border-soft); color: var(--ds-text-3); flex-shrink: 0; }
.rank-pos.r1 { background: #fef3c7; color: #b45309; }
.rank-pos.r2 { background: #e2e8f0; color: #475569; }
.rank-pos.r3 { background: #fde8d7; color: #c2410c; }
.progress { display: inline-block; width: 104px; height: 6px; background: var(--ds-border-soft); border-radius: 999px; overflow: hidden; }
.progress-bar { display: block; height: 100%; border-radius: 999px; background: var(--ds-primary); transition: width .6s cubic-bezier(.22, 1, .36, 1); }
.progress-bar.amber { background: var(--ds-amber); }
.progress-bar.red { background: var(--ds-red); }

/* 待办工单表格（原型 .table） */
.table-wrap { overflow: auto; }
.table { width: 100%; border-collapse: collapse; font-size: 13px; }
.table th { text-align: left; padding: 10px 16px; font-size: 11.5px; font-weight: 600; color: var(--ds-text-3); background: var(--ds-surface-2); border-bottom: 1px solid var(--ds-border); white-space: nowrap; }
.table td { padding: 11px 16px; border-bottom: 1px solid var(--ds-border-soft); vertical-align: middle; }
.table tbody tr { transition: background .18s cubic-bezier(.4, 0, .2, 1); }
.table tbody tr:hover { background: #f8fafc; }
.table tbody tr:last-child td { border-bottom: none; }
.table .cell-main { font-weight: 600; color: var(--ds-text-1); }
.table .cell-sub { font-size: 12px; color: var(--ds-text-3); margin-top: 1px; }
.table .row-click { cursor: pointer; }
.sla { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-weight: 600; padding: 2px 7px; border-radius: 999px; white-space: nowrap; }
.sla.ok { background: var(--ds-green-soft); color: #059669; }
.sla.warn { background: var(--ds-amber-soft); color: #b45309; }
.sla.danger { background: var(--ds-red-soft); color: #dc2626; }
.btn-ghost-sm { display: inline-flex; align-items: center; gap: 4px; height: 28px; padding: 0 10px; border: none; background: transparent; border-radius: 7px; font-size: 12px; color: var(--ds-text-2); cursor: pointer; }
.btn-ghost-sm:hover { background: var(--ds-border-soft); color: var(--ds-text-1); }

/* 窄屏：KPI 与栅格逐级塌陷，别挤成一团 */
@media (max-width: 1440px) {
  .kpi-grid { grid-template-columns: repeat(3, 1fr); }
  .col-8, .col-5, .col-4 { grid-column: span 6; }
  .col-3 { grid-column: span 4; }
}
@media (max-width: 1100px) {
  .kpi-grid { grid-template-columns: repeat(2, 1fr); }
  .col-8, .col-5, .col-4, .col-3 { grid-column: span 12; }
}
</style>
