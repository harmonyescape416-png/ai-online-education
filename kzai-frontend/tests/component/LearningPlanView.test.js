import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const { plans, pausePlan, list } = vi.hoisted(() => ({
  plans: vi.fn(),
  pausePlan: vi.fn(async () => ({})),
  list: vi.fn(async () => ({ records: [] }))
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() })
}))

vi.mock('../../src/api', () => ({
  learningApi: {
    plans,
    pausePlan,
    list,
    joinPlan: vi.fn(),
    changePlanGoal: vi.fn(),
    resumePlan: vi.fn(),
    quitPlan: vi.fn()
  }
}))

import LearningPlanView from '../../src/views/LearningPlanView.vue'

describe('LearningPlanView', () => {
  it('空计划展示没有计划，不使用本地假数据', async () => {
    plans.mockResolvedValueOnce({
      summary: 'NONE',
      summaryText: '没有计划',
      coverageText: '本周完成量按上海时区首次完成的章节统计',
      weekStart: '2026-10-19',
      weekEndExclusive: '2026-10-26',
      timezone: 'Asia/Shanghai',
      plans: []
    })
    const wrapper = mount(LearningPlanView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('没有计划')
    expect(plans).toHaveBeenCalled()
  })

  it('暂停调用计划接口', async () => {
    plans.mockResolvedValue({
      summary: 'PARTIAL',
      summaryText: '部分完成',
      coverageText: '本周完成量按上海时区首次完成的章节统计',
      weekStart: '2026-10-19',
      weekEndExclusive: '2026-10-26',
      timezone: 'Asia/Shanghai',
      plans: [{
        id: '8', courseId: '6001', courseName: '计划课', status: 'ACTIVE', statusText: '进行中',
        weeklyGoal: 2, goalEffectiveWeek: '2026-10-19', finishedThisWeek: 1, remaining: 1, ratio: 50,
        weekStatus: 'PARTIAL', weekStatusText: '部分完成', canContinue: true, continuePath: '/learn/6001'
      }]
    })
    const wrapper = mount(LearningPlanView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    const button = wrapper.findAll('button').find((item) => item.text() === '暂停')
    await button.trigger('click')
    await flushPromises()
    expect(pausePlan).toHaveBeenCalledWith('8')
  })
})
