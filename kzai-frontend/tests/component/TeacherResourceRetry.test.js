import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '9' } })
}))

const { retryKb, submitKb } = vi.hoisted(() => ({
  retryKb: vi.fn(async () => ({ jobId: '77', phase: 'INDEXING', alreadyQueued: false })),
  submitKb: vi.fn(async () => ({}))
}))

vi.mock('../../src/api', () => ({
  resourceApi: {
    list: vi.fn(async () => [{
      id: '3', fileName: '讲义.md', kindText: '教学资料', fileSizeText: '1 KB',
      kbPhase: 'FAILED', kbRetryable: true, kbFailReason: 'ES 写入失败'
    }]),
    unbound: vi.fn(async () => []),
    retryKb,
    submitKb,
    history: vi.fn(async () => [])
  },
  chapterApi: { list: vi.fn(async () => []) },
  teacherApi: { detail: vi.fn(async () => ({ name: '课' })) }
}))

import TeacherResourceView from '../../src/views/TeacherResourceView.vue'

describe('TeacherResourceView 重试', () => {
  it('失败且允许重试时调用 retryKb，不调用首次提交', async () => {
    const wrapper = mount(TeacherResourceView, { global: { plugins: [ElementPlus] } })
    await flushPromises()
    const button = wrapper.findAll('button').find((b) => b.text() === '重试索引')
    expect(button).toBeTruthy()
    await button.trigger('click')
    await flushPromises()
    expect(retryKb).toHaveBeenCalledWith('3')
    expect(submitKb).not.toHaveBeenCalled()
  })
})
