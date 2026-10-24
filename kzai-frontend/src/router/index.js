import { createRouter, createWebHashHistory, useRouter } from 'vue-router'
import { h } from 'vue'
import { ElButton } from 'element-plus'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../store/user'
import MainLayout from '../layout/MainLayout.vue'

const routes = [
  {
    path: '/register',
    name: 'register',
    component: () => import('../views/RegisterView.vue'),
    meta: { public: true },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue')
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/courses',
    children: [
      {
        path: 'courses',
        name: 'courses',
        component: () => import('../views/CourseListView.vue')
      },
      {
        path: 'courses/:id',
        name: 'course-detail',
        component: () => import('../views/CourseDetailView.vue')
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('../views/ProfileView.vue')
      },
      {
        // M6：AI 工作台
        path: 'ai',
        name: 'ai',
        component: () => import('../views/AiWorkbenchView.vue')
      },
      {
        // M2：管理员用户管理，后端同样做接口鉴权，此处仅为前端拦截
        path: 'admin/refunds',
        name: 'admin-refunds',
        component: () => import('../views/AdminRefundView.vue'),
        meta: { roles: ['ADMIN', 'STAFF'] }
      },
      {
        path: 'admin/coupons',
        name: 'admin-coupons',
        component: () => import('../views/AdminCouponView.vue'),
        meta: { roles: ['ADMIN', 'STAFF'] }
      },
      {
        path: 'coupons',
        name: 'coupons',
        component: () => import('../views/CouponCenterView.vue'),
        meta: { roles: ['STUDENT', 'ADMIN'] }
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('../views/AdminUserView.vue'),
        meta: { roles: ['ADMIN', 'STAFF'] }
      },
      // M3：教师课程管理（后端 @RequireRoles TEACHER/ADMIN + 课程归属校验）
      {
        path: 'teacher/courses',
        name: 'teacher-courses',
        component: () => import('../views/TeacherCourseListView.vue'),
        meta: { roles: ['TEACHER', 'ADMIN'] }
      },
      {
        path: 'teacher/courses/new',
        name: 'teacher-course-new',
        component: () => import('../views/TeacherCourseEditView.vue'),
        meta: { roles: ['TEACHER', 'ADMIN'] }
      },
      {
        path: 'teacher/courses/:id/edit',
        name: 'teacher-course-edit',
        component: () => import('../views/TeacherCourseEditView.vue'),
        meta: { roles: ['TEACHER', 'ADMIN'] }
      },
      {
        path: 'teacher/courses/:id/chapters',
        name: 'teacher-chapters',
        component: () => import('../views/TeacherChapterView.vue'),
        meta: { roles: ['TEACHER', 'ADMIN'] }
      },
      {
        path: 'teacher/courses/:id/resources',
        name: 'teacher-resources',
        component: () => import('../views/TeacherResourceView.vue'),
        meta: { roles: ['TEACHER', 'ADMIN'] }
      },
      // M5：教师学情统计
      {
        path: 'teacher/learning-stats',
        name: 'teacher-learning-stats',
        component: () => import('../views/TeacherStatsView.vue'),
        meta: { roles: ['TEACHER', 'ADMIN'] }
      },
      // M5：我的学习与课程学习（学员）
      {
        path: 'learning',
        name: 'learning',
        component: () => import('../views/LearningListView.vue'),
        meta: { roles: ['STUDENT', 'ADMIN'] }
      },
      {
        path: 'learning/points',
        name: 'learning-points',
        component: () => import('../views/PointCenterView.vue'),
        meta: { roles: ['STUDENT', 'ADMIN'] }
      },
      {
        path: 'learning/plans',
        name: 'learning-plans',
        component: () => import('../views/LearningPlanView.vue'),
        meta: { roles: ['STUDENT', 'ADMIN'] }
      },
      {
        path: 'learn/:courseId',
        name: 'course-learn',
        component: () => import('../views/CourseLearnView.vue'),
        meta: { roles: ['STUDENT', 'ADMIN'] }
      },
      // M4：订单（学员）
      {
        path: 'orders',
        name: 'orders',
        component: () => import('../views/OrderListView.vue'),
        meta: { roles: ['STUDENT', 'ADMIN'] }
      },
      {
        path: 'orders/confirm',
        name: 'order-confirm',
        component: () => import('../views/OrderConfirmView.vue'),
        meta: { roles: ['STUDENT', 'ADMIN'] }
      },
      {
        path: 'orders/:id',
        name: 'order-detail',
        component: () => import('../views/OrderDetailView.vue'),
        meta: { roles: ['STUDENT', 'ADMIN'] }
      },
      // M3：管理员分类管理与课程审核
      {
        path: 'admin/categories',
        name: 'admin-categories',
        component: () => import('../views/AdminCategoryView.vue'),
        meta: { roles: ['ADMIN', 'STAFF'] }
      },
      {
        path: 'admin/course-audits',
        name: 'admin-course-audits',
        component: () => import('../views/AdminCourseAuditView.vue'),
        meta: { roles: ['ADMIN', 'STAFF'] }
      },
      // M8：管理员 AI Prompt 模板管理（仅 ADMIN）
      {
        path: 'admin/ai/prompts',
        name: 'admin-ai-prompts',
        component: () => import('../views/PromptAdminView.vue'),
        meta: { roles: ['ADMIN'] }
      },
      // M9：管理员知识库管理（仅 ADMIN）
      {
        path: 'admin/ai/knowledge',
        name: 'admin-ai-knowledge',
        component: () => import('../views/KnowledgeAdminView.vue'),
        meta: { roles: ['ADMIN'] }
      },
      // M12：管理员 AI 批量评测看板（仅 ADMIN）
      {
        path: 'admin/ai/evaluations',
        name: 'admin-ai-evaluations',
        component: () => import('../views/AdminEvaluationsView.vue'),
        meta: { roles: ['ADMIN'] }
      },
      // M13：运营看板 / 监管 / 审计 / 参数 / Outbox 运维（仅 ADMIN）
      {
        path: 'admin/dashboard',
        name: 'admin-dashboard',
        component: () => import('../views/AdminDashboardView.vue'),
        meta: { roles: ['ADMIN'] }
      },
      {
        path: 'admin/supervision/sessions',
        name: 'admin-sessions',
        component: () => import('../views/AdminSessionsSupervisionView.vue'),
        meta: { roles: ['ADMIN'] }
      },
      {
        path: 'admin/supervision/questions',
        name: 'admin-questions-supervision',
        component: () => import('../views/AdminQuestionsSupervisionView.vue'),
        meta: { roles: ['ADMIN'] }
      },
      {
        path: 'admin/operation-logs',
        name: 'admin-operation-logs',
        component: () => import('../views/AdminOperationLogsView.vue'),
        meta: { roles: ['ADMIN'] }
      },
      {
        path: 'admin/system-settings',
        name: 'admin-system-settings',
        component: () => import('../views/AdminSystemSettingsView.vue'),
        meta: { roles: ['ADMIN'] }
      },
      {
        path: 'admin/outbox',
        name: 'admin-outbox',
        component: () => import('../views/AdminOutboxView.vue'),
        meta: { roles: ['ADMIN'] }
      },
      // M10：课程问答中心（登录用户）
      {
        path: 'questions',
        name: 'question-center',
        component: () => import('../views/QuestionCenterView.vue')
      },
      {
        // M11：站内通知中心
        path: 'notifications',
        name: 'notifications',
        component: () => import('../views/NotificationCenterView.vue'),
        meta: { title: '通知中心' }
      },
      // M10：教师问答管理（教师/管理员）
      {
        path: 'teacher/questions',
        name: 'teacher-questions',
        component: () => import('../views/TeacherQuestionManageView.vue'),
        meta: { roles: ['TEACHER', 'ADMIN'] }
      }
    ]
  },
  // M15.42：404 兜底——未知路径显示「页面不存在」（不再静默重定向课程页/控制台 No match）
  {
    path: '/:pathMatch(.*)*',
    component: {
      setup() {
        const store = useUserStore()
        const router = useRouter()
        function goHome() {
          if (store.token && store.roles && store.roles.length) {
            router.push(ROLE_HOME[store.roles[0]] || '/courses')
          } else {
            router.push('/login')
          }
        }
        return () => h('div', { style: 'min-height: 60vh; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 14px' }, [
          h('div', { style: 'font-size: 56px; font-weight: 700; color: #4F46E5' }, '404'),
          h('div', { style: 'font-size: 18px; color: var(--kz-text-secondary)' }, '页面不存在'),
          h(ElButton, { type: 'primary', onClick: goHome }, () => '返回首页')
        ])
      }
    }
  }
]

import { roleAllowed, ROLE_HOME } from './menuConfig'

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach(async (to) => {
  if (to.path === '/login' || to.path === '/register') return true
  const store = useUserStore()
  if (!store.token) return { path: '/login', query: { redirect: to.fullPath } }
  if (!store.profile) {
    try {
      await store.fetchProfile()
    } catch {
      return { path: '/login' }
    }
  }
  // M15.14-R8/R9：角色-菜单权限校验（配置静态引入，消除动态 import 失败导致的导航中止）
  // 守卫全量兜底：任何异常不产生 Unhandled rejection（会阻断 el-menu 导航，表现为点击无响应）
  try {
    const isNotFoundRoute = (to.matched.at(-1)?.path || '').includes(':pathMatch')
    if (isNotFoundRoute) return true // M15.42：404 兜底路由放行（页面自带返回首页，不再弹回课程页）
    if (!roleAllowed(to.path, store.roles)) {
      const home = ROLE_HOME[store.roles[0]] || '/courses'
      ElMessage.warning('您没有访问该页面的权限，已返回首页')
      return { path: home }
    }
    const required = to.meta?.roles
    if (required && !required.some((r) => store.roles.includes(r))) {
      ElMessage.error('权限不足，无法访问该页面')
      return { path: '/courses' }
    }
    return true
  } catch (e) {
    console.error('[router.guard]', e)
    return { path: '/login' }
  }
  // 前端路由权限仅做菜单级拦截，真正的权限控制在后端（@RequireRoles）
})

export default router
