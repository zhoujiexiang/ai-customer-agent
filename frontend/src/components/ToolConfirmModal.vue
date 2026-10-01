<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { ToolConfirmEvent } from '@/types'

const props = defineProps<{ confirm: ToolConfirmEvent | null }>()
const emit = defineEmits<{ decide: [approved: boolean] }>()

const remaining = ref(0)
let timer: number | undefined

const total = computed(() => props.confirm?.timeoutSeconds ?? 60)
const percent = computed(() => (total.value ? (remaining.value / total.value) * 100 : 0))

const urgent = computed(() => remaining.value <= 10)

function stopTimer() {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

watch(
  () => props.confirm?.confirmId,
  (id) => {
    stopTimer()
    if (!id) {
      return
    }
    remaining.value = total.value
    timer = window.setInterval(() => {
      remaining.value -= 1
      if (remaining.value <= 0) {
        // 倒计时归零不再回执：后端会自行判定 TIMEOUT，
        // 此时再发确认请求只会拿到「已失效」，不如直接收起弹窗
        stopTimer()
      }
    }, 1000)
  },
  { immediate: true }
)

onUnmounted(stopTimer)

const args = computed(() => {
  const raw = props.confirm?.arguments ?? {}
  return Object.entries(raw).map(([key, value]) => ({
    key,
    label: ARG_LABELS[key] ?? key,
    value: typeof value === 'object' ? JSON.stringify(value) : String(value)
  }))
})

const ARG_LABELS: Record<string, string> = {
  orderNo: '订单号',
  reason: '退款原因',
  newAddress: '新地址'
}

function decide(approved: boolean) {
  stopTimer()
  emit('decide', approved)
}
</script>

<template>
  <!-- v-if 提到 n-modal 上：没有待确认操作时整个弹窗不挂载。
       若只在内层 div 上判断，Naive UI 会因为 default slot 渲染为空而打印告警 -->
  <n-modal v-if="confirm" :show="true" :mask-closable="false" :close-on-esc="false">
    <div class="confirm-card">
      <div class="confirm-head">
        <span class="warn-icon">!</span>
        <div>
          <h3>该操作需要你确认</h3>
          <p>Agent 准备执行一个会修改数据的写操作</p>
        </div>
      </div>

      <div class="confirm-body">
        <div class="preview">{{ confirm.preview }}</div>

        <div class="args">
          <div v-for="item in args" :key="item.key" class="arg-row">
            <span class="arg-label">{{ item.label }}</span>
            <span class="arg-value">{{ item.value }}</span>
          </div>
          <div class="arg-row">
            <span class="arg-label">调用工具</span>
            <span class="arg-value mono">{{ confirm.name }}</span>
          </div>
        </div>

        <div class="countdown">
          <div class="countdown-bar">
            <div
              class="countdown-fill"
              :class="{ urgent }"
              :style="{ width: percent + '%' }"
            />
          </div>
          <span class="countdown-text">
            {{ remaining > 0 ? `${remaining} 秒后自动取消` : '已超时，操作自动取消' }}
          </span>
        </div>
      </div>

      <div class="confirm-foot">
        <n-button @click="decide(false)">取消</n-button>
        <n-button type="primary" @click="decide(true)">确认执行</n-button>
      </div>
    </div>
  </n-modal>
</template>

<style scoped>
.confirm-card {
  width: 460px;
  background: var(--panel);
  border-radius: 14px;
  box-shadow: var(--shadow-md);
  overflow: hidden;
}

.confirm-head {
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 18px 20px;
  background: var(--warn-soft);
  border-bottom: 1px solid #fde68a;
}

.warn-icon {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--warn);
  color: #fff;
  font-weight: 700;
  flex-shrink: 0;
}

.confirm-head h3 {
  margin: 0;
  font-size: 15px;
}

.confirm-head p {
  margin: 3px 0 0;
  font-size: 12px;
  color: #b45309;
}

.confirm-body {
  padding: 18px 20px;
}

.preview {
  padding: 11px 13px;
  background: var(--panel-soft);
  border: 1px solid var(--border);
  border-left: 3px solid var(--warn);
  border-radius: 8px;
  font-size: 13.5px;
  line-height: 1.6;
}

.args {
  margin-top: 14px;
}

.arg-row {
  display: flex;
  gap: 12px;
  padding: 5px 0;
  font-size: 12.5px;
  border-bottom: 1px dashed var(--border);
}

.arg-row:last-child {
  border-bottom: none;
}

.arg-label {
  width: 70px;
  flex-shrink: 0;
  color: var(--text-mute);
}

.arg-value {
  color: var(--text);
  word-break: break-all;
}

.countdown {
  margin-top: 16px;
}

.countdown-bar {
  height: 4px;
  background: #f3f4f6;
  border-radius: 2px;
  overflow: hidden;
}

.countdown-fill {
  height: 100%;
  background: var(--warn);
  transition: width 1s linear;
}

.countdown-fill.urgent {
  background: var(--danger);
}

.countdown-text {
  display: block;
  margin-top: 6px;
  font-size: 11.5px;
  color: var(--text-mute);
  text-align: right;
}

.confirm-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid var(--border);
  background: var(--panel-soft);
}
</style>
