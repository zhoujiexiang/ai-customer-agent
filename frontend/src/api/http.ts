const TOKEN_KEY = 'agent_token'
const USER_KEY = 'agent_user'

export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function getUsername(): string {
  return localStorage.getItem(USER_KEY) || ''
}

export function setUsername(name: string): void {
  localStorage.setItem(USER_KEY, name)
}

export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/** 登录失效的统一出口：清掉本地凭证并跳回登录页 */
function handleUnauthorized(): void {
  clearAuth()
  if (!location.hash.startsWith('#/login')) {
    location.hash = '#/login'
  }
}

export async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { ...(options.headers as Record<string, string>) }
  const token = getToken()
  if (token) {
    headers['Authorization'] = `Bearer ${token}`
  }
  // FormData 的 Content-Type 必须交给浏览器自己生成（要带 boundary），不能手动写
  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json'
  }

  const resp = await fetch(url, { ...options, headers })
  if (resp.status === 401) {
    handleUnauthorized()
    throw new Error('登录已失效，请重新登录')
  }

  let json: ApiResult<T>
  try {
    json = (await resp.json()) as ApiResult<T>
  } catch {
    throw new Error(`服务返回了非 JSON 内容（HTTP ${resp.status}）`)
  }
  if (json.code !== 200) {
    throw new Error(json.message || `请求失败（HTTP ${resp.status}）`)
  }
  return json.data
}
