/**
 * M15.24：OrderDetailView 组件测试（生产组件直接挂载）——
 * 订单契约（order 平铺/enrollStatuses 四态）/取消/重试/登录失效 401。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const mocks = vi.hoisted(() => ({
  // 模拟 axios 拦截器解包后的 data（生产语义：R.code=200 → 返回 data）
  detailResp: null,
  cancelResp: null,
  retryResp: null,
  routerPush: vi.fn()
}))

vi.mock('../../src/api', () => ({
  default: Object.assign(vi.fn(), {
    interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } },
    get: vi.fn(),
    post: vi.fn()
  }),
  orderApi: {
    detail: vi.fn(() => Promise.resolve(mocks.detailResp)),
    cancel: vi.fn(() => Promise.resolve(mocks.cancelResp)),
    retryEnroll: vi.fn(() => Promise.resolve(mocks.retryResp))
  },
  teacherApi: {
    detail: vi.fn(() => Promise.resolve({ name: '测试课程' }))
  }
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '2113061587214991361' } }),
  useRouter: () => ({ push: mocks.routerPush })
}))

import ElementPlus from 'element-plus'
import OrderDetailView from '../../src/views/OrderDetailView.vue'
import { orderApi } from '../../src/api'

const paidWithSuccess = {
    order: {
      id: '2113061587214991361', status: 2, statusText: '已支付',
      totalAmountYuan: '199.00', realAmountYuan: '199.00', discountAmount: 0,
      createTime: '2026-10-22 11:19:00', message: '支付成功，已开课 1 门',
    items: [{ name: '测试课程', statusText: '已支付' }]
  },
  enrollStatuses: [{ courseId: '2112041767078014978', status: 'SUCCESS', attempts: 1, lastError: null }],
  enrollAllSuccess: true
}
const paidWithExhausted = JSON.parse(JSON.stringify(paidWithSuccess))
paidWithExhausted.order.message = '支付成功；开课失败 1 门（…），重试支付可补偿'
paidWithExhausted.enrollStatuses = [{ courseId: '2112041767078014978', status: 'FAILED_MANUAL', attempts: 3, lastError: 'learning down' }]
paidWithExhausted.enrollAllSuccess = false
const pendingOrder = { order: { id: 'X', status: 1, statusText: '待支付', totalAmountYuan: '1.00', realAmountYuan: '1.00', discountAmount: 0, createTime: '', message: '', items: [] }, enrollStatuses: [], enrollAllSuccess: false }

function mountView() {
  return mount(OrderDetailView, {
    global: { plugins: [createPinia(), ElementPlus] },
    attachTo: document.body
  })
}

describe('OrderDetailView（生产组件·契约）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    mocks.detailResp = paidWithSuccess
  })

  it('① 已支付订单：order 平铺字段渲染（订单号/金额/明细）', async () => {
    const w = mountView()
    await flushPromises()
    const text = w.text()
    expect(text).toContain('2113061587214991361')
    expect(text).toContain('199.00')
    expect(text).toContain('测试课程')
    expect(text).toContain('已开课') // enrollStatuses 四态 tag
  })

  it('② 开课失败（FAILED_MANUAL）：显示重试开课按钮', async () => {
    mocks.detailResp = paidWithExhausted
    const w = mountView()
    await flushPromises()
    expect(w.text()).toContain('重试耗尽')
    const btn = w.findAll('button').find((b) => b.text().includes('重试开课'))
    expect(btn).toBeTruthy()
  })

  it('③ 重试开课点击：调 retryEnroll 并刷新', async () => {
    mocks.detailResp = paidWithExhausted
    const w = mountView()
    await flushPromises()
    const btn = w.findAll('button').find((b) => b.text().includes('重试开课'))
    await btn.trigger('click')
    await flushPromises()
    expect(orderApi.retryEnroll).toHaveBeenCalledWith('2113061587214991361')
  })

  it('④ 待支付订单：无重试按钮、有取消按钮', async () => {
    mocks.detailResp = pendingOrder
    const w = mountView()
    await flushPromises()
    expect(w.findAll('button').find((b) => b.text().includes('重试开课'))).toBeFalsy()
    expect(w.findAll('button').find((b) => b.text().includes('取消订单'))).toBeTruthy()
  })

  it('⑤ 登录失效（401）：detail 请求 reject，页面不渲染订单数据', async () => {
    orderApi.detail.mockImplementationOnce(() => Promise.reject(new Error('登录已失效')))
    const w = mountView()
    await flushPromises()
    // 无 order 数据 → 不渲染订单号（错误态，不默认成功）
    expect(w.text()).not.toContain(paidWithSuccess.order.id)
  })
})

describe('OrderDetailView（契约回归）', () => {
  it('⑥ enrollStatuses 缺失=待核实（不默认成功）', async () => {
    const noStatus = { order: paidWithSuccess.order, enrollStatuses: null, enrollAllSuccess: true }
    mocks.detailResp = noStatus
    const w = mountView()
    await flushPromises()
    // 缺失状态 → 重试按钮显示（待核实入口）
    expect(w.findAll('button').find((b) => b.text().includes('重试开课'))).toBeTruthy()
  })
})
