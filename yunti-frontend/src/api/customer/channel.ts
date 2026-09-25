import { request } from '../request'
import { getTenantCode } from '../../utils/auth'

export interface ChannelResult {
  id: string
  channelId: string
  channelType: number
  name: string
  desc?: string
  status: number
  stage: number
  maskedKey?: string
  appKey?: string
  creatorName?: string
  createTime?: string
}

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'
const MOCK_KEY = 'yunti_mock_channels'

function mockStorageKey() { return `${MOCK_KEY}:${getTenantCode() || 'PLATFORM'}` }

function readMock(): ChannelResult[] {
  try { return JSON.parse(localStorage.getItem(mockStorageKey()) || '[]') as ChannelResult[] } catch { return [] }
}

function writeMock(channels: ChannelResult[]) {
  localStorage.setItem(mockStorageKey(), JSON.stringify(channels))
}

function randomSecret(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(24))
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}

export function listChannels(): Promise<ChannelResult[]> {
  if (USE_MOCK) return Promise.resolve(readMock().map((channel) => ({ ...channel, appKey: undefined })))
  return request({ url: '/customer/channels', method: 'get' })
}

export function createChannel(data: { channelType: number; name: string; desc?: string }): Promise<ChannelResult> {
  if (USE_MOCK) {
    const secret = randomSecret()
    const channel: ChannelResult = {
      id: String(Date.now()), channelId: `CH${crypto.randomUUID().replace(/-/g, '').slice(0, 30)}`,
      channelType: data.channelType, name: data.name, desc: data.desc,
      status: 1, stage: 3, appKey: secret, maskedKey: `${secret.slice(0, 6)}**********************${secret.slice(-4)}`,
      creatorName: '演示用户', createTime: new Date().toISOString(),
    }
    writeMock([channel, ...readMock()])
    return Promise.resolve(channel)
  }
  return request({ url: '/customer/channels', method: 'post', data })
}

export function revealChannelKey(channelId: string): Promise<string> {
  if (USE_MOCK) return Promise.resolve(readMock().find((c) => c.id === channelId)?.appKey || '')
  return request<{ appKey: string }>({ url: `/customer/channels/${channelId}/key`, method: 'get' }).then((result) => result.appKey)
}

export function rotateChannelKey(channelId: string): Promise<ChannelResult> {
  if (USE_MOCK) {
    const channels = readMock()
    const channel = channels.find((c) => c.id === channelId)
    if (!channel) return Promise.reject(new Error('渠道不存在'))
    const secret = randomSecret()
    channel.appKey = secret
    channel.maskedKey = `${secret.slice(0, 6)}**********************${secret.slice(-4)}`
    writeMock(channels)
    return Promise.resolve(channel)
  }
  return request({ url: `/customer/channels/${channelId}/key/rotate`, method: 'post' })
}

export function updateChannelStatus(channelId: string, enabled: boolean): Promise<ChannelResult> {
  if (USE_MOCK) {
    const channels = readMock()
    const channel = channels.find((c) => c.id === channelId)
    if (!channel) return Promise.reject(new Error('渠道不存在'))
    channel.status = enabled ? 1 : 2
    writeMock(channels)
    return Promise.resolve(channel)
  }
  return request({ url: `/customer/channels/${channelId}/status`, method: 'post', data: { enabled } })
}
