/**
 * 与后端 SSE 事件协议一一对应的类型定义。
 * 后端 ChatService 推送什么，这里就应该有什么，改一边必须改另一边。
 */

export interface SessionEvent {
  sessionId: number
}

export interface RagHit {
  index: number
  docName: string
  chunkIndex: number
  score: number
  /** 分数是否达到阈值 —— 未达标的不进 Prompt，但照样展示，便于排查召回问题 */
  used: boolean
  snippet: string
}

export interface RagEvent {
  enabled: boolean
  query?: string
  retrieveMs: number
  topK: number
  threshold: number
  hitCount: number
  usedCount: number
  hits: RagHit[]
  error?: string
}

export interface ToolCallEvent {
  step: number
  name: string
  label: string
  write: boolean
  arguments: Record<string, unknown>
}

export interface ToolConfirmEvent {
  confirmId: string
  step: number
  name: string
  label: string
  arguments: Record<string, unknown>
  /** 后端拼好的人话描述，弹窗直接显示 */
  preview: string
  timeoutSeconds: number
}

export type ConfirmStatus = 'CONFIRMED' | 'CANCELLED' | 'TIMEOUT' | 'PENDING'

export interface ToolConfirmResultEvent {
  confirmId: string
  status: ConfirmStatus
  elapsedMs: number
}

export interface ToolResultEvent {
  step: number
  name: string
  label: string
  write: boolean
  success: boolean
  summary: string
  ms: number
  confirm: string
  data: Record<string, unknown>
}

export interface DoneEvent {
  messageId: number
  retrieveMs: number
  firstTokenMs: number
  llmMs: number
  totalMs: number
  promptTokens: number
  completionTokens: number
  citationCount: number
  toolCallCount: number
  agentSteps: number
}

export interface Citation {
  index: number
  /** 前端从 rag 事件构造时后端未下发 docId，故为可选 */
  docId?: number
  docName: string
  chunkIndex: number
  score: number
  snippet: string
}

export interface KbDocument {
  id: number
  name: string
  fileType: string
  fileSize: number
  chunkCount: number
  status: 'PARSING' | 'READY' | 'FAILED'
  createdAt: string
}

export interface ToolMeta {
  name: string
  label: string
  description: string
  write: boolean
  required: string[]
}

export interface StatusInfo {
  llmConfigured: boolean
  llmModel: string
  embeddingConfigured: boolean
  embeddingDimension: number
  ready: boolean
}

/** 前端自己维护的一条消息 */
export interface ChatMessageView {
  id: string
  role: 'USER' | 'ASSISTANT'
  content: string
  streaming?: boolean
  error?: string
  citations?: Citation[]
  done?: DoneEvent
}

/** 一次工具调用的前端轨迹（决策面板时间线用） */
export interface ToolTrace {
  step: number
  name: string
  label: string
  write: boolean
  arguments: Record<string, unknown>
  status: 'RUNNING' | 'WAITING_CONFIRM' | 'SUCCESS' | 'FAILED' | 'REJECTED'
  confirmStatus?: ConfirmStatus
  summary?: string
  ms?: number
  data?: Record<string, unknown>
}
