/**
 * 前端 UI 冒烟检查。
 *
 * 用本机 Chrome 走一遍完整用户路径，逐步截图，同时收集控制台错误。
 * 目的不是替代人工验收，而是确认「页面能渲染、能登录、流式能跑、弹窗能弹」——
 * 这几步都是纯前端代码，接口测试覆盖不到。
 *
 * 运行前提：后端 8080 与前端 5173 都已启动。
 *   node scripts/ui-check.mjs
 */

import { createRequire } from 'node:module'
import { mkdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const require = createRequire('file:///C:/Users/share/.workbuddy/binaries/node/workspace/')
const puppeteer = require('puppeteer-core')

const HERE = dirname(fileURLToPath(import.meta.url))
const OUT_DIR = resolve(HERE, '../docs/screenshots')
mkdirSync(OUT_DIR, { recursive: true })

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
// 默认按常规开发端口跑；受限环境可用 APP_URL 覆盖（例如仅允许监听 4173 的沙箱）
const APP = process.env.APP_URL || 'http://127.0.0.1:5173'
const WAIT = (ms) => new Promise((r) => setTimeout(r, ms))

const consoleErrors = []
const failedRequests = []
const badResponses = []
let step = 0

/**
 * 直接写 DOM 值并派发 input 事件，而不是「点一下 + 键盘输入」。
 * 登录页的账号密码是预填的演示值，三击全选在 Naive UI 的封装输入框上并不可靠，
 * 会出现把新值拼在旧值后面的情况（admin -> adminadmin）。
 */
async function fill(page, selector, value) {
  await page.$eval(
    selector,
    (el, v) => {
      const proto =
        el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v)
      el.dispatchEvent(new Event('input', { bubbles: true }))
    },
    value
  )
}

async function shot(page, name, note) {
  step += 1
  const file = `${String(step).padStart(2, '0')}-${name}.png`
  await page.screenshot({ path: resolve(OUT_DIR, file) })
  console.log(`  📷 ${file}  ${note}`)
}

/** 等右侧面板出现「已完成」，说明这一轮流式真正结束了 */
async function waitDone(page, timeout = 90000) {
  await page.waitForFunction(
    () => document.body.innerText.includes('已完成'),
    { timeout }
  )
}

/**
 * 直接往输入框发问，而不是点示例按钮。
 * 示例按钮只在空对话时渲染，第二轮开始就找不到了 —— 而且直接输入更贴近真实使用。
 */
async function ask(page, text) {
  await fill(page, 'textarea', text)
  const btn = await page.waitForSelector('.send-btn:not([disabled])', { timeout: 15000 })
  await btn.click()
  // 等运行态出现：既确认这一轮真的开始了，也保证决策面板的「已完成」已被重置
  await page.waitForFunction(() => document.body.innerText.includes('停止'), { timeout: 20000 })
}

/** 按可见文字点击按钮（puppeteer 没有 :has-text 选择器） */
async function clickByText(page, text) {
  return page.$$eval(
    'button',
    (btns, t) => {
      const target = btns.find((b) => b.innerText.trim() === t)
      if (!target) return false
      target.click()
      return true
    },
    text
  )
}

/** 等写操作确认弹窗；模型偶尔会不调工具直接作答，所以调用方需要处理「没等到」 */
async function waitConfirm(page, timeout = 45000) {
  try {
    await page.waitForSelector('.confirm-card', { timeout })
    return true
  } catch {
    return false
  }
}

async function main() {
  console.log('前端 UI 冒烟检查\n')

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    defaultViewport: { width: 1440, height: 900 },
    // --no-proxy-server：这台机器上有透明代理，localhost 请求绝不能走它
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--no-proxy-server']
  })

  const page = await browser.newPage()

  page.on('console', (msg) => {
    if (msg.type() === 'error') {
      consoleErrors.push(msg.text())
    }
  })
  page.on('pageerror', (err) => consoleErrors.push(`[pageerror] ${err.message}`))
  page.on('requestfailed', (req) => {
    failedRequests.push(`${req.method()} ${req.url()} — ${req.failure()?.errorText}`)
  })
  page.on('response', (res) => {
    if (res.status() >= 400) {
      badResponses.push(`${res.status()} ${res.request().method()} ${res.url()}`)
    }
  })

  try {
    // ---------------- 1. 登录页 ----------------
    await page.goto(APP, { waitUntil: 'networkidle2', timeout: 60000 })
    await page.waitForSelector('input[placeholder="admin"]', { timeout: 30000 })
    await shot(page, 'login', '登录页')

    await fill(page, 'input[placeholder="admin"]', 'admin')
    await fill(page, 'input[placeholder="admin123"]', 'admin123')

    await clickByText(page, '登录')

    // ---------------- 2. 对话页 ----------------
    await page.waitForSelector('.example', { timeout: 30000 })
    await WAIT(600)
    await shot(page, 'chat-empty', '对话页初始状态（欢迎语 + 示例问题）')

    // ---------------- 3. 纯知识库问答 ----------------
    console.log('\n  ▸ 场景：知识库问答')
    await ask(page, '退款一般几天到账？')
    await waitDone(page)
    await WAIT(500)
    await shot(page, 'chat-rag', '纯 RAG 问答：引用角标 + 决策面板检索分数')

    // ---------------- 4. 只读工具调用 ----------------
    console.log('\n  ▸ 场景：只读工具（查物流）')
    await ask(page, '帮我查一下订单 202610010003 的物流到哪了')
    await waitDone(page)
    await WAIT(500)
    await shot(page, 'chat-tool', '工具调用：决策面板出现工具时间线')

    // ---------------- 5. 写操作二次确认 ----------------
    console.log('\n  ▸ 场景：写操作二次确认')
    await ask(page, '订单 202610010001 我要退款，商品有质量问题')
    if (!(await waitConfirm(page, 60000))) {
      throw new Error('退款场景没等到写操作确认弹窗')
    }
    await WAIT(800)
    await shot(page, 'confirm-modal', '写操作确认弹窗（含 60 秒倒计时）')

    const confirmButtons = await page.$$('.confirm-foot button')
    await confirmButtons[1].click() // 第二个是「确认执行」
    await waitDone(page)
    await WAIT(500)
    await shot(page, 'chat-confirmed', '确认后执行完成')

    // ---------------- 6. 写操作取消 ----------------
    console.log('\n  ▸ 场景：写操作取消')
    // 先清空对话：前几轮的上下文会让模型有时跳过工具直接作答，开一个新会话行为更稳定
    if (!(await clickByText(page, '清空对话'))) {
      console.log('    ! 未找到「清空对话」按钮，沿用当前会话')
    }
    await WAIT(600)
    await ask(page, '把订单 202610010005 的收货地址改成杭州市西湖区文三路 100 号')

    if (!(await waitConfirm(page, 45000))) {
      // 模型行为有随机性，这一轮没走工具不代表代码有问题 —— 记录并继续，不判失败
      console.log('    ! 本轮模型改为直接作答、没发起写操作，取消路径未覆盖')
      await shot(page, 'chat-cancel-skipped', '本轮未触发写操作（模型直接作答）')
    } else {
      await WAIT(800)
      await shot(page, 'confirm-modal-cancel', '取消前的确认弹窗')
      const cancelButtons = await page.$$('.confirm-foot button')
      await cancelButtons[0].click() // 第一个是「取消」
      await waitDone(page)
      await WAIT(500)
      await shot(page, 'chat-cancelled', '取消后：工具未执行，面板显示已取消')
    }

    // ---------------- 6. 知识库页 ----------------
    console.log('\n  ▸ 知识库页')
    await page.goto(`${APP}/#/kb`, { waitUntil: 'networkidle2', timeout: 60000 })
    await page.waitForSelector('.n-data-table', { timeout: 30000 })
    await WAIT(800)
    await shot(page, 'kb-list', '知识库文档列表')

    // 顺带跑一次召回测试，验证分数条渲染
    const searchInput = await page.$('.search-input input')
    if (searchInput) {
      await searchInput.click()
      await page.keyboard.type('退款一般几天到账')
      const searchBtns = await page.$$('.search-input button')
      if (searchBtns[1]) {
        await searchBtns[1].click()
      } else if (searchBtns[0]) {
        await searchBtns[0].click()
      }
      await page.waitForSelector('.search-result .hit', { timeout: 30000 })
      await WAIT(600)
      await shot(page, 'kb-search', '召回测试结果（分数条 + 阈值线）')
    }
  } catch (err) {
    console.error(`\n  ✗ 执行中断：${err.message}`)
    try {
      await shot(page, 'error-state', '出错时的页面状态')
    } catch {
      /* 忽略截图失败 */
    }
    process.exitCode = 1
  } finally {
    await browser.close()
  }

  // ---------------- 汇总 ----------------
  console.log(`\n${'='.repeat(62)}`)
  console.log(`  截图输出：${OUT_DIR}`)
  if (consoleErrors.length) {
    console.log(`\n  ⚠ 控制台错误 ${consoleErrors.length} 条：`)
    consoleErrors.slice(0, 10).forEach((e) => console.log(`    - ${e.slice(0, 160)}`))
    process.exitCode = 1
  } else {
    console.log('  ✓ 无控制台错误')
  }
  if (failedRequests.length) {
    const real = failedRequests.filter((u) => !u.includes('favicon'))
    if (real.length) {
      console.log(`\n  ⚠ 失败请求 ${real.length} 条：`)
      real.slice(0, 10).forEach((e) => console.log(`    - ${e.slice(0, 160)}`))
      process.exitCode = 1
    } else {
      console.log('  ✓ 无失败请求')
    }
  } else {
    console.log('  ✓ 无失败请求')
  }
  if (badResponses.length) {
    console.log(`\n  ⚠ 4xx/5xx 响应 ${badResponses.length} 条：`)
    badResponses.slice(0, 10).forEach((e) => console.log(`    - ${e.slice(0, 160)}`))
    process.exitCode = 1
  } else {
    console.log('  ✓ 无 4xx/5xx 响应')
  }
  console.log(`${'='.repeat(62)}`)
}

main()
