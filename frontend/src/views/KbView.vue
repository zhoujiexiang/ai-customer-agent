<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import { NButton, NTag, useDialog, useMessage, type DataTableColumns } from 'naive-ui'
import { deleteDocument, listDocuments, searchKb, uploadDocument, type KbSearchResult } from '@/api/kb'
import type { KbDocument } from '@/types'

const toast = useMessage()
const dialog = useDialog()

const docs = ref<KbDocument[]>([])
const loading = ref(false)
const uploading = ref(false)
const dragging = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const query = ref('')
const searching = ref(false)
const result = ref<KbSearchResult | null>(null)

const totalChunks = computed(() => docs.value.reduce((sum, d) => sum + (d.chunkCount || 0), 0))

const STATUS_META: Record<string, { text: string; type: 'success' | 'warning' | 'error' | 'default' }> = {
  READY: { text: '已就绪', type: 'success' },
  PARSING: { text: '解析中', type: 'warning' },
  FAILED: { text: '失败', type: 'error' }
}

function formatSize(bytes: number): string {
  if (!bytes) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

const columns = computed<DataTableColumns<KbDocument>>(() => [
  { title: '文档名称', key: 'name', ellipsis: { tooltip: true }, minWidth: 200 },
  {
    title: '类型',
    key: 'fileType',
    width: 90,
    render: (row) => h(NTag, { size: 'small', bordered: false }, { default: () => (row.fileType || 'txt').toUpperCase() })
  },
  {
    title: '切片数',
    key: 'chunkCount',
    width: 90,
    render: (row) => h('span', { class: 'mono' }, String(row.chunkCount ?? 0))
  },
  {
    title: '大小',
    key: 'fileSize',
    width: 100,
    render: (row) => h('span', { class: 'mono' }, formatSize(row.fileSize))
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: (row) => {
      const meta = STATUS_META[row.status] ?? STATUS_META.READY
      return h(NTag, { size: 'small', type: meta.type, bordered: false }, { default: () => meta.text })
    }
  },
  {
    title: '上传时间',
    key: 'createdAt',
    width: 165,
    render: (row) => h('span', { class: 'mono' }, (row.createdAt || '').replace('T', ' ').slice(0, 16))
  },
  {
    title: '操作',
    key: 'actions',
    width: 80,
    render: (row) =>
      h(
        NButton,
        { size: 'tiny', quaternary: true, type: 'error', onClick: () => confirmDelete(row) },
        { default: () => '删除' }
      )
  }
])

onMounted(load)

async function load() {
  loading.value = true
  try {
    docs.value = await listDocuments()
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '加载知识库失败')
  } finally {
    loading.value = false
  }
}

function pickFile() {
  fileInput.value?.click()
}

async function handleFiles(files: FileList | null) {
  if (!files || !files.length) {
    return
  }
  const file = files[0]
  if (!/\.(txt|md|markdown)$/i.test(file.name)) {
    toast.warning('目前只支持 .txt / .md 文本文件')
    return
  }
  uploading.value = true
  try {
    const doc = await uploadDocument(file)
    toast.success(`《${doc.name}》已入库，生成 ${doc.chunkCount} 个切片`)
    await load()
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '上传失败')
  } finally {
    uploading.value = false
    if (fileInput.value) {
      fileInput.value.value = ''
    }
  }
}

function onDrop(e: DragEvent) {
  dragging.value = false
  handleFiles(e.dataTransfer?.files ?? null)
}

function confirmDelete(row: KbDocument) {
  dialog.warning({
    title: '确认删除',
    content: `删除《${row.name}》会同时移除它的 ${row.chunkCount} 个切片与向量，且不可恢复。`,
    positiveText: '删除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await deleteDocument(row.id)
        toast.success('已删除')
        await load()
      } catch (e) {
        toast.error(e instanceof Error ? e.message : '删除失败')
      }
    }
  })
}

async function runSearch() {
  const q = query.value.trim()
  if (!q) {
    toast.warning('请输入检索内容')
    return
  }
  searching.value = true
  try {
    result.value = await searchKb(q)
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '检索失败')
  } finally {
    searching.value = false
  }
}

function thresholdLeft(threshold: number) {
  return Math.min(100, Math.max(0, threshold * 100)) + '%'
}
</script>

<template>
  <div class="kb-page">
    <div class="kb-head">
      <div>
        <h2>知识库管理</h2>
        <p>
          共 {{ docs.length }} 篇文档 · {{ totalChunks }} 个切片。上传后自动完成解析、切片与向量化。
        </p>
      </div>
      <n-button type="primary" :loading="uploading" @click="pickFile">上传文档</n-button>
      <input
        ref="fileInput"
        type="file"
        accept=".txt,.md,.markdown"
        hidden
        @change="handleFiles(($event.target as HTMLInputElement).files)"
      />
    </div>

    <div
      class="dropzone"
      :class="{ dragging }"
      @click="pickFile"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <span class="dz-icon">↑</span>
      <div>
        <b>点击选择或拖拽文件到此处</b>
        <p>支持 .txt / .md，单文件建议不超过 1 MB。项目内置 8 篇电商售后政策文档位于 docs/knowledge/</p>
      </div>
    </div>

    <div class="card table-card">
      <n-data-table
        :columns="columns"
        :data="docs"
        :loading="loading"
        :bordered="false"
        :row-key="(row: KbDocument) => row.id"
        size="small"
      />
      <div v-if="!docs.length && !loading" class="empty">知识库还是空的，先上传一篇文档试试</div>
    </div>

    <div class="card search-card">
      <div class="search-head">
        <h3>召回测试</h3>
        <span>只跑检索、不进模型 —— 调 chunk-size / topK / 阈值时唯一可靠的观测手段</span>
      </div>

      <div class="search-input">
        <n-input
          v-model:value="query"
          placeholder="输入一个问题，看看知识库召回了什么，例如：运费谁承担"
          @keyup.enter="runSearch"
        />
        <n-button type="primary" :loading="searching" @click="runSearch">检索</n-button>
      </div>

      <div v-if="result" class="search-result">
        <div class="search-meta">
          命中 <b>{{ result.hits.length }}</b> 条 · 耗时 <b>{{ result.retrieveMs }}ms</b> ·
          topK <b>{{ result.topK }}</b> · 阈值 <b>{{ result.threshold }}</b>
        </div>

        <div v-for="hit in result.hits" :key="hit.index" class="hit">
          <div class="hit-head">
            <span class="tag" :class="hit.used ? 'tag-blue' : 'tag-gray'">#{{ hit.index }}</span>
            <span class="hit-doc">{{ hit.docName }}</span>
            <span class="hit-chunk">片段 {{ hit.chunkIndex }}</span>
            <span class="hit-score mono">{{ hit.score.toFixed(4) }}</span>
            <span class="tag" :class="hit.used ? 'tag-green' : 'tag-amber'">
              {{ hit.used ? '采用' : '分数不足' }}
            </span>
          </div>
          <div class="score-bar">
            <div class="score-fill" :class="{ used: hit.used }" :style="{ width: Math.min(100, hit.score * 100) + '%' }" />
            <div class="score-threshold" :style="{ left: thresholdLeft(result.threshold) }" />
          </div>
          <div class="hit-snippet">{{ hit.snippet }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kb-page {
  height: 100%;
  overflow-y: auto;
  padding: 24px 28px 40px;
  max-width: 1100px;
  margin: 0 auto;
}

.kb-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}

.kb-head h2 {
  margin: 0 0 6px;
  font-size: 19px;
}

.kb-head p {
  margin: 0;
  color: var(--text-sub);
  font-size: 13px;
}

.dropzone {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 20px;
  border: 1.5px dashed var(--border-strong);
  border-radius: var(--radius);
  background: var(--panel);
  cursor: pointer;
  transition: all 0.15s;
  margin-bottom: 18px;
}

.dropzone:hover,
.dropzone.dragging {
  border-color: var(--primary);
  background: var(--primary-soft);
}

.dz-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: var(--primary-soft);
  color: var(--primary);
  font-size: 17px;
  font-weight: 700;
  flex-shrink: 0;
}

.dropzone b {
  font-size: 13.5px;
}

.dropzone p {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--text-mute);
}

.table-card {
  padding: 6px 8px;
  margin-bottom: 20px;
}

.empty {
  text-align: center;
  padding: 30px;
  color: var(--text-mute);
  font-size: 13px;
}

.search-card {
  padding: 18px 20px;
}

.search-head h3 {
  margin: 0 0 4px;
  font-size: 15px;
}

.search-head span {
  font-size: 12px;
  color: var(--text-mute);
}

.search-input {
  display: flex;
  gap: 10px;
  margin: 14px 0 0;
}

.search-result {
  margin-top: 16px;
}

.search-meta {
  font-size: 12.5px;
  color: var(--text-sub);
  margin-bottom: 12px;
}

.search-meta b {
  color: var(--text);
}

.hit {
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: 9px;
  background: var(--panel-soft);
  margin-bottom: 10px;
}

.hit-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  flex-wrap: wrap;
}

.hit-doc {
  font-weight: 500;
}

.hit-chunk {
  color: var(--text-mute);
  font-size: 11px;
}

.hit-score {
  margin-left: auto;
  font-weight: 600;
}

.score-bar {
  position: relative;
  height: 4px;
  background: #eceef1;
  border-radius: 2px;
  margin: 8px 0 7px;
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
  font-size: 12px;
  color: var(--text-sub);
  line-height: 1.6;
}
</style>
