import type {
  DoneEvent,
  RagEvent,
  SessionEvent,
  StatusInfo,
  ToolCallEvent,
  ToolConfirmEvent,
  ToolConfirmResultEvent,
  ToolMeta,
  ToolResultEvent
} from '@/types'
import { request } from './http'

export interface StreamOptions {
  sessionId?: number | null
  question: string
  useRag?: boolean
  enableTools?: boolean
}

export interface StreamHandlers {
  onSession?: (data: SessionEvent) => void
  onRag?: (data: RagEvent) => void
  onToolCall?: (data: ToolCallEvent) => void
  onToolConfirm?: (data: ToolConfirmEvent) => void
  onToolConfirmResult?: (data: ToolConfirmResultEvent) => void
  onToolResult?: (data: ToolResultEvent) => void
  onDelta?: (text: string) => void
  onDone?: (data: DoneEvent) => void
  onError?: (message: string) => void
}

/**
 * 流式对话。
 * <p>
 * 用 POST + fetch + ReadableStream 而不是 EventSource：
 * EventSource 只支持 GET，请求体塞不进去，多轮上下文和工具开关都不好传。
 */
export async function streamChat(
  options: StreamOptions,
  handlers: StreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  const resp = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      sessionId: options.sessionId ?? null,
      question: options.question,
      useRag: options.useRag ?? true,
      enableTools: options.enableTools ?? true
    }),
    signal
  })

  if (!resp.ok) {
    throw new Error(`对话接口返回 HTTP ${resp.status}`)
  }
  if (!resp.body) {
    throw new Error('当前浏览器不支持流式响应')
  }

  const reader = resp.body.getReader()
  // stream: true 是必须的：一个中文字符会被 UTF-8 编成 3 个字节，
  // 很可能被切在两块 chunk 里，不开启流式解码就会得到乱码
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const dispatch = (raw: string) => {
    let event = 'message'
    const dataLines: string[] = []
    for (const line of raw.split('\n')) {
      const clean = line.replace(/\r$/, '')
      if (clean.startsWith('event:')) {
        event = clean.slice(6).trim()
      } else if (clean.startsWith('data:')) {
        // SSE 规范：冒号后可以有一个空格，要去掉
        dataLines.push(clean.slice(5).replace(/^ /, ''))
      }
    }
    if (dataLines.length === 0) {
      return
    }
    let payload: any
    try {
      payload = JSON.parse(dataLines.join('\n'))
    } catch {
      return
    }
    switch (event) {
      case 'session':
        handlers.onSession?.(payload as SessionEvent)
        break
      case 'rag':
        handlers.onRag?.(payload as RagEvent)
        break
      case 'tool_call':
        handlers.onToolCall?.(payload as ToolCallEvent)
        break
      case 'tool_confirm':
        handlers.onToolConfirm?.(payload as ToolConfirmEvent)
        break
      case 'tool_confirm_result':
        handlers.onToolConfirmResult?.(payload as ToolConfirmResultEvent)
        break
      case 'tool_result':
        handlers.onToolResult?.(payload as ToolResultEvent)
        break
      case 'delta':
        handlers.onDelta?.((payload as { text: string }).text)
        break
      case 'done':
        handlers.onDone?.(payload as DoneEvent)
        break
      case 'error':
        handlers.onError?.((payload as { message: string }).message)
        break
      default:
        break
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      // 事件之间以空行分隔；这里按 \n\n 切，最后一段不完整的留在 buffer 里等下一块
      let separator = buffer.indexOf('\n\n')
      while (separator !== -1) {
        const raw = buffer.slice(0, separator)
        buffer = buffer.slice(separator + 2)
        dispatch(raw)
        separator = buffer.indexOf('\n\n')
      }
    }
    // 收尾：处理可能没有以空行结束的最后一条事件
    if (buffer.trim()) {
      dispatch(buffer)
    }
  } finally {
    reader.cancel().catch(() => undefined)
  }
}

/** 写操作二次确认回执。走独立的 HTTP 请求，用来唤醒被挂起的推流线程 */
export async function sendConfirm(confirmId: string, approved: boolean): Promise<boolean> {
  const data = await request<{ accepted: boolean }>('/api/chat/confirm', {
    method: 'POST',
    body: JSON.stringify({ confirmId, approved })
  })
  return data.accepted
}

export function fetchStatus(): Promise<StatusInfo> {
  return request<StatusInfo>('/api/chat/status')
}

export function fetchTools(): Promise<ToolMeta[]> {
  return request<ToolMeta[]>('/api/chat/tools')
}
