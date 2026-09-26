import { request } from '../request'

/** 知识库总览 */
export interface KbOverview {
  docTotal: number
  published: number
  draft: number
  indexed: number
  chunkTotal: number
  categoryCount: number
  /** AI 服务与向量库是否就绪 */
  vectorReady: boolean
}

/** 知识文档 */
export interface KbDocItem {
  id: string
  docNo: string
  categoryId?: string | null
  title: string
  summary?: string | null
  sourceType: number
  sourceText: string
  status: number
  statusText: string
  indexStatus: number
  indexStatusText: string
  indexMessage?: string | null
  chunkCount: number
  fileName?: string | null
  fileSize: number
  author?: string | null
  createTime?: string | null
  updateTime?: string | null
}

/** 文档切片 */
export interface KbChunkItem {
  id: string
  chunkNo: number
  content: string
  charCount: number
  tokenCount: number
  vectorModel?: string | null
  createTime?: string | null
}

/** 知识分类 */
export interface KbCategoryItem {
  id: string
  parentId: string
  name: string
  sortNo: number
  docCount: number
}

/** 检索命中的切片 */
export interface KbHitItem {
  chunkId: string
  docId: string
  docTitle?: string | null
  chunkNo: number
  content: string
  score: number
  vectorModel?: string | null
}

export interface KbSearchResult {
  query: string
  results: KbHitItem[]
  vectorReady: boolean
  /**
   * 检索模式：
   * vector-向量检索（真实语义）、keyword-local-vector-没配向量密钥退化成关键词、
   * keyword-向量无结果后兜底关键词、empty-空查询
   */
  mode?: string
}

export function fetchKbOverview(): Promise<KbOverview> {
  return request<KbOverview>({ url: '/customer/kb/overview', method: 'get' })
}

export function fetchKbDocuments(params: {
  categoryId?: string
  status?: number
  keyword?: string
} = {}): Promise<KbDocItem[]> {
  return request<KbDocItem[]>({ url: '/customer/kb/documents', method: 'get', params })
}

/** 正文来源：manual-手工录入、chunks-由切片拼回（文件导入型）、null-还没有正文 */
export type KbContentSource = 'manual' | 'chunks' | null

export function fetchKbDocumentDetail(
  id: string,
): Promise<{ document: KbDocItem; content?: string; contentSource?: KbContentSource }> {
  return request({ url: `/customer/kb/documents/${id}`, method: 'get' })
}

/** 新建文档（手工正文） */
export function createKbDocument(data: {
  title: string
  categoryId?: string | null
  content?: string
  summary?: string
}): Promise<KbDocItem> {
  return request<KbDocItem>({ url: '/customer/kb/documents', method: 'post', data })
}

export function updateKbDocument(
  id: string,
  data: { title?: string; categoryId?: string | null; content?: string; summary?: string },
): Promise<KbDocItem> {
  return request<KbDocItem>({ url: `/customer/kb/documents/${id}`, method: 'put', data })
}

/** 上传文件：文件进对象存储，内容由 AI 服务解析 + 切块 + 向量化 */
export function uploadKbDocument(file: File, categoryId?: string | null, title?: string): Promise<KbDocItem> {
  const form = new FormData()
  form.append('file', file)
  if (categoryId) {
    form.append('categoryId', categoryId)
  }
  if (title) {
    form.append('title', title)
  }
  // 注意：这里**不能**手写 Content-Type。
  // 手写成 'multipart/form-data' 会把 boundary 丢掉，服务端解析出来的文件是空的
  // （现象就是"上传成功但索引失败/文件是空的"）。交给浏览器自动带上 boundary。
  return request<KbDocItem>({
    url: '/customer/kb/documents/upload',
    method: 'post',
    data: form,
    timeout: 120000,
  })
}

export function reindexKbDocument(id: string): Promise<KbDocItem> {
  return request<KbDocItem>({ url: `/customer/kb/documents/${id}/reindex`, method: 'post', timeout: 120000 })
}

export function changeKbDocumentStatus(id: string, status: number): Promise<KbDocItem> {
  return request<KbDocItem>({ url: `/customer/kb/documents/${id}/status`, method: 'post', data: { status } })
}

export function deleteKbDocument(id: string): Promise<void> {
  return request<void>({ url: `/customer/kb/documents/${id}`, method: 'delete' })
}

export function fetchKbChunks(id: string): Promise<KbChunkItem[]> {
  return request<KbChunkItem[]>({ url: `/customer/kb/documents/${id}/chunks`, method: 'get' })
}

export function fetchKbCategories(): Promise<KbCategoryItem[]> {
  return request<KbCategoryItem[]>({ url: '/customer/kb/categories', method: 'get' })
}

export function createKbCategory(data: {
  name: string
  parentId?: string | null
  sortNo?: number
}): Promise<KbCategoryItem> {
  return request<KbCategoryItem>({ url: '/customer/kb/categories', method: 'post', data })
}

/** 检索测试 */
export function searchKb(data: { query: string; topK?: number }): Promise<KbSearchResult> {
  return request<KbSearchResult>({ url: '/customer/kb/search', method: 'post', data })
}

/** 引用来源：回答里的 [n] 对应这里第 n 条 */
export interface KbCitation {
  index: number
  chunkId: string
  docId: string
  docTitle: string
  chunkNo: number
  score: number
  content: string
}

/** 编排轨迹：这次是怎么查的 */
export interface KbAskStep {
  node: string
  detail: string
}

export interface KbAskResult {
  question: string
  answer: string
  /** 编排引擎：langgraph / linear */
  engine: string
  /** 检索口径：vector / keyword-local-vector / keyword */
  mode: string
  /** 召回是否够用 */
  enough: boolean
  citations: KbCitation[]
  steps: KbAskStep[]
}

/** 知识问答：让 AI 先查知识库再回答，并带回出处 */
export function askKb(data: { question: string; topK?: number }): Promise<KbAskResult> {
  return request<KbAskResult>({ url: '/customer/kb/ask', method: 'post', data, timeout: 120000 })
}
