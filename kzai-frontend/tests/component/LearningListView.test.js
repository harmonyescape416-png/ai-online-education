import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const { push, list } = vi.hoisted(() => ({
  push: vi.fn(() => Promise.resolve()),
  list: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push })
}))

vi.mock('../../src/api', () => ({
  learningApi: { list }
}))

import LearningListView from '../../src/views/LearningListView.vue'

const lessons = [
  {
    id: '1',
    courseId: '1004',
    courseName: 'RAG 检索增强生成实践',
    status: 1,
    statusText: '学习中',
    free: false,
    entitlementValid: false,
    progress: 0,
    finishedChapterCount: 0,
    totalChapterCount: 2,
    lastChapterId: '11',
    lastChapterName: '第1章 RAG 架构总览',
    studySeconds: 0
  },
  {
    id: '2',
    courseId: '1002',
    courseName: 'Spring Cloud Alibaba 进阶',
    status: 2,
    statusText: '已完成',
    free: false,
    entitlementValid: true,
    progress: 100,
    finishedChapterCount: 3,
    totalChapterCount: 3,
    lastChapterId: '33',
    lastChapterName: '第3章 分布式事务',
    studySeconds: 0
  }
]

describe('LearningListView', () => {
  it('开始学习和继续学习都进入对应课程章节', async () => {
    list.mockResolvedValueOnce({ records: lessons, total: 2 })
    const wrapper = mount(LearningListView, { global: { plugins: [ElementPlus] } })
    await flushPromises()

    const start = wrapper.get('a.learn-entry')
    expect(start.text()).toBe('开始学习')
    expect(start.attributes('href')).toBe('#/learn/1004?chapterId=11')
    await start.trigger('click')
    expect(push).toHaveBeenCalledWith('/learn/1004?chapterId=11')

    const resume = wrapper.findAll('a.learn-entry')[1]
    expect(resume.text()).toBe('继续学习第3章')
    expect(resume.attributes('href')).toBe('#/learn/1002?chapterId=33')
    await resume.trigger('click')
    expect(push).toHaveBeenCalledWith('/learn/1002?chapterId=33')
  })
})
