<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const showHeader = computed(() => route.path !== '/login')
const activePath = computed(() => route.path)

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <n-config-provider :theme-overrides="{ common: { primaryColor: '#2563eb' } }">
    <n-message-provider>
      <n-dialog-provider>
        <div class="app-shell">
          <header v-if="showHeader" class="app-header">
            <div class="brand">
              <span class="brand-logo">云</span>
              <span>云集商城</span>
              <span class="brand-sub">AI 售后客服 Agent</span>
            </div>

            <nav class="nav">
              <router-link to="/chat" class="nav-item" :class="{ active: activePath === '/chat' }">
                智能客服
              </router-link>
              <router-link to="/kb" class="nav-item" :class="{ active: activePath === '/kb' }">
                知识库
              </router-link>
            </nav>

            <div class="header-right">
              <span>{{ auth.username || 'admin' }}</span>
              <n-button size="tiny" quaternary @click="logout">退出</n-button>
            </div>
          </header>

          <main class="app-main">
            <router-view />
          </main>
        </div>
      </n-dialog-provider>
    </n-message-provider>
  </n-config-provider>
</template>
