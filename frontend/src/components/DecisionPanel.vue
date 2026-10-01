<script setup lang="ts">
import { computed } from 'vue'
import type { DoneEvent, RagEvent, ToolTrace } from '@/types'

const props = defineProps<{
  rag: RagEvent | null
  tools: ToolTrace[]
  done: DoneEvent | null
  running: boolean
  hasConversation: boolean
}>()

const empty = computed(() => !props.hasConversation && !props.running)

/** 分数条上阈值虚线的位置：超过它的片段才会被送进 Prompt */
const thresholdPercent = computed(() => {
  const t = props.rag?.threshold ?? 0
  return Math.min(100, Math.max(0, t * 100))
})

const statusMeta: Record<ToolTrace['status'], { text: string; cls: string }> = {
  RUNNING: { text: '执行中', cls: 'tag-blue' },
  WAITING_CONFIRM: { text: '等待确认', cls: 'tag-amber' },
  SUCCESS: { text: '成功', cls: 'tag-green' },
  FAILED: { text: '失败', cls: 'tag-red' },
  REJECTED: { text: '已取消', cls: 'tag-gray' }
}

function statusOf(trace: ToolTrace) {
  return statusMeta[trace.status]
}

function argEntries(args: Record<string, unknown>) {
  return Object.entries(args ?? {}).map(([k, v]) => ({
    key: k,
    value: typeof v === 'object' ? JSON.stringify(v) : String(v)
  }))
}

const ARG_LABELS: Record<string, string> = {
  orderNo: '订单号',
  reason: '退款原因',
  newAddress: '新地址'
}

function argLabel(key: string) {
  return ARG_LABELS[key] ?? key
}
</script>

<template>
  <aside class="panel">
    <div class="panel-head">
      <div class="panel-title">
        <span class="dot-live" :class="{ active: running }" />
        Agent 决策过程
      </div>
      <span v-if="done" class="tag tag-green">已完成</span>
      <span v-else-if="running" class="tag tag-blue">进行中</span>
    </div>

    <div class="panel-body">
      <div v-if="empty" class="empty">
        <div class="empty-icon">◔</div>
        <p>发送一条消息</p>
        <p class="empty-sub">这里会实时展示 Agent 的检索、工具调用与决策轨迹</p>
      </div>

      <template v-else>
        <!-- ---------- 关键指标 ---------- -->
        <section class="block">
          <div class="block-title">关键指标</div>
          <div class="metrics-grid">
            <div class="metric">
              <span class="metric-value">{{ rag?.retrieveMs ?? '—' }}<i>ms</i></span>
              <span class="metric-label">检索耗时</span>
            </div>
            <div class="metric">
              <span class="metric-value">{{ done?.firstTokenMs ?? '—' }}<i>ms</i></span>
              <span class="metric-label">首 Token</span>
            </div>
            <div class="metric">
              <span class="metric-value">{{ done?.totalMs ?? '—' }}<i>ms</i></span>
              <span class="metric-label">总耗时</span>
            </div>
            <div class="metric">
              <span class="metric-value">{{ done ? done.promptTokens + done.completionTokens : '—' }}</span>
              <span class="metric-label">Token 总量</span>
            </div>
            <div class="metric">
              <span class="metric-value">{{ done?.agentSteps ?? (running ? '…' : '—') }}</span>
              <span class="metric-label">决策轮数</span>
            </div>
            <div class="metric">
              <span class="metric-value">{{ tools.length }}</span>
              <span class="metric-label">工具调用</span>
            </div>
          </div>
        </section>

        <!-- ---------- 知识库检索 ---------- -->
        <section class="block">
          <div class="block-title">
            知识库检索
            <span v-if="rag" class="tag tag-gray">
              topK {{ rag.topK }} · 阈值 {{ rag.threshold }}
            </span>
          </div>

          <div v-if="!rag" class="hint">未启用检索</div>
          <div v-else-if="rag.error" class="hint err">检索失败：{{ rag.error }}</div>

          <template v-else>
            <div class="rag-summary">
              命中 <b>{{ rag.hitCount }}</b> 条，采用 <b>{{ rag.usedCount }}</b> 条
              <span v-if="rag.usedCount === 0" class="tag tag-amber">全部低于阈值，本次不作答依据</span>
            </div>

            <div class="hits">
              <div v-for="hit in rag.hits" :key="hit.index" class="hit">
                <div class="hit-head">
                  <span class="hit-rank">#{{ hit.index }}</span>
                  <span class="hit-doc">{{ hit.docName }}</span>
                  <span class="hit-chunk">片段 {{ hit.chunkIndex }}</span>
                  <span class="hit-score mono" :class="{ dim: !hit.used }">{{ hit.score.toFixed(4) }}</span>
                </div>
                <div class="score-bar">
                  <div
                    class="score-fill"
                    :class="{ used: hit.used }"
                    :style="{ width: Math.min(100, hit.score * 100) + '%' }"
                  />
                  <div class="score-threshold" :style="{ left: thresholdPercent + '%' }" />
                </div>
                <div class="hit-snippet">{{ hit.snippet }}</div>
                <div class="hit-flag" :class="hit.used ? 'is-used' : 'is-skipped'">
                  {{ hit.used ? '✓ 已送入 Prompt' : '✕ 分数不足，已丢弃' }}
                </div>
              </div>
            </div>
          </template>
        </section>

        <!-- ---------- 工具调用 ---------- -->
        <section v-if="tools.length || running" class="block">
          <div class="block-title">
            工具调用
            <span class="tag tag-violet">Function Calling</span>
          </div>

          <div v-if="!tools.length" class="hint">
            {{ running ? '本轮未调用工具' : '未调用工具' }}
          </div>

          <div v-else class="timeline">
            <div v-for="(trace, i) in tools" :key="i" class="tool-item">
              <div class="tool-line">
                <span class="tool-index">{{ trace.step }}</span>
                <div class="tool-name">
                  <span class="tool-label">{{ trace.label }}</span>
                  <span class="mono tool-code">{{ trace.name }}</span>
                </div>
                <span v-if="trace.write" class="tag tag-amber">写操作</span>
                <span class="tag" :class="statusOf(trace).cls">{{ statusOf(trace).text }}</span>
              </div>

              <div class="tool-args">
                <span v-for="arg in argEntries(trace.arguments)" :key="arg.key" class="tool-arg">
                  <i>{{ argLabel(arg.key) }}</i>{{ arg.value }}
                </span>
              </div>

              <div v-if="trace.confirmStatus" class="tool-confirm">
                确认结果：
                <span
                  class="tag"
                  :class="
                    trace.confirmStatus === 'CONFIRMED'
                      ? 'tag-green'
                      : trace.confirmStatus === 'TIMEOUT'
                        ? 'tag-amber'
                        : 'tag-gray'
                  "
                >
                  {{
                    trace.confirmStatus === 'CONFIRMED'
                      ? '用户已确认'
                      : trace.confirmStatus === 'TIMEOUT'
                        ? '超时自动取消'
                        : '用户已取消'
                  }}
                </span>
              </div>

              <div v-if="trace.summary" class="tool-summary" :class="{ fail: trace.status === 'FAILED' }">
                {{ trace.summary }}
                <span v-if="trace.ms" class="tool-ms">{{ trace.ms }}ms</span>
              </div>
            </div>
          </div>
        </section>

        <!-- ---------- 收口 ---------- -->
        <section v-if="done" class="block">
          <div class="block-title">本轮收口</div>
          <div class="footprint">
            <div><span>检索</span><b>{{ done.retrieveMs }}ms</b></div>
            <div><span>模型生成</span><b>{{ done.llmMs }}ms</b></div>
            <div><span>引用条数</span><b>{{ done.citationCount }}</b></div>
            <div><span>Prompt / 输出</span><b class="mono">{{ done.promptTokens }} / {{ done.completionTokens }}</b></div>
          </div>
        </section>
      </template>
    </div>
  </aside>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  width: 400px;
  flex-shrink: 0;
  background: var(--panel);
  border-left: 1px solid var(--border);
  min-height: 0;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-weight: 600;
  font-size: 13.5px;
  margin-right: auto;
}

.dot-live {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--border-strong);
}

.dot-live.active {
  background: var(--success);
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.15);
  animation: pulse 1.4s infinite;
}

@keyframes pulse {
  50% {
    opacity: 0.4;
  }
}

.panel-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 16px 24px;
}

.empty {
  margin-top: 70px;
  text-align: center;
  color: var(--text-mute);
}

.empty-icon {
  font-size: 30px;
  margin-bottom: 10px;
  color: var(--border-strong);
}

.empty p {
  margin: 4px 0;
  font-size: 13px;
}

.empty-sub {
  font-size: 12px !important;
  max-width: 240px;
  margin: 0 auto !important;
  line-height: 1.6;
}

.block {
  margin-bottom: 22px;
}

.block-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-sub);
  text-transform: uppercase;
  letter-spacing: 0.4px;
  margin-bottom: 10px;
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  background: var(--panel-soft);
  border: 1px solid var(--border);
  border-radius: 8px;
}

.metric-value {
  font-size: 16px;
  font-weight: 600;
  line-height: 1.2;
}

.metric-value i {
  font-size: 10px;
  font-style: normal;
  color: var(--text-mute);
  margin-left: 2px;
}

.metric-label {
  font-size: 11px;
  color: var(--text-mute);
}

.rag-summary {
  font-size: 12.5px;
  color: var(--text-sub);
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.rag-summary b {
  color: var(--text);
}

.hits {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.hit {
  padding: 9px 11px;
  background: var(--panel-soft);
  border: 1px solid var(--border);
  border-radius: 9px;
}

.hit-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
}

.hit-rank {
  color: var(--text-mute);
  font-family: ui-monospace, monospace;
}

.hit-doc {
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 150px;
}

.hit-chunk {
  color: var(--text-mute);
  font-size: 11px;
}

.hit-score {
  margin-left: auto;
  font-weight: 600;
}

.hit-score.dim {
  color: var(--text-mute);
  font-weight: 400;
}

.score-bar {
  position: relative;
  height: 4px;
  background: #eceef1;
  border-radius: 2px;
  margin: 7px 0 6px;
  overflow: visible;
}

.score-fill {
  height: 100%;
  border-radius: 2px;
  background: #d1d5db;
  transition: width 0.4s ease;
}

.score-fill.used {
  background: linear-gradient(90deg, #60a5fa, #2563eb);
}

.score-threshold {
  position: absolute;
  top: -3px;
  width: 2px;
  height: 10px;
  background: var(--danger);
  border-radius: 1px;
}

.hit-snippet {
  font-size: 11.5px;
  color: var(--text-sub);
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.hit-flag {
  margin-top: 5px;
  font-size: 10.5px;
}

.hit-flag.is-used {
  color: #047857;
}

.hit-flag.is-skipped {
  color: var(--text-mute);
}

.timeline {
  position: relative;
  padding-left: 14px;
}

.timeline::before {
  content: '';
  position: absolute;
  left: 8px;
  top: 8px;
  bottom: 8px;
  width: 1.5px;
  background: var(--border);
}

.tool-item {
  position: relative;
  padding: 0 0 14px 12px;
}

.tool-line {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}

.tool-index {
  position: absolute;
  left: -14px;
  top: 1px;
  width: 17px;
  height: 17px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--violet-soft);
  color: var(--violet);
  font-size: 10px;
  font-weight: 700;
  border: 1.5px solid var(--panel);
}

.tool-name {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.tool-label {
  font-size: 13px;
  font-weight: 600;
}

.tool-code {
  color: var(--text-mute);
  font-size: 11px;
}

.tool-args {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 6px 0 0;
}

.tool-arg {
  font-size: 11.5px;
  color: var(--text-sub);
  line-height: 1.5;
}

.tool-arg i {
  font-style: normal;
  color: var(--text-mute);
  margin-right: 5px;
}

.tool-confirm {
  margin-top: 5px;
  font-size: 11.5px;
  color: var(--text-sub);
}

.tool-summary {
  margin-top: 6px;
  padding: 6px 9px;
  background: var(--success-soft);
  border-radius: 6px;
  font-size: 11.5px;
  color: #065f46;
  line-height: 1.5;
}

.tool-summary.fail {
  background: var(--danger-soft);
  color: #991b1b;
}

.tool-ms {
  margin-left: 6px;
  color: var(--text-mute);
}

.footprint {
  display: flex;
  flex-direction: column;
  gap: 5px;
  font-size: 12px;
}

.footprint > div {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
  border-bottom: 1px dashed var(--border);
}

.footprint span {
  color: var(--text-mute);
}

.hint {
  font-size: 12px;
  color: var(--text-mute);
  padding: 8px 0;
}

.hint.err {
  color: var(--danger);
}
</style>
