import { request } from '../request'

/**
 * 知识库体检（直连 yunti-ai）。
 *
 * <p>最该看的是 `chunkVectorModels` / `vectorQualityHint`：切片的向量是索引那一刻算出来的，
 * 如果当时向量化失败（密钥没配 / 401），库里存的就是没有语义的兜底向量，
 * 检索只能按字面匹配——"知识库明明有数据、机器人却答不上来"多半就是这一条。</p>
 */
export interface KbHealth {
  status: string
  engine?: string
  vectorStore?: string
  embeddingKeyConfigured?: boolean
  embeddingModel?: string
  /** 库里每种向量来源各有多少个切片，例如 [{ model: 'local-hash', chunks: 42 }] */
  chunkVectorModels?: { model: string; chunks: number }[]
  vectorQualityHint?: string
}

export function getKbHealth(): Promise<KbHealth> {
  return request<KbHealth>({ url: '/customer/kb/health', method: 'get', silent: true })
}
