<script setup lang="ts">
import { computed } from 'vue'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import type { ChatMessageView } from '@/types'

const props = defineProps<{ message: ChatMessageView }>()

/**
 * Markdown 渲染。
 * <p>
 * 模型输出是不可信内容（可能被知识库里的文本带出 HTML），所以必须先 marked 再 DOMPurify，
 * 顺序不能反：先净化会把 markdown 语法也一起干掉。
 */
const html = computed(() => {
  const source = props.message.content || ''
  if (!source) {
    return ''
  }
  const rendered = marked.parse(source, { breaks: true, gfm: true, async: false }) as string
  const safe = DOMPurify.sanitize(rendered)
  // 把正文里的 [1] 渲染成引用角标。只匹配数字，不引入任何外部输入，插在净化之后是安全的
  return safe.replace(/\[(\d+)\]/g, '<sup class="cite-badge">$1</sup>')
})

const isUser = computed(() => props.message.role === 'USER')
</script>

<template>
  <div class="msg" :class="isUser ? 'msg-user' : 'msg-bot'">
    <div v-if="!isUser" class="avatar bot">AI</div>

    <div class="bubble-wrap">
      <div class="bubble" :class="isUser ? 'bubble-user' : 'bubble-bot'">
        <template v-if="isUser">
          <div class="plain">{{ message.content }}</div>
        </template>
        <template v-else>
          <div v-if="message.content" class="markdown" v-html="html" />
          <div v-else-if="message.streaming" class="thinking">
            <span class="dot" /><span class="dot" /><span class="dot" />
          </div>
          <span v-if="message.streaming && message.content" class="caret" />
          <div v-if="message.error" class="err">⚠ {{ message.error }}</div>
        </template>
      </div>

      <!-- 引用来源 -->
      <div v-if="message.citations?.length" class="citations">
        <div class="citations-title">引用来源（{{ message.citations.length }}）</div>
        <div v-for="c in message.citations" :key="c.index" class="citation">
          <span class="tag tag-blue">[{{ c.index }}]</span>
          <span class="citation-doc">{{ c.docName }}</span>
          <span class="citation-score mono">{{ c.score.toFixed(4) }}</span>
          <span class="citation-snippet">{{ c.snippet }}</span>
        </div>
      </div>

      <!-- 单条指标 -->
      <div v-if="message.done" class="metrics">
        <span>首字 {{ message.done.firstTokenMs }}ms</span>
        <span>总耗时 {{ message.done.totalMs }}ms</span>
        <span>检索 {{ message.done.retrieveMs }}ms</span>
        <span v-if="message.done.toolCallCount">工具 {{ message.done.toolCallCount }} 次</span>
        <span class="mono">{{ message.done.promptTokens }}→{{ message.done.completionTokens }} tok</span>
      </div>
    </div>

    <div v-if="isUser" class="avatar user">我</div>
  </div>
</template>

<style scoped>
.msg {
  display: flex;
  gap: 10px;
  margin-bottom: 18px;
  align-items: flex-start;
}

.msg-user {
  flex-direction: row;
  justify-content: flex-end;
}

.avatar {
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 600;
  margin-top: 2px;
}

.avatar.bot {
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  color: #fff;
}

.avatar.user {
  background: #e5e7eb;
  color: var(--text-sub);
}

.bubble-wrap {
  max-width: min(720px, 82%);
  min-width: 0;
}

.bubble {
  padding: 10px 14px;
  border-radius: 12px;
  line-height: 1.7;
  font-size: 14px;
  word-break: break-word;
}

.bubble-user {
  background: var(--primary);
  color: #fff;
  border-top-right-radius: 4px;
}

.bubble-bot {
  background: var(--panel);
  border: 1px solid var(--border);
  border-top-left-radius: 4px;
}

.plain {
  white-space: pre-wrap;
}

.markdown :deep(p) {
  margin: 0 0 8px;
}

.markdown :deep(p:last-child) {
  margin-bottom: 0;
}

.markdown :deep(ul),
.markdown :deep(ol) {
  margin: 6px 0;
  padding-left: 20px;
}

.markdown :deep(li) {
  margin: 2px 0;
}

.markdown :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  width: 100%;
  font-size: 13px;
}

.markdown :deep(th),
.markdown :deep(td) {
  border: 1px solid var(--border);
  padding: 5px 9px;
  text-align: left;
}

.markdown :deep(th) {
  background: var(--panel-soft);
  font-weight: 600;
}

.markdown :deep(code) {
  background: #f3f4f6;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 12.5px;
}

.markdown :deep(pre) {
  background: #1f2937;
  color: #e5e7eb;
  padding: 10px 12px;
  border-radius: 8px;
  overflow-x: auto;
}

.markdown :deep(pre code) {
  background: none;
  color: inherit;
  padding: 0;
}

.markdown :deep(.cite-badge) {
  display: inline-block;
  min-width: 14px;
  padding: 0 3px;
  margin: 0 1px;
  background: var(--primary-soft);
  color: var(--primary);
  border-radius: 4px;
  font-size: 10px;
  line-height: 15px;
  text-align: center;
  font-weight: 600;
  vertical-align: super;
}

.caret {
  display: inline-block;
  width: 7px;
  height: 15px;
  margin-left: 2px;
  background: var(--primary);
  vertical-align: text-bottom;
  animation: blink 1s steps(2, start) infinite;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}

.thinking {
  display: flex;
  gap: 4px;
  padding: 4px 0;
}

.thinking .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--text-mute);
  animation: bounce 1.2s infinite;
}

.thinking .dot:nth-child(2) {
  animation-delay: 0.15s;
}

.thinking .dot:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.4;
  }
  30% {
    transform: translateY(-4px);
    opacity: 1;
  }
}

.err {
  margin-top: 6px;
  color: var(--danger);
  font-size: 13px;
}

.citations {
  margin-top: 8px;
  padding: 9px 11px;
  background: var(--panel-soft);
  border: 1px solid var(--border);
  border-radius: 9px;
}

.citations-title {
  font-size: 11px;
  color: var(--text-mute);
  margin-bottom: 6px;
}

.citation {
  display: grid;
  grid-template-columns: auto auto auto 1fr;
  align-items: baseline;
  gap: 8px;
  padding: 3px 0;
  font-size: 12px;
}

.citation-doc {
  font-weight: 500;
  white-space: nowrap;
}

.citation-score {
  color: var(--text-mute);
}

.citation-snippet {
  color: var(--text-sub);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 6px;
  font-size: 11px;
  color: var(--text-mute);
}
</style>
