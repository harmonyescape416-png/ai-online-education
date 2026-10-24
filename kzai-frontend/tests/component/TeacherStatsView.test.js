/**
 * 教师学情统计：挂载后必须渲染标题与摘要，分页按服务端每页 5 门减少表格节点，
 * 筛选回到第 1 页，失败只显示重试，章节/学员明细默认不挂载。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ElementPlus from 'element-plus'

const allCourses = Array.from({ length: 7 }, (_, i) => ({
  courseId: i + 1,
  courseName: `课程${i + 1}`,
  free: i % 2 === 0,
  learnerCount: 10 + i,
  finishedLearnerCount: i,
  finishRate: i,
  chapterCount: 2,
  totalStudySeconds: 90,
  avgStudySeconds: 9,
  lastStudyTime: '2026-10-01T08:00:00',
  interactionReady: false,
  chapterStats: [{ chapterOrder: 1, chapterName: `第${i + 1}章`, learnerCount: 1, finishedCount: 0, finishRate: 0, totalStudySeconds: 3 }],
  recentStudies: [{ username: `学员${i + 1}`, chapterName: `第${i + 1}章`, status: 1, statusText: '学习中', progress: 20, studySeconds: 4, lastStudyTime: '2026-10-01T08:00:00' }]
}))

const mocks = vi.hoisted(() => ({
  fail: false,
  empty: false,
  calls: []
}))

vi.mock('../../src/api', () => ({
  teacherApi: {
    list: vi.fn(async () => ({ records: [{ id: 11, name: '演示课' }], total: 1 }))
  },
  teacherStatsApi: {
    workbenchSummary: vi.fn(async () => ({
      courseCount: 7, learners: 12, pendingQuestions: 3, aiReplies: 4, weeklyQuestions: 5
    })),
    stats: vi.fn(async (params) => {
      mocks.calls.push(params)
      if (mocks.fail) throw new Error('学情接口失败')
      const pageNum = params.pageNum || 1
      const pageSize = params.pageSize || 5
      const source = params.courseId
        ? allCourses.filter((c) => String(c.courseId) === String(params.courseId))
        : allCourses
      if (mocks.empty) return { records: [], total: 0 }
      const start = (pageNum - 1) * pageSize
      return { records: source.slice(start, start + pageSize), total: source.length }
    }),
    exportCsv: vi.fn(async () => new Blob(['课程,学习人数\n演示课,1\n'], { type: 'text/csv' }))
  }
}))

import TeacherStatsView from '../../src/views/TeacherStatsView.vue'
import { teacherStatsApi } from '../../src/api'

function mountView() {
  return mount(TeacherStatsView, { global: { plugins: [ElementPlus] } })
}

beforeEach(() => {
  mocks.fail = false
  mocks.empty = false
  mocks.calls = []
  vi.stubGlobal('URL', Object.assign(URL, {
    createObjectURL: vi.fn(() => 'blob:stats'),
    revokeObjectURL: vi.fn()
  }))
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
})

describe('TeacherStatsView', () => {
  it('挂载后显示摘要、口径和当前页课程，且默认不渲染明细表', async () => {
    const wrapper = mountView()
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('课程学情统计')
    expect(text).toContain('我的课程')
    expect(text).toContain('学习人数')
    expect(text).toContain('7')
    expect(text).toContain('累计口径')
    const titles = wrapper.findAll('.kz-stats__title').map((n) => n.text())
    expect(titles).toEqual(['课程1', '课程2', '课程3', '课程4', '课程5'])
    expect(wrapper.findAll('.teacher-stats__course')).toHaveLength(5)
    expect(wrapper.findAll('.el-table')).toHaveLength(0)
    expect(mocks.calls[0]).toMatchObject({ pageNum: 1, pageSize: 5 })
    const nodes = wrapper.element.querySelectorAll('*').length
    expect(nodes).toBeGreaterThan(0)
    expect(nodes).toBeLessThan(2500)
  })

  it('翻页只渲染下一页，展开后才出现章节和学员表', async () => {
    const wrapper = mountView()
    await flushPromises()
    const before = wrapper.element.querySelectorAll('*').length
    const pager = wrapper.findComponent({ name: 'ElPagination' })
    expect(pager.exists()).toBe(true)
    await pager.vm.$emit('current-change', 2)
    await flushPromises()
    expect(wrapper.findAll('.kz-stats__title').map((n) => n.text())).toEqual(['课程6', '课程7'])
    expect(wrapper.findAll('.teacher-stats__course')).toHaveLength(2)
    expect(wrapper.findAll('.el-table')).toHaveLength(0)
    const afterPage = wrapper.element.querySelectorAll('*').length
    expect(afterPage).toBeLessThan(before)

    await wrapper.findAll('button').find((b) => b.text() === '展开章节').trigger('click')
    expect(wrapper.findAll('.el-table')).toHaveLength(1)
    expect(wrapper.text()).toContain('第6章')
    await wrapper.findAll('button').find((b) => b.text() === '展开学员').trigger('click')
    expect(wrapper.findAll('.el-table')).toHaveLength(2)
    expect(wrapper.text()).toContain('学员6')
  })

  it('筛选后回到第 1 页，空数据不显示失败态', async () => {
    const wrapper = mountView()
    await flushPromises()
    const pager = wrapper.findComponent({ name: 'ElPagination' })
    await pager.vm.$emit('current-change', 2)
    await flushPromises()
    const select = wrapper.findComponent({ name: 'ElSelect' })
    await select.vm.$emit('update:modelValue', '1')
    await select.vm.$emit('change', '1')
    await flushPromises()
    const last = mocks.calls.at(-1)
    expect(last).toMatchObject({ pageNum: 1, pageSize: 5, courseId: '1' })
    expect(wrapper.findAll('.kz-stats__title').map((n) => n.text())).toEqual(['课程1'])

    mocks.empty = true
    await wrapper.findAll('button').find((b) => b.text() === '查询').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂无学情数据')
    expect(wrapper.text()).not.toContain('学情数据加载失败')
    expect(wrapper.findComponent({ name: 'ElPagination' }).exists()).toBe(false)
  })

  it('请求失败显示重试且不显示空数据，重试后恢复', async () => {
    const wrapper = mountView()
    await flushPromises()
    mocks.fail = true
    await wrapper.findAll('button').find((b) => b.text() === '查询').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('学情数据加载失败')
    expect(wrapper.text()).not.toContain('暂无学情数据')
    expect(wrapper.findAll('.teacher-stats__course')).toHaveLength(0)
    mocks.fail = false
    await wrapper.findAll('button').find((b) => b.text() === '重试').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('课程1')
    expect(wrapper.text()).not.toContain('学情数据加载失败')
  })

  it('导出沿用当前课程筛选，不带分页截断', async () => {
    const wrapper = mountView()
    await flushPromises()
    const select = wrapper.findComponent({ name: 'ElSelect' })
    await select.vm.$emit('update:modelValue', '11')
    await wrapper.findAll('button').find((b) => b.text() === '导出 CSV').trigger('click')
    await flushPromises()
    expect(teacherStatsApi.exportCsv).toHaveBeenCalledWith({ courseId: '11' })
  })
})
