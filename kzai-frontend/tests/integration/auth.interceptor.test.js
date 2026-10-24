/**
 * M15.25-二：认证失效集成测试（真实 api/index.js 拦截器+tokenStore——网络层 adapter mock）。
 * 覆盖：业务 code=401 / HTTP 401 清理+跳登录；403 不清登录态；网络异常透传；并发 401 幂等跳转。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.stubGlobal('location', { hash: '#/orders/1', href: '' })

const adapterResponses = []
function makeAdapter() {
  return vi.fn((config) => {
    const spec = adapterResponses.shift()
    if (!spec) return Promise.reject(new Error('no mock'))
    if (spec.reject) {
      const err = new Error(spec.message || 'fail')
      err.response = spec.response
      err.config = config
      err.code = spec.code
      return Promise.reject(err)
    }
    return Promise.resolve({ status: spec.status || 200, data: spec.data, config, headers: {} })
  })
}

let capturedMsg = []

vi.mock('element-plus', () => ({
  ElMessage: {
    error: vi.fn((m) => { capturedMsg.push(m) }),
    success: vi.fn(), warning: vi.fn(), info: vi.fn()
  }
}))

import request, { tokenStore } from '../../src/api'

describe('认证失效集成（真实拦截器）', () => {
  beforeEach(() => {
    adapterResponses.length = 0
    capturedMsg = []
    vi.spyOn(tokenStore, 'clear') // spy 真实 clear（实现保留——清理 localStorage）
    tokenStore.set('mock-token')
    window.location.hash = '#/orders/1'
  })
  afterEach(() => {
    localStorage.removeItem('kzai_token')
    vi.restoreAllMocks()
  })

  it('超时转为可理解的提示，保留错误类型且只由拦截器通知一次', async () => {
    request.defaults.adapter = makeAdapter()
    adapterResponses.push({ reject: true, code: 'ECONNABORTED', message: 'timeout of 30000ms exceeded' })
    await expect(request.get('/teacher/learning-stats')).rejects.toMatchObject({
      message: '请求超时，请稍后重试', code: 'ECONNABORTED', notified: true
    })
    expect(capturedMsg).toEqual(['请求超时，请稍后重试'])
    expect(tokenStore.get()).toBe('mock-token')
  })

  it('① 业务 code=401：token 清理+跳转登录+错误上抛', async () => {
    request.defaults.adapter = makeAdapter()
    adapterResponses.push({ data: { code: 401, msg: '登录已失效', data: null } })
    await expect(request.get('/users/me')).rejects.toThrow('登录已失效')
    expect(tokenStore.clear).toHaveBeenCalled()
    expect(window.location.hash).toBe('#/login')
  })

  it('② HTTP 401（响应错误分支）：token 清理+跳转', async () => {
    request.defaults.adapter = makeAdapter()
    adapterResponses.push({ reject: true, response: { status: 401, data: { code: 401, msg: '未登录' } } })
    await expect(request.get('/users/me')).rejects.toThrow()
    expect(tokenStore.clear).toHaveBeenCalled()
    expect(window.location.hash).toBe('#/login')
  })

  it('③ 业务 403：错误上抛但不清登录态、不跳转', async () => {
    request.defaults.adapter = makeAdapter()
    adapterResponses.push({ data: { code: 403, msg: '无权限' } })
    await expect(request.get('/admin/dashboard')).rejects.toThrow('无权限')
    expect(tokenStore.clear).not.toHaveBeenCalled()
    expect(window.location.hash).toBe('#/orders/1')
  })

  it('④ 网络异常：服务端原因透传、不清登录态', async () => {
    request.defaults.adapter = makeAdapter()
    adapterResponses.push({ reject: true, response: { status: 500, data: { code: 500, msg: '服务端内部错误' } } })
    await expect(request.get('/courses')).rejects.toThrow('服务端内部错误')
    expect(tokenStore.clear).not.toHaveBeenCalled()
  })

  it('⑤ 并发 401：全部拒绝、清理幂等、跳转一次语义', async () => {
    request.defaults.adapter = makeAdapter()
    adapterResponses.push(
      { data: { code: 401, msg: '失效1' } },
      { data: { code: 401, msg: '失效2' } }
    )
    const results = await Promise.allSettled([
      request.get('/users/me'),
      request.get('/notifications')
    ])
    expect(results.every((r) => r.status === 'rejected')).toBe(true)
    expect(tokenStore.clear).toHaveBeenCalled()
    expect(window.location.hash).toBe('#/login')
  })
})
