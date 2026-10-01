<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useMessage } from 'naive-ui'
import MessageBubble from '@/components/MessageBubble.vue'
import DecisionPanel from '@/components/DecisionPanel.vue'
import ToolConfirmModal from '@/components/ToolConfirmModal.vue'
import { fetchStatus, sendConfirm, streamChat } from '@/api/chat'
import type {
  ChatMessageView,
  DoneEvent,
  RagEvent,
  ToolConfirmEvent,
  ToolTrace
} from '@/types'

const toast = useMessage()

const messages = ref<ChatMessageView[]>([])
const sessionId = ref<number | null>(null)
const input = ref('')
const running = ref(false)
const hasConversation = ref(false)

const rag = ref<RagEvent | null>(null)
const tools = ref<ToolTrace[]>([])
const done = ref<DoneEvent | null>(null)
const pendingConfirm = ref<ToolConfirmEvent | null>(null)

const useRag = ref(true)
const enableTools = ref(true)

let abortController: AbortController | null = null
let seq = 0

const listRef = ref<HTMLElement | null>(null)

const EXAMPLES = [
  { text: '退款一般几天到账？', hint: '纯知识库检索' },
  { text: '帮我查一下订单 202610010003 的物流到哪了', hint: '工具：查物流' },
  { text: '订单 202610010001 我要退款，商品有质量问题', hint: '写操作 + 二次确认' },
  { text: '把订单 202610010005 的收货地址改成杭州市西湖区文三路 100 号', hint: '写操作 + 二次确认' }
]

onMounted(async () => {
  try {
    const status = await fetchStatus()
    if (!status.ready) {
      toast.warning('后端 API Key 未配置完整，对话或检索可能不可用')
    }
  } catch {
    toast.error('无法连接后端服务，请确认后端已在 8080 端口启动')
  }
})

function uid(): string {
  seq += 1
  return `m${seq}`
}

function scrollToBottom() {
  nextTick(() => {
    const el = listRef.value
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  })
}

/** 同一轮里可能同名工具被调用多次，所以要从后往前找最近的一条 */
function lastTool(step: number, name: string): ToolTrace | undefined {
  for (let i = tools.value.length - 1; i >= 0; i -= 1) {
    const item = tools.value[i]
    if (item.step === step && item.name === name) {
      return item
    }
  }
  return undefined
}

async function send(preset?: string) {
  const question = (preset ?? input.value).trim()
  if (!question || running.value) {
    return
  }

  // 决策面板按「每轮」重置，保证看到的永远是当前这轮的轨迹
  rag.value = null
  tools.value = []
  done.value = null
  pendingConfirm.value = null
  hasConversation.value = true

  messages.value.push({ id: uid(), role: 'USER', content: question })
  messages.value.push({ id: uid(), role: 'ASSISTANT', content: '', streaming: true })
  // 必须把数组里的元素取回来用：push 进去的原始对象会被 reactive() 包一层代理，
  // 后续直接改原始对象的 content/streaming 不会触发视图更新，
  // 表现就是气泡永远停在「正在输入」，而右侧决策面板（独立 ref）却是正常的
  const reply: ChatMessageView = messages.value[messages.value.length - 1]
  input.value = ''
  running.value = true
  scrollToBottom()

  abortController = new AbortController()

  try {
    await streamChat(
      {
        sessionId: sessionId.value,
        question,
        useRag: useRag.value,
        enableTools: enableTools.value
      },
      {
        onSession: (data) => {
          sessionId.value = data.sessionId
        },
        onRag: (data) => {
          rag.value = data
        },
        onToolCall: (data) => {
          tools.value.push({
            step: data.step,
            name: data.name,
            label: data.label,
            write: data.write,
            arguments: data.arguments,
            status: 'RUNNING'
          })
        },
        onToolConfirm: (data) => {
          pendingConfirm.value = data
          const trace = lastTool(data.step, data.name)
          if (trace) {
            trace.status = 'WAITING_CONFIRM'
          }
        },
        onToolConfirmResult: (data) => {
          pendingConfirm.value = null
          const trace = tools.value.find((t) => t.confirmStatus === undefined && t.write)
          const target =
            trace ??
            tools.value
              .slice()
              .reverse()
              .find((t) => t.status === 'WAITING_CONFIRM' || t.status === 'RUNNING')
          if (target) {
            target.confirmStatus = data.status
            if (data.status !== 'CONFIRMED') {
              target.status = 'REJECTED'
              target.summary =
                data.status === 'TIMEOUT' ? '超时未确认，操作已自动取消' : '用户取消了该操作'
            }
          }
        },
        onToolResult: (data) => {
          const trace = lastTool(data.step, data.name)
          if (trace) {
            trace.status = data.success ? 'SUCCESS' : 'FAILED'
            trace.summary = data.summary
            trace.ms = data.ms
            trace.data = data.data
          }
        },
        onDelta: (text) => {
          reply.content += text
          scrollToBottom()
        },
        onDone: (data) => {
          done.value = data
          reply.streaming = false
          reply.done = data
          // 后端 done 事件只给引用条数，明细前端从 rag 事件里取（标记为 used 的那些）
          const hits = rag.value?.hits ?? []
          const used = hits.filter((h) => h.used)
          if (used.length) {
            reply.citations = used.map((h) => ({
              index: h.index,
              docName: h.docName,
              chunkIndex: h.chunkIndex,
              score: h.score,
              snippet: h.snippet
            }))
          }
        },
        onError: (msg) => {
          reply.error = msg
        }
      },
      abortController.signal
    )
  } catch (e) {
    if ((e as Error).name === 'AbortError') {
      reply.content += reply.content ? '\n\n（已停止生成）' : '（已停止生成）'
    } else {
      reply.error = e instanceof Error ? e.message : '请求失败'
    }
  } finally {
    reply.streaming = false
    running.value = false
    abortController = null
    scrollToBottom()
  }
}

function stop() {
  abortController?.abort()
}

async function decide(approved: boolean) {
  const current = pendingConfirm.value
  if (!current) {
    return
  }
  pendingConfirm.value = null
  try {
    const accepted = await sendConfirm(current.confirmId, approved)
    if (!accepted) {
      toast.warning('该确认已失效（可能已超时），请重新发起对话')
    }
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '提交确认失败')
  }
}

function reset() {
  if (running.value) {
    stop()
  }
  messages.value = []
  sessionId.value = null
  rag.value = null
  tools.value = []
  done.value = null
  hasConversation.value = false
}
</script>

<template>
  <div class="chat-layout">
    <section class="chat-main">
      <div ref="listRef" class="messages">
        <div v-if="!messages.length" class="welcome">
          <div class="welcome-logo">云</div>
          <h2>你好，我是云集商城的售后客服助手</h2>
          <p>
            我可以基于平台政策回答问题，也能直接查订单、查物流、提交退款、修改收货地址。
            <br />
            右侧面板会实时展示我的检索结果和工具调用过程。
          </p>

          <div class="examples">
            <button
              v-for="item in EXAMPLES"
              :key="item.text"
              class="example"
              @click="send(item.text)"
            >
              <span class="example-text">{{ item.text }}</span>
              <span class="example-hint">{{ item.hint }}</span>
            </button>
          </div>
        </div>

        <MessageBubble v-for="msg in messages" :key="msg.id" :message="msg" />
      </div>

      <div class="composer">
        <div class="composer-tools">
          <label class="switch">
            <input v-model="useRag" type="checkbox" />
            <span>知识库检索</span>
          </label>
          <label class="switch">
            <input v-model="enableTools" type="checkbox" />
            <span>工具调用</span>
          </label>
          <span class="composer-tip">
            关掉开关可以做对照演示：纯模型回答 vs 检索增强 vs Agent 自主调用工具
          </span>
          <n-button v-if="messages.length" size="tiny" quaternary class="reset-btn" @click="reset">
            清空对话
          </n-button>
        </div>

        <div class="input-row">
          <textarea
            v-model="input"
            class="input"
            rows="2"
            placeholder="描述你的售后问题，例如：订单 202610010001 我要退款，商品有质量问题（Enter 发送，Shift + Enter 换行）"
            @keydown.enter.exact.prevent="send()"
          />
          <n-button v-if="running" type="error" secondary class="send-btn" @click="stop">
            停止
          </n-button>
          <n-button v-else type="primary" class="send-btn" :disabled="!input.trim()" @click="send()">
            发送
          </n-button>
        </div>
      </div>
    </section>

    <DecisionPanel
      :rag="rag"
      :tools="tools"
      :done="done"
      :running="running"
      :has-conversation="hasConversation"
    />

    <ToolConfirmModal :confirm="pendingConfirm" @decide="decide" />
  </div>
</template>

<style scoped>
.chat-layout {
  display: flex;
  height: 100%;
  min-height: 0;
}

.chat-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  min-height: 0;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 24px 28px 8px;
  min-height: 0;
}

.welcome {
  max-width: 620px;
  margin: 40px auto 30px;
  text-align: center;
}

.welcome-logo {
  width: 52px;
  height: 52px;
  border-radius: 15px;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  color: #fff;
  font-size: 22px;
  font-weight: 700;
  display: grid;
  place-items: center;
  margin: 0 auto 16px;
}

.welcome h2 {
  font-size: 19px;
  margin: 0 0 10px;
}

.welcome p {
  color: var(--text-sub);
  font-size: 13.5px;
  line-height: 1.75;
  margin: 0 0 26px;
}

.examples {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 9px;
  text-align: left;
}

.example {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 11px 13px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
}

.example:hover {
  border-color: var(--primary-border);
  background: var(--primary-soft);
  transform: translateY(-1px);
}

.example-text {
  font-size: 13px;
  color: var(--text);
  line-height: 1.5;
}

.example-hint {
  font-size: 11px;
  color: var(--text-mute);
}

.composer {
  flex-shrink: 0;
  padding: 10px 28px 18px;
  border-top: 1px solid var(--border);
  background: var(--panel);
}

.composer-tools {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 8px;
}

.switch {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--text-sub);
  cursor: pointer;
  user-select: none;
}

.switch input {
  accent-color: var(--primary);
  cursor: pointer;
}

.composer-tip {
  font-size: 11px;
  color: var(--text-mute);
}

.reset-btn {
  margin-left: auto;
}

.input-row {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.input {
  flex: 1;
  resize: none;
  padding: 10px 12px;
  border: 1px solid var(--border-strong);
  border-radius: 10px;
  font-family: inherit;
  font-size: 13.5px;
  line-height: 1.6;
  color: var(--text);
  background: var(--panel);
  outline: none;
  transition: border-color 0.15s;
}

.input:focus {
  border-color: var(--primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

.send-btn {
  height: 42px;
  min-width: 78px;
}
</style>
