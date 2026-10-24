/**
 * 关键角色路由冒烟：登录、学员课程、教师学情、管理员用户。
 * 断言页面正文，并收集渲染期 errorHandler / console.error，而不只检查组件能被 import。
 */
import { describe, it, expect, vi, afterEach } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createApp } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import router from '../../src/router'
import { useUserStore } from '../../src/store/user'

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal()
  const ok = (data) => vi.fn(async () => data)
  return {
    ...actual,
    default: {
      get: ok({ count: 0, records: [], total: 0 }),
      post: ok({}),
      put: ok({}),
      delete: ok({}),
      interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } }
    },
    courseApi: {
      ...actual.courseApi,
      categories: ok([]),
      list: ok({ records: [], total: 0 }),
      search: ok([])
    },
    adminApi: {
      ...actual.adminApi,
      list: ok({ records: [], total: 0 })
    },
    teacherApi: {
      ...actual.teacherApi,
      list: ok({ records: [], total: 0 })
    },
    teacherStatsApi: {
      workbenchSummary: ok({ courseCount: 1, learners: 2, pendingQuestions: 0, aiReplies: 0, weeklyQuestions: 0 }),
      stats: ok({ records: [], total: 0 }),
      exportCsv: ok(new Blob())
    },
    authApi: {
      ...actual.authApi,
      me: ok({ username: 'u', nickname: '测试', roles: ['STUDENT'] }),
      logout: ok({})
    }
  }
})

const apps = []

async function openRoute(path, user) {
  localStorage.clear()
  const errors = []
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = useUserStore()
  store.token = user?.token || ''
  store.roles = user?.roles || []
  store.profile = user?.profile || null
  const app = createApp({ template: '<router-view />' })
  app.config.errorHandler = (err) => {
    errors.push(err instanceof Error ? err.message : String(err))
  }
  app.use(pinia).use(ElementPlus).use(router)
  const el = document.createElement('div')
  document.body.appendChild(el)
  const consoleErrors = []
  const spy = vi.spyOn(console, 'error').mockImplementation((...args) => {
    consoleErrors.push(args.map((a) => String(a?.message || a)).join(' '))
  })
  app.mount(el)
  apps.push({ app, el, spy })
  await router.isReady()
  await router.replace(path)
  await flushPromises()
  await flushPromises()
  return { text: el.textContent || '', errors, consoleErrors }
}

afterEach(() => {
  while (apps.length) {
    const { app, el, spy } = apps.pop()
    app.unmount()
    el.remove()
    spy.mockRestore()
  }
})

function assertHealthy(result, needle) {
  expect(result.text).toContain(needle)
  expect(result.errors).toEqual([])
  const fatal = result.consoleErrors.filter((line) => /is not defined|Unhandled|TypeError|ReferenceError/.test(line))
  expect(fatal).toEqual([])
}

describe('关键角色路由冒烟', () => {
  it('登录页渲染标题且无运行异常', async () => {
    const result = await openRoute('/login')
    assertHealthy(result, '课栈智伴')
    expect(result.text).toMatch(/登\s*录/)
  })

  it('学员课程页渲染课程浏览且无运行异常', async () => {
    const result = await openRoute('/courses', {
      token: 'student-token',
      roles: ['STUDENT'],
      profile: { username: 'student01', nickname: '学员', roles: ['STUDENT'] }
    })
    assertHealthy(result, '课程浏览')
  })

  it('教师学情页渲染统计标题且无 computed 异常', async () => {
    const result = await openRoute('/teacher/learning-stats', {
      token: 'teacher-token',
      roles: ['TEACHER'],
      profile: { username: 'teacher01', nickname: '教师', roles: ['TEACHER'] }
    })
    assertHealthy(result, '课程学情统计')
    expect(result.text).toContain('暂无学情数据')
  })

  it('管理员用户页渲染用户管理且无运行异常', async () => {
    const result = await openRoute('/admin/users', {
      token: 'admin-token',
      roles: ['ADMIN'],
      profile: { username: 'admin', nickname: '管理员', roles: ['ADMIN'] }
    })
    assertHealthy(result, '用户管理')
  })
})
