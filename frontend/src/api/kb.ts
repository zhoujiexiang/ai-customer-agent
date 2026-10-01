import type { KbDocument, RagHit } from '@/types'
import { request } from './http'

export interface KbSearchResult {
  query: string
  topK: number
  threshold: number
  retrieveMs: number
  hits: RagHit[]
}

export function listDocuments(): Promise<KbDocument[]> {
  return request<KbDocument[]>('/api/kb/documents')
}

export function uploadDocument(file: File): Promise<KbDocument> {
  const form = new FormData()
  form.append('file', file)
  // 解析 + 切片 + 向量化是同步做的，大文档会慢，这里不设超时由浏览器默认处理
  return request<KbDocument>('/api/kb/documents', { method: 'POST', body: form })
}

export function deleteDocument(id: number): Promise<void> {
  return request<void>(`/api/kb/documents/${id}`, { method: 'DELETE' })
}

/** 只跑检索不进模型 —— 调参时唯一可靠的观测手段 */
export function searchKb(q: string, topK?: number): Promise<KbSearchResult> {
  const params = new URLSearchParams({ q })
  if (topK) {
    params.set('topK', String(topK))
  }
  return request<KbSearchResult>(`/api/kb/search?${params.toString()}`)
}
