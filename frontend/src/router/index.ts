import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import { getToken } from '@/api/http'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/chat' },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: '智能客服' }
  },
  {
    path: '/kb',
    name: 'kb',
    component: () => import('@/views/KbView.vue'),
    meta: { title: '知识库' }
  }
]

const router = createRouter({
  // 用 hash 模式：构建产物丢到任意静态目录都能直接打开，不用配服务端 fallback
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  const logged = !!getToken()
  if (!to.meta.public && !logged) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && logged) {
    return { path: '/chat' }
  }
  return true
})

export default router
