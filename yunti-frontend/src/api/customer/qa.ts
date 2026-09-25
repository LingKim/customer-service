import { request } from '../request'

const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'
const TASKS_KEY = 'yunti_mock_qa_tasks'
const RULES_KEY = 'yunti_mock_qa_rules'

export interface QaOverview {
  total: number
  pending: number
  passed: number
  rejected: number
  avgAiScore: number
  avgReviewScore: number
  passRate: number
  riskLow: number
  riskMid: number
  riskHigh: number
}

export interface QaTaskItem {
  id: string
  taskNo: string
  sessionName: string
  agentName: string
  aiScore?: number
  reviewScore?: number
  riskLevel: number
  riskText: string
  status: number
  statusText: string
  createTime?: string
  reviewTime?: string
  aiComment?: string
  ruleNames: string[]
  aiSource?: string
}

export interface RuleResult {
  name: string
  type: string
  pass: boolean
  reason: string
}

export interface QaTaskDetail extends QaTaskItem {
  ruleResults: RuleResult[]
  reviewerId?: string
}

export interface QaRuleItem {
  id: string
  ruleName: string
  ruleType: number
  ruleTypeText: string
  ruleContent?: string
  weight: number
  enabled: boolean
  updateTime?: string
}

export interface QaScanResult {
  created: number
  taskNos: string[]
}

export function fetchQaOverview(): Promise<QaOverview> {
  if (USE_MOCK) {
    const tasks = mockTasks()
    const passed = tasks.filter((task) => task.status === 2).length
    return Promise.resolve({
      total: tasks.length, pending: tasks.filter((task) => task.status === 1).length,
      passed, rejected: tasks.filter((task) => task.status === 3).length,
      avgAiScore: average(tasks.map((task) => task.aiScore)),
      avgReviewScore: average(tasks.map((task) => task.reviewScore)),
      passRate: tasks.length ? Math.round(passed / tasks.length * 10000) / 100 : 0,
      riskLow: tasks.filter((task) => task.riskLevel === 1).length,
      riskMid: tasks.filter((task) => task.riskLevel === 2).length,
      riskHigh: tasks.filter((task) => task.riskLevel === 3).length,
    })
  }
  return request<QaOverview>({ url: '/customer/qa/overview', method: 'get' })
}

export function fetchQaTasks(params: { status?: number; keyword?: string }): Promise<QaTaskItem[]> {
  if (USE_MOCK) {
    const keyword = params.keyword?.trim().toLowerCase() || ''
    return Promise.resolve(mockTasks().filter((task) =>
      (!params.status || task.status === params.status)
      && (!keyword || `${task.taskNo} ${task.sessionName} ${task.agentName}`.toLowerCase().includes(keyword))))
  }
  return request<QaTaskItem[]>({ url: '/customer/qa/tasks', method: 'get', params })
}

export function fetchQaTaskDetail(taskNo: string): Promise<QaTaskDetail> {
  if (USE_MOCK) {
    const task = mockTasks().find((item) => item.taskNo === taskNo)
    return task ? Promise.resolve(task) : Promise.reject(new Error('任务不存在'))
  }
  return request<QaTaskDetail>({ url: `/customer/qa/tasks/${taskNo}`, method: 'get' })
}

export function scanQaTasks(): Promise<QaScanResult> {
  if (USE_MOCK) {
    const names = ['演示会话 · 订单咨询', '演示会话 · 售后反馈', '演示会话 · 投诉处理']
    const created = names.map((name, index) => makeMockTask(name, ['客服甲', '客服乙', '客服丙'][index], 92 - index * 12, index + 1))
    saveTasks([...created, ...mockTasks()])
    return Promise.resolve({ created: created.length, taskNos: created.map((task) => task.taskNo) })
  }
  return request<QaScanResult>({ url: '/customer/qa/scan', method: 'post' })
}

export function createManualQaTask(data: {
  sessionName: string
  agentName: string
  aiScore?: number
  riskLevel?: number
  comment?: string
  ruleNames?: string[]
  transcript?: string
}): Promise<QaTaskItem> {
  if (USE_MOCK) {
    const task = makeMockTask(data.sessionName, data.agentName, data.aiScore ?? 80, data.riskLevel ?? 1, data.comment)
    saveTasks([task, ...mockTasks()])
    return Promise.resolve(task)
  }
  return request<QaTaskItem>({ url: '/customer/qa/tasks/manual', method: 'post', data })
}

export function reviewQaTask(
  taskNo: string,
  data: { action: number; score?: number; comment?: string },
): Promise<QaTaskItem> {
  if (USE_MOCK) {
    const tasks = mockTasks()
    const task = tasks.find((item) => item.taskNo === taskNo)
    if (!task) return Promise.reject(new Error('任务不存在'))
    task.status = data.action === 1 ? 2 : data.action === 2 ? 3 : 1
    task.statusText = task.status === 2 ? '已通过' : task.status === 3 ? '已驳回' : '待复核'
    task.reviewScore = data.action === 3 ? undefined : data.score
    task.reviewTime = new Date().toISOString()
    saveTasks(tasks)
    return Promise.resolve(task)
  }
  return request<QaTaskItem>({ url: `/customer/qa/tasks/${taskNo}/review`, method: 'post', data })
}

export interface BatchReviewResult {
  processed: number
  taskNos: string[]
}

export function batchReviewQaTasks(
  taskNos: string[],
  data: { action: number; score?: number; comment?: string },
): Promise<BatchReviewResult> {
  if (USE_MOCK) return Promise.all(taskNos.map((taskNo) => reviewQaTask(taskNo, data)))
    .then(() => ({ processed: taskNos.length, taskNos }))
  return request<BatchReviewResult>({ url: '/customer/qa/tasks/batch-review', method: 'post', data: { taskNos, ...data } })
}

export function batchAiQaTasks(taskNos: string[]): Promise<BatchReviewResult> {
  if (USE_MOCK) {
    const tasks = mockTasks()
    for (const task of tasks.filter((item) => taskNos.includes(item.taskNo))) {
      task.aiScore = 72 + task.taskNo.length % 26
      task.status = 1
      task.statusText = '待复核'
      task.reviewScore = undefined
      task.aiSource = 'demo'
    }
    saveTasks(tasks)
    return Promise.resolve({ processed: taskNos.length, taskNos })
  }
  return request<BatchReviewResult>({ url: '/customer/qa/tasks/batch-ai', method: 'post', data: { taskNos } })
}

export function fetchQaRules(): Promise<QaRuleItem[]> {
  if (USE_MOCK) return Promise.resolve(mockRules())
  return request<QaRuleItem[]>({ url: '/customer/qa/rules', method: 'get' })
}

export function saveQaRule(data: {
  id?: string
  ruleName: string
  ruleType: number
  ruleContent?: string
  weight: number
  enabled: boolean
}): Promise<QaRuleItem> {
  if (USE_MOCK) {
    const rules = mockRules()
    const item: QaRuleItem = { ...data, id: data.id || String(Date.now()),
      ruleTypeText: ['敏感词', '承诺规范', '必答项', '情绪识别'][data.ruleType - 1] || '其他',
      updateTime: new Date().toISOString() }
    saveRules(data.id ? rules.map((rule) => rule.id === data.id ? item : rule) : [...rules, item])
    return Promise.resolve(item)
  }
  return request<QaRuleItem>({ url: '/customer/qa/rules', method: 'post', data })
}

export function deleteQaRule(id: string): Promise<void> {
  if (USE_MOCK) { saveRules(mockRules().filter((rule) => rule.id !== id)); return Promise.resolve() }
  return request<void>({ url: `/customer/qa/rules/${id}`, method: 'delete' })
}

function readList<T>(key: string): T[] {
  try { return JSON.parse(localStorage.getItem(key) || '[]') as T[] } catch { return [] }
}

function mockTasks(): QaTaskDetail[] { return readList<QaTaskDetail>(TASKS_KEY) }
function saveTasks(tasks: QaTaskDetail[]): void { localStorage.setItem(TASKS_KEY, JSON.stringify(tasks)) }
function saveRules(rules: QaRuleItem[]): void { localStorage.setItem(RULES_KEY, JSON.stringify(rules)) }

function mockRules(): QaRuleItem[] {
  const stored = localStorage.getItem(RULES_KEY)
  if (stored !== null) return readList<QaRuleItem>(RULES_KEY)
  const rules: QaRuleItem[] = [
    { id: '1', ruleName: '敏感词与禁语', ruleType: 1, ruleTypeText: '敏感词', ruleContent: '检查不礼貌用语', weight: 25, enabled: true },
    { id: '2', ruleName: '服务承诺规范', ruleType: 2, ruleTypeText: '承诺规范', ruleContent: '承诺需明确时间', weight: 25, enabled: true },
    { id: '3', ruleName: '必答项完整', ruleType: 3, ruleTypeText: '必答项', ruleContent: '核实关键信息', weight: 25, enabled: true },
    { id: '4', ruleName: '情绪安抚', ruleType: 4, ruleTypeText: '情绪识别', ruleContent: '客户不满时先安抚', weight: 25, enabled: true },
  ]
  saveRules(rules)
  return rules
}

function average(values: Array<number | undefined>): number {
  const present = values.filter((value): value is number => value !== undefined)
  return present.length ? Math.round(present.reduce((sum, value) => sum + value, 0) / present.length * 100) / 100 : 0
}

function makeMockTask(sessionName: string, agentName: string, score: number, riskLevel: number, comment = '演示评分，请结合会话人工复核'): QaTaskDetail {
  const id = String(Date.now()) + Math.random().toString(36).slice(2, 6)
  return {
    id, taskNo: `QA${id}`, sessionName, agentName, aiScore: score,
    riskLevel, riskText: ['低风险', '中风险', '高风险'][riskLevel - 1],
    status: 1, statusText: '待复核', createTime: new Date().toISOString(),
    aiComment: comment, ruleNames: [], ruleResults: [],
    aiSource: 'demo',
  }
}
