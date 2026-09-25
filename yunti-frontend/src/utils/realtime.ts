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
