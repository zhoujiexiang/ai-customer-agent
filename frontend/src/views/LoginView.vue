<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMessage } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const message = useMessage()

const username = ref('admin')
const password = ref('admin123')
const loading = ref(false)

async function submit() {
  if (!username.value.trim() || !password.value) {
    message.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(username.value.trim(), password.value)
    message.success('登录成功')
    const redirect = (route.query.redirect as string) || '/chat'
    router.push(redirect)
  } catch (e) {
    message.error(e instanceof Error ? e.message : '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card card">
      <div class="login-head">
        <span class="brand-logo lg">云</span>
        <h1>云集商城 · AI 售后客服</h1>
        <p>管理后台登录 · 知识库与对话记录受保护</p>
      </div>

      <div class="login-form">
        <label>用户名</label>
        <n-input v-model:value="username" placeholder="admin" size="large" @keyup.enter="submit" />

        <label>密码</label>
        <n-input
          v-model:value="password"
          type="password"
          show-password-on="click"
          placeholder="admin123"
          size="large"
          @keyup.enter="submit"
        />

        <n-button type="primary" size="large" block :loading="loading" @click="submit">
          登录
        </n-button>

        <p class="hint">演示账号：<code>admin / admin123</code></p>
      </div>
    </div>

    <p class="login-foot">
      Vue3 + TypeScript · Spring Boot 3 · PostgreSQL / pgvector
    </p>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 20px;
  background:
    radial-gradient(900px 400px at 50% -10%, #e0e7ff 0%, transparent 60%),
    var(--bg);
}

.login-card {
  width: 380px;
  padding: 32px 30px 26px;
  box-shadow: var(--shadow-md);
}

.login-head {
  text-align: center;
  margin-bottom: 24px;
}

.login-head h1 {
  font-size: 17px;
  margin: 14px 0 6px;
  font-weight: 600;
}

.login-head p {
  margin: 0;
  font-size: 12px;
  color: var(--text-mute);
}

.brand-logo.lg {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  font-size: 20px;
  margin: 0 auto;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.login-form label {
  font-size: 12px;
  color: var(--text-sub);
  margin-top: 6px;
}

.login-form :deep(.n-button) {
  margin-top: 16px;
}

.hint {
  text-align: center;
  font-size: 12px;
  color: var(--text-mute);
  margin: 6px 0 0;
}

.hint code {
  background: #f3f4f6;
  padding: 1px 5px;
  border-radius: 4px;
}

.login-foot {
  font-size: 12px;
  color: var(--text-mute);
}
</style>
