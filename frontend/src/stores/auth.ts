import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { clearAuth, getToken, getUsername, request, setToken, setUsername } from '@/api/http'

interface LoginResult {
  token: string
  username: string
  nickname: string
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(getToken())
  const username = ref(getUsername())
  const nickname = ref('')

  const isLoggedIn = computed(() => !!token.value)

  async function login(user: string, password: string): Promise<void> {
    const data = await request<LoginResult>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username: user, password })
    })
    token.value = data.token
    username.value = data.username
    nickname.value = data.nickname
    setToken(data.token)
    setUsername(data.username)
  }

  function logout(): void {
    token.value = ''
    username.value = ''
    nickname.value = ''
    clearAuth()
  }

  return { token, username, nickname, isLoggedIn, login, logout }
})
