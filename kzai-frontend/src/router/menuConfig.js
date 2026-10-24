/**
 * M15.14-R8：角色-菜单映射表（统一配置，不散落硬编码）。
 *
 * 依据：需求 3.1.1「角色仅见权限内菜单」、3.2.1 管理员职责、3.2.2 教师职责、3.2.3 学员功能。
 * 裁剪原则：仅从菜单隐藏，路由与页面组件保留；无权限角色直链访问由路由守卫重定向。
 *
 * admin 可见（一级 8 项 ≤9）：用户管理、课程运营（分类/审核）、Prompt 模板、知识库管理、
 *   AI 评测、操作日志、系统参数、个人中心；
 *   移除：我的学习/我的订单/课程浏览（学员页）、课程问答、问答管理（教师职责 3.2.2）、
 *   运营看板（与学情统计重叠）、平台监管、事件运维（需求文档无对应条款）。
 * teacher 可见：我的课程（课程列表/学情统计/问答管理 3.2.2）、个人中心。
 * student 保持现状：课程浏览、我的学习、我的订单、AI 工作台、课程问答、个人中心。
 */

export const MENU_CONFIG = [
  { key: '/courses', label: '课程浏览', icon: 'Reading', roles: ['STUDENT'] },
  { key: '/learning', label: '我的学习', icon: 'VideoPlay', roles: ['STUDENT'] },
  { key: '/learning/points', label: '签到积分', icon: 'Medal', roles: ['STUDENT'] },
  { key: '/orders', label: '我的订单', icon: 'Tickets', roles: ['STUDENT'] },
  { key: '/coupons', label: '优惠券', icon: 'Ticket', roles: ['STUDENT'] },
  {
    key: '/teacher',
    label: '我的课程',
    icon: 'Notebook',
    roles: ['TEACHER'],
    children: [
      { key: '/teacher/courses', label: '课程列表' },
      { key: '/teacher/learning-stats', label: '学情统计' },
      { key: '/teacher/questions', label: '问答管理' }
    ]
  },
  { key: '/admin/users', label: '用户管理', icon: 'UserFilled', roles: ['ADMIN'] },
  { key: '/admin/refunds', label: '退款审核', icon: 'Wallet', roles: ['ADMIN'] },
  { key: '/admin/coupons', label: '优惠券', icon: 'Ticket', roles: ['ADMIN'] },
  {
    key: '/admin/course-ops',
    label: '课程运营',
    icon: 'Collection',
    roles: ['ADMIN'],
    children: [
      { key: '/admin/categories', label: '分类管理' },
      { key: '/admin/course-audits', label: '课程审核' }
    ]
  },
  { key: '/admin/ai/prompts', label: 'Prompt 模板', icon: 'EditPen', roles: ['ADMIN'] },
  { key: '/admin/ai/knowledge', label: '知识库管理', icon: 'Collection', roles: ['ADMIN'] },
  { key: '/admin/ai/evaluations', label: 'AI 评测', icon: 'DataAnalysis', roles: ['ADMIN'] },
  {
    key: '/admin/dashboard',
    label: '运营看板',
    icon: 'Odometer',
    roles: ['ADMIN']
  },
  {
    key: '/admin/supervision',
    label: '平台监管',
    icon: 'View',
    roles: ['ADMIN'],
    children: [
      { key: '/admin/supervision/sessions', label: 'AI 会话监管' },
      { key: '/admin/supervision/questions', label: '课程问答监管' }
    ]
  },
  { key: '/admin/operation-logs', label: '操作日志', icon: 'Document', roles: ['ADMIN'] },
  { key: '/admin/system-settings', label: '系统参数', icon: 'Setting', roles: ['ADMIN'] },
  { key: '/ai', label: 'AI 工作台', icon: 'ChatDotRound', roles: ['STUDENT'] },
  { key: '/questions', label: '课程问答', icon: 'ChatDotRound', roles: ['STUDENT'] },
  { key: '/profile', label: '个人中心', icon: 'User', roles: ['STUDENT', 'TEACHER', 'ADMIN'] }
]

/** 角色首页（守卫重定向目标） */
export const ROLE_HOME = {
  STUDENT: '/courses',
  TEACHER: '/teacher/courses',
  ADMIN: '/admin/users'
}

/**
 * 菜单外功能页豁免：不在菜单显示、但学员核心流程必须可达的路径
 * （菜单裁剪 M15.14-R8 守卫曾误杀 /learn 深链——本清单修复）。
 * 页面级真实权限仍由后端权威校验（学习页走 EntitlementService 权益）。
 */
export const HIDDEN_ALLOWED = ['/learn', '/notifications']

/**
 * 判定当前角色是否允许访问该路径（含子路径前缀匹配，如 /admin/users 与 /admin/users/1）。
 * 通知中心对所有已登录角色放行（铃铛入口）。
 */
export function roleAllowed(path, roles) {
  if (!roles || !roles.length) return true
  for (const hidden of HIDDEN_ALLOWED) {
    if (path === hidden || path.startsWith(hidden + '/')) return true
  }
  if (path.startsWith('/notifications')) return true
  for (const item of MENU_CONFIG) {
    const allowed = item.roles.some(r => roles.includes(r))
    if (!allowed) continue
    if (path === item.key || path.startsWith(item.key + '/')) return true
    if (item.children) {
      for (const child of item.children) {
        if (path === child.key || path.startsWith(child.key + '/')) return true
      }
    }
  }
  return false
}
