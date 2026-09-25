import { request } from '../request'
import { getTenantCode } from '../../utils/auth'

export interface BotIntent {
  id: string
  intentCode: string
  name: string
  status: number
  confidence?: number | null
  hitCount: number
  samples?: string
  updateTime?: string
}

export interface BotSetting {
  botName: string
  welcomeMessage?: string
  fallbackMessage?: string
  transferPrompt?: string
  isEnabled: boolean
  modelKey?: string
  temperature: number
}

export interface BotModel {
  id: string
  modelKey: string
  modelName: string
  provider: string
  modelType: number
  temperature: number
  isEnabled: boolean
}

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'
const INTENTS_KEY = 'yunti_mock_bot_intents'
const SETTING_KEY = 'yunti_mock_bot_setting'
function mockStorageKey(base: string) { return `${base}:${getTenantCode() || 'PLATFORM'}` }
const MODELS: BotModel[] = [
  { id: '1', modelKey: 'qwen-plus', modelName: '千问 Plus', provider: 'qwen', modelType: 1, temperature: 0.35, isEnabled: true },
  { id: '2', modelKey: 'deepseek-chat', modelName: 'DeepSeek Chat', provider: 'deepseek', modelType: 1, temperature: 0.3, isEnabled: true },
  { id: '3', modelKey: 'qwen-vl-plus', modelName: '千问 VL', provider: 'qwen', modelType: 2, temperature: 0.2, isEnabled: true },
]
const DEFAULT_SETTING: BotSetting = {
  botName: '小云', welcomeMessage: '您好，我是云梯智能客服小云。',
  fallbackMessage: '抱歉，我暂时无法准确回答您的问题。', transferPrompt: '如需人工客服，请回复“转人工”。',
  isEnabled: true, modelKey: 'qwen-plus', temperature: 0.35,
}

function readIntents(): BotIntent[] {
  try { return JSON.parse(localStorage.getItem(mockStorageKey(INTENTS_KEY)) || '[]') as BotIntent[] } catch { return [] }
}

function writeIntents(intents: BotIntent[]) { localStorage.setItem(mockStorageKey(INTENTS_KEY), JSON.stringify(intents)) }

function readSetting(): BotSetting {
  try { return { ...DEFAULT_SETTING, ...JSON.parse(localStorage.getItem(mockStorageKey(SETTING_KEY)) || '{}') } as BotSetting }
  catch { return { ...DEFAULT_SETTING } }
}

export function listBotIntents(): Promise<BotIntent[]> {
  if (USE_MOCK) return Promise.resolve(readIntents())
  return request({ url: '/ai/v1/bot/intents', method: 'get' })
}

export function createBotIntent(data: { name: string; samples?: string }): Promise<BotIntent> {
  if (USE_MOCK) {
    const intent: BotIntent = {
      id: String(Date.now()), intentCode: `IT${Date.now()}`, name: data.name,
      samples: data.samples, status: 1, confidence: null, hitCount: 0,
    }
    writeIntents([intent, ...readIntents()])
    return Promise.resolve(intent)
  }
  return request({ url: '/ai/v1/bot/intents', method: 'post', data })
}

export function updateBotIntent(id: string, data: { name?: string; samples?: string; status?: number }): Promise<void> {
  if (USE_MOCK) {
    writeIntents(readIntents().map((intent) => intent.id === id ? { ...intent, ...data } : intent))
    return Promise.resolve()
  }
  return request({ url: `/ai/v1/bot/intents/${id}`, method: 'put', data })
}

export function getBotSetting(): Promise<BotSetting> {
  if (USE_MOCK) return Promise.resolve(readSetting())
  return request({ url: '/ai/v1/bot/settings', method: 'get' })
}

export function saveBotSetting(data: BotSetting): Promise<BotSetting> {
  if (USE_MOCK) {
    const setting = { ...readSetting(), ...data }
    localStorage.setItem(mockStorageKey(SETTING_KEY), JSON.stringify(setting))
    return Promise.resolve(setting)
  }
  return request({ url: '/ai/v1/bot/settings', method: 'put', data })
}

export function listBotModels(): Promise<BotModel[]> {
  if (USE_MOCK) return Promise.resolve(MODELS)
  return request({ url: '/ai/v1/bot/models', method: 'get' })
}

export function selectBotModel(data: { modelKey: string; temperature: number }): Promise<void> {
  if (USE_MOCK) {
    localStorage.setItem(mockStorageKey(SETTING_KEY), JSON.stringify({ ...readSetting(), ...data }))
    return Promise.resolve()
  }
  return request({ url: '/ai/v1/bot/model', method: 'put', data })
}
