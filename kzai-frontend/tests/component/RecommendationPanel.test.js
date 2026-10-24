import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../src/api', () => ({ default: mocks }))
import Panel from '../../src/components/RecommendationPanel.vue'
const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }, { path: '/courses/:id', name: 'course-detail', component: { template: '<div />' } }] })
beforeEach(() => {
  mocks.get.mockReset().mockResolvedValue({ goal: '学习接口开发', foundation: 'BEGINNER', budgetCents: 10000 })
  mocks.post.mockReset().mockResolvedValue({ status: 'OK', retrievalMode: 'KEYWORD_FALLBACK', message: '已降级为关键词查课', courses: [{ courseId: '11', name: '真实课程', priceYuan: '99.00', price: 9900, free: 0, purchasable: true, recommendationEvidence: ['公开适用人群：零基础'], recommendationWarnings: ['时长未登记'] }] })
})
async function open() {
  const w = mount(Panel, { props: { sessionId: 'mine' }, global: { plugins: [ElementPlus, router] } })
  await flushPromises()
  await w.find('.el-collapse-item__header').trigger('click')
  await flushPromises()
  return w
}
describe('课程推荐入口', () => {
  it('回读会话条件，展示真实卡片、证据和关键词降级状态', async () => {
    const w = await open()
    expect(w.find('input[placeholder*="例如"]').element.value).toBe('学习接口开发')
    await w.findAll('button').find(b => b.text() === '查找课程').trigger('click')
    await flushPromises()
    expect(mocks.post.mock.calls[0][1].preferences.budgetCents).toBe(10000)
    expect(w.text()).toContain('关键词查课（已降级）')
    expect(w.text()).toContain('公开适用人群：零基础')
    expect(w.text()).toContain('¥99.00')
    await w.findAll('button').find(b => b.text() === '选择课程').trigger('click')
    expect(w.emitted('ask')[0][0]).toContain('核对当前价格并展示购买确认')
    expect(mocks.post.mock.calls.map(c => c[0])).toEqual(['/chat/recommendations'])
    w.unmount()
  })
  it('没有匹配时展示真实空结果，切换会话不残留旧卡片', async () => {
    const w = await open()
    mocks.post.mockResolvedValueOnce({ status: 'NOT_FOUND', retrievalMode: 'SEMANTIC', message: '没有符合条件的课程', courses: [] })
    await w.findAll('button').find(b => b.text() === '查找课程').trigger('click'); await flushPromises()
    expect(w.text()).toContain('没有符合条件的课程')
    expect(w.text()).toContain('已完成语义查询')
    expect(w.findAll('.course-card')).toHaveLength(0)
    await w.setProps({ sessionId: 'other' }); await flushPromises()
    expect(mocks.get).toHaveBeenLastCalledWith('/chat/recommendations/other')
    expect(w.text()).not.toContain('没有符合条件的课程')
    w.unmount()
  })
  it('查询不完整时说明故障与下一步，不展示内部状态和统计', async () => {
    const w = await open()
    mocks.post.mockResolvedValueOnce({ status: 'SEARCH_INCOMPLETE', retrievalMode: 'KEYWORD_FALLBACK',
      message: '语义推荐暂不可用，已降级为关键词查询。', nextAction: '请稍后重试。',
      reasonCodes: ['SEMANTIC_UNAVAILABLE'], diagnostics: { namespace: 'internal-index' }, courses: [] })
    await w.findAll('button').find(b => b.text() === '查找课程').trigger('click'); await flushPromises()
    expect(w.text()).toContain('查询未完整完成'); expect(w.text()).toContain('请稍后重试。')
    expect(w.text()).not.toContain('SEMANTIC_UNAVAILABLE'); expect(w.text()).not.toContain('internal-index')
    w.unmount()
  })
  it('没有新课时展示已购课程及学习入口，不允许再次选择购买', async () => {
    const w = await open()
    mocks.post.mockResolvedValueOnce({ status: 'NOT_FOUND', retrievalMode: 'SEMANTIC', courses: [], count: 0,
      message: '有候选已在你的已购或学习列表中', nextAction: '可打开已购课程卡片查看详情和学习入口。',
      ownedCourses: [{ courseId: '13', name: '真实AI课程', owned: true, completed: true, priceYuan: '99.00', free: 0, purchasable: true, recommendationEvidence: ['课程简介：大模型应用'] }] })
    await w.findAll('button').find(b => b.text() === '查找课程').trigger('click'); await flushPromises()
    expect(w.text()).toContain('你已购或已加入学习的相关课程')
    expect(w.text()).toContain('真实AI课程'); expect(w.text()).toContain('已完成')
    expect(w.findAll('.course-card')).toHaveLength(1)
    expect(w.findAll('button').find(b => b.text() === '选择课程').element.disabled).toBe(true)
    await w.findAll('button').find(b => b.text() === '查看学习入口').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.name).toBe('course-detail'); expect(router.currentRoute.value.params.id).toBe('13')
    expect(w.emitted('ask')).toBeUndefined()
    await w.setProps({ sessionId: 'other' }); await flushPromises()
    expect(w.findAll('.course-card')).toHaveLength(0)
    w.unmount()
  })
})
