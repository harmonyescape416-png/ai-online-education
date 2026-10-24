import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))
vi.mock('../../src/api', () => ({ default: { get: mocks.get } }))

import Dashboard from '../../src/views/AdminDashboardView.vue'
import Questions from '../../src/views/AdminQuestionsSupervisionView.vue'

beforeEach(() => { mocks.get.mockReset() })

describe('管理员页面加载失败后的恢复', () => {
  it('运营看板捕获已通知的超时，显示重试入口并能恢复真实指标', async () => {
    mocks.get.mockRejectedValueOnce(Object.assign(new Error('请求超时，请稍后重试'), { notified: true }))
    const wrapper = mount(Dashboard, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('请求超时，请稍后重试')
    mocks.get.mockResolvedValueOnce({ userCount: 12, courseCount: 3, todayQuestions: 7, aigcAvailable: true, learningAvailable: true })
    await wrapper.findAll('button').find(b => b.text() === '刷新').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('请求超时，请稍后重试')
    expect(wrapper.findAll('.kpi-v').map(e => e.text()).slice(0, 2)).toEqual(['12', '3'])
    wrapper.unmount()
  })

  it('问答监管捕获超时后能重新查询，提问人缺少名称时正确回退', async () => {
    mocks.get.mockRejectedValueOnce(Object.assign(new Error('请求超时，请稍后重试'), { notified: true }))
    const wrapper = mount(Questions, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('请求超时，请稍后重试')
    mocks.get.mockResolvedValueOnce({ total: 1, records: [{ id: '90', title: '真实问答', userId: '3', courseId: '7', answerTimes: 1 }] })
    await wrapper.findAll('button').find(b => b.text() === '查询').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('请求超时，请稍后重试')
    expect(wrapper.text()).toContain('真实问答')
    expect(wrapper.text()).toContain('用户 3')
    wrapper.unmount()
  })
})
