/**
 * 教师问答分页：翻页必须写入页码再请求。模板里对已解包的 page 写 .value 会抛 TypeError，请求不会发出。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const calls = []

vi.mock('../../src/api', () => ({
  default: {
    get: vi.fn(async (url, cfg) => {
      calls.push({ url, params: cfg?.params })
      const page = cfg?.params?.page || 1
      return {
        total: 11,
        records: [{ id: String(page), title: page === 1 ? '第一页问题' : '第二页问题', courseId: 1, answerTimes: 1, hidden: 0 }]
      }
    }),
    post: vi.fn(),
    patch: vi.fn()
  },
  teacherApi: {
    list: vi.fn(async () => ({ records: [{ id: 1, name: '演示课' }], total: 1 }))
  }
}))

import TeacherQuestionManageView from '../../src/views/TeacherQuestionManageView.vue'

beforeEach(() => {
  calls.length = 0
})

describe('TeacherQuestionManageView', () => {
  it('翻页更新页码并请求下一页', async () => {
    const wrapper = mount(TeacherQuestionManageView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('第一页问题')
    const pager = wrapper.findComponent({ name: 'ElPagination' })
    expect(pager.exists()).toBe(true)
    await pager.vm.$emit('current-change', 2)
    await flushPromises()
    expect(wrapper.text()).toContain('第二页问题')
    expect(calls.filter((c) => c.url === '/teacher/questions').map((c) => c.params.page)).toEqual([1, 2])
  })
})
