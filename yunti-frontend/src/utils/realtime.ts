/**
 * 实时通信客户端：长连接、心跳、断线重连、发送确认。
 *
 * 和后端的约定见 yunti-realtime-service：
 * - 连接：ws://host:9096/ws/realtime?token=xxx（坐席用登录令牌，访客用访客令牌）
 * - 心跳：客户端 30 秒发一次 PING，服务端回 PONG，超时判定掉线
 * - 可靠：每条消息带 clientMsgNo，服务端落库后回 ACK；没等到 ACK 的消息重连后自动重发
 * - 下行：CONNECTED / JOINED / ACK / MESSAGE / HISTORY / SESSION / PRESENCE / AGENTS / QUEUE / ERROR
 * （QUEUE 是"会话列表有变化"的提醒，坐席工作台收到后重新拉一次列表）
 */

export type RealtimeState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface RealtimeMessage {
  type: string
  sessionNo?: string
  clientMsgNo?: string
  msgType?: number
  content?: string
  beforeId?: number
  /** 转接目标坐席 */
  toAgentId?: string
  /** 转接原因 / 结束小结 */
  remark?: string
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  data?: any
  serverTime?: number
  code?: number
  message?: string
}

/** 待确认的消息（发件箱条目）：断线、刷新后都要能重发 */
export interface OutboxItem {
  clientMsgNo: string
  sessionNo?: string
  content?: string
  attempts: number
  createdAt: number
}

export interface RealtimeClientOptions {
  /** 访问令牌：坐席用登录令牌，访客用访客令牌 */
  token: string
  /**
   * 每次建连前取一次令牌。
   *
   * <p>为什么要这个：令牌是有有效期的（登录令牌 12 小时，访客令牌 12 小时），
   * 而一个工作台标签页可能开一整天。如果只在 new RealtimeClient 时取一次，
   * 令牌过期后每次重连都用的是那份死令牌——服务端一直回"访问令牌无效或已过期"，
   * 客户端却只看到"连不上"，于是无限重连、界面还显示登录正常。</p>
   */
  tokenProvider?: () => string
  /** 收到服务端消息 */
  onMessage: (message: RealtimeMessage) => void
  /** 连接状态变化 */
  onStateChange?: (state: RealtimeState) => void
  /**
   * 判定令牌不可用时的回调（连续多次连不上，或服务端明确回了 40100）。
   *
   * <p>页面接到回调后去确认一次登录态即可：令牌真过期就按登录失效处理，
   * 服务没起/网络断则什么都不用做，继续重连。</p>
   */
  onAuthFailed?: (reason?: string) => void
  /** 心跳间隔（毫秒），默认 30 秒 */
  heartbeatMs?: number
  /** 单条消息等待 ACK 的超时（毫秒），默认 8 秒 */
  ackTimeoutMs?: number
  /** 发件箱变化（可用于展示"发送中/待重发"） */
  onOutboxChange?: (items: OutboxItem[]) => void
}

interface PendingMessage {
  payload: RealtimeMessage
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  resolve: (value: any) => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  reject: (reason?: any) => void
  timer: number
  attempts: number
}

const MAX_RECONNECT_DELAY = 15000
const BASE_RECONNECT_DELAY = 1000
/** 单条消息最多自动重发几次；之后交给页面提示用户手动重发（重发沿用同一个 clientMsgNo，不会重复） */
const MAX_SEND_ATTEMPTS = 6
/** 自动重发间隔 */
const RETRY_INTERVAL = 4000
/** 连续连不上几次之后，让页面去确认一次登录态（多用几次，避免网络抖动就误报） */
const AUTH_PROBE_AFTER_FAILURES = 3
/** 同一轮里最多多久提示一次"令牌可能失效"，免得刷屏 */
const AUTH_NOTIFY_COOLDOWN = 5 * 60 * 1000
/** 发件箱持久化 key：刷新页面也能把没确认的消息补发出去 */
const OUTBOX_KEY = 'yunti_realtime_outbox'
const OUTBOX_LIMIT = 50

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
  private retryTimer: number | undefined
  private readonly pending = new Map<string, PendingMessage>()
  /** 连续建连失败次数：成功一次就清零 */
  private connectFailures = 0
  /** 上次提示"令牌可能失效"的时间 */
  private authNotifiedAt = 0

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
    this.restoreOutbox()
    this.open()
  }

  /** 主动断开（离开页面时调用），不再自动重连 */
  close(): void {
    this.manualClosed = true
    this.clearTimers()
    this.socket?.close(1000, 'client close')
    this.socket = null
    this.pending.forEach((item) => {
      item.reject(new Error('连接已关闭'))
    })
    this.pending.clear()
    this.persistOutbox()
    this.notifyOutbox()
    this.setState('closed')
  }

  /**
   * 发送消息并等待服务端 ACK。
   *
   * <p>必达约定：消息先进入发件箱（localStorage 持久化），拿到 ACK 才出箱。
   * 期间断线、刷新、超时都不会把消息丢掉——重连后会用同一个 clientMsgNo 重发，
   * 服务端按幂等键去重，所以既能补发又不会重复。</p>
   */
  send(payload: Omit<RealtimeMessage, 'clientMsgNo'> & { clientMsgNo?: string }): Promise<RealtimeMessage> {
    // 允许调用方自带消息号：页面要把"本地乐观气泡"和"服务端回执"对上号
    // （ACK 迟到、断线重发这些情况下，只靠内部自增号，页面就只能干看着两个气泡）
    const clientMsgNo = payload.clientMsgNo || this.nextClientMsgNo()
    const message: RealtimeMessage = { ...payload, clientMsgNo }
    return new Promise<RealtimeMessage>((resolve, reject) => {
      this.pending.set(clientMsgNo, { payload: message, resolve, reject, timer: 0, attempts: 0 })
      this.persistOutbox()
      this.notifyOutbox()
      this.flush(clientMsgNo)
      this.scheduleRetry()
    })
  }

  /** 手动重发（页面上点"重发"时调用）：沿用原 clientMsgNo，服务端幂等去重 */
  retry(clientMsgNo: string): void {
    const item = this.pending.get(clientMsgNo)
    if (!item) {
      return
    }
    item.attempts = 0
    this.flush(clientMsgNo, item)
    this.scheduleRetry()
    this.notifyOutbox()
  }

  /** 当前发件箱（还没等到 ACK 的消息） */
  outbox(): OutboxItem[] {
    return [...this.pending.values()].map((item) => ({
      clientMsgNo: item.payload.clientMsgNo as string,
      sessionNo: item.payload.sessionNo,
      content: item.payload.content,
      attempts: item.attempts,
      createdAt: 0,
    }))
  }

  /** 不需要 ACK 的控制类消息（心跳、拉历史等） */
  post(payload: RealtimeMessage): void {
    this.rawSend(payload)
  }

  private open(): void {
    this.setState(this.reconnectAttempt === 0 ? 'connecting' : 'reconnecting')
    // 每次建连都重新取一次令牌：标签页开久了，建对象时那份早就过期了
    const token = (this.options.tokenProvider?.() || this.options.token || '').trim()
    if (!token) {
      // 连令牌都没有，重试多少次都没用，直接交给页面处理
      this.notifyAuthFailed('本地没有访问令牌')
      return
    }
    const url = `${resolveWsBase()}/ws/realtime?token=${encodeURIComponent(token)}`
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
      this.connectFailures = 0
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
          this.pending.delete(message.clientMsgNo)
          this.persistOutbox()
          this.notifyOutbox()
          item.resolve(message)
        }
      }
      // 服务端明确说令牌不行：立刻交给页面确认登录态，别等到重试次数堆满
      if (message.type === 'ERROR' && (message.code === 40100 || message.code === 40110)) {
        this.notifyAuthFailed(message.message)
      }
      this.options.onMessage(message)
    }

    socket.onclose = (event) => {
      this.stopHeartbeat()
      if (this.manualClosed) {
        this.setState('closed')
        return
      }
      this.connectFailures += 1
      // 握手被拒（401）时浏览器只给一个笼统的 1006，客户端分不清"令牌过期"和"服务没起"，
      // 所以这里不猜：连续失败几次就把问题抛给页面，由它用一次真实请求去确认登录态。
      if (this.connectFailures >= AUTH_PROBE_AFTER_FAILURES) {
        this.notifyAuthFailed(`连续 ${this.connectFailures} 次连接失败（close code=${event?.code ?? '-'}）`)
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
    target.attempts += 1
    this.rawSend(target.payload)
  }

  /**
   * 自动重发：只要还有没确认的消息，就按固定节奏补发一次。
   * 超过上限还没有 ACK 的，把 promise 置为失败，但**消息留在发件箱里**，
   * 后续重连仍会补发（服务端幂等，不会产生重复）。
   */
  private scheduleRetry(): void {
    if (this.retryTimer || this.manualClosed) {
      return
    }
    this.retryTimer = window.setInterval(() => {
      if (this.manualClosed) {
        this.stopRetry()
        return
      }
      if (!this.pending.size) {
        this.stopRetry()
        return
      }
      this.pending.forEach((item, clientMsgNo) => {
        if (this.socket?.readyState === WebSocket.OPEN) {
          this.flush(clientMsgNo, item)
        }
        if (item.attempts >= MAX_SEND_ATTEMPTS && item.timer === 0) {
          // 只通知一次失败，消息继续留在发件箱等网络恢复
          item.timer = 1
          item.reject(new Error('网络不稳定，消息已保留，恢复后会自动补发'))
          this.notifyOutbox()
        }
      })
    }, RETRY_INTERVAL)
  }

  private stopRetry(): void {
    if (this.retryTimer) {
      window.clearInterval(this.retryTimer)
      this.retryTimer = undefined
    }
  }

  /** 发件箱落盘：刷新页面后 still 能把没确认的消息补发出去 */
  private persistOutbox(): void {
    try {
      const items = this.outbox().slice(-OUTBOX_LIMIT)
      window.localStorage.setItem(OUTBOX_KEY, JSON.stringify(items))
    } catch {
      // 存储不可用（隐私模式等）时忽略，内存发件箱仍然有效
    }
  }

  /** 恢复发件箱：只恢复"还没确认"的消息，重新排队等待补发 */
  private restoreOutbox(): void {
    let items: OutboxItem[] = []
    try {
      items = JSON.parse(window.localStorage.getItem(OUTBOX_KEY) || '[]') as OutboxItem[]
    } catch {
      items = []
    }
    for (const item of items) {
      if (!item?.clientMsgNo || this.pending.has(item.clientMsgNo)) {
        continue
      }
      const payload: RealtimeMessage = {
        type: 'SEND',
        sessionNo: item.sessionNo,
        content: item.content,
        msgType: 1,
        clientMsgNo: item.clientMsgNo,
      }
      this.pending.set(item.clientMsgNo, {
        payload,
        resolve: () => undefined,
        reject: () => undefined,
        timer: 0,
        attempts: 0,
      })
    }
    if (items.length) {
      this.notifyOutbox()
    }
  }

  private notifyOutbox(): void {
    this.options.onOutboxChange?.(this.outbox())
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

  /**
   * 通知页面"令牌可能不可用"。
   *
   * <p>带冷却时间：连接一直失败时 onclose 会反复触发，不去重的话页面会被刷爆
   * （而且用户看到的提示会一直闪）。</p>
   */
  private notifyAuthFailed(reason?: string): void {
    const now = Date.now()
    if (now - this.authNotifiedAt < AUTH_NOTIFY_COOLDOWN) {
      return
    }
    this.authNotifiedAt = now
    this.options.onAuthFailed?.(reason)
  }

  private clearTimers(): void {
    this.stopHeartbeat()
    this.stopRetry()
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
