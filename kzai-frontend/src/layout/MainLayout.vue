<template>
  <div class="app-shell">
    <header class="kz-header">
      <div class="logo-row">
        <el-button v-if="isMobile" class="menu-toggle" text aria-label="打开菜单" @click="drawerOpen = true">
          <el-icon :size="22"><Expand /></el-icon>
        </el-button>
        <div class="logo">{{ isMobile ? '课栈智伴' : '课栈智伴 · AI 智能在线教育平台' }}</div>
      </div>
      <div class="user">
        <!-- M11：通知铃铛（未读数来自真实后端，页面加载/路由切换/30s 轮询刷新） -->
        <!-- M15.14-R6 G3：通知下拉面板（最近 5 条+全部已读+跳转中心；空态+加载失败提示） -->
        <el-popover placement="bottom" :width="320" trigger="click" @show="loadRecent">
          <template #reference>
            <button type="button" class="notify-bell" aria-label="通知" style="cursor: pointer; background: none; border: none; padding: 0">
              <el-badge :value="unread" :hidden="unread === 0" :max="99">
                <el-icon :size="20"><Bell /></el-icon>
              </el-badge>
            </button>
          </template>
          <div class="notify-panel">
            <div class="notify-panel__head">
              <b>通知</b>
              <el-button size="small" link type="primary" :loading="readAllLoading" @click="markAllRead">全部已读</el-button>
            </div>
            <div v-if="recentLoading" class="notify-panel__empty">加载中…</div>
            <div v-else-if="recentError" class="notify-panel__empty" @click="loadRecent">加载失败，点击重试</div>
            <div v-else-if="!recentList.length" class="notify-panel__empty">暂无通知</div>
            <div v-else class="notify-panel__list">
              <div v-for="n in recentList" :key="n.id" class="notify-panel__item"
                   :class="{ 'is-unread': n.readStatus === 0 }" @click="openNotification(n)">
                <div class="notify-panel__title">{{ n.title }}</div>
                <div class="notify-panel__time">{{ n.createTime }}</div>
              </div>
            </div>
            <div class="notify-panel__foot">
              <el-button size="small" link type="primary" @click="router.push('/notifications')">查看全部</el-button>
            </div>
          </div>
        </el-popover>
        <!-- M15.15：顶栏同步显示头像与昵称（保存后经 Pinia 响应式立即更新，无需刷新） -->
        <span class="topbar-avatar">
          <img v-if="topbarAvatar" :src="topbarAvatar" alt="头像" />
          <span v-else class="topbar-avatar__fallback">{{ avatarFallbackChar }}</span>
        </span>
        <span>{{ topbarName }}</span>
        <el-tag type="info" size="small" effect="dark">{{ store.roleName }}</el-tag>
        <el-button size="small" type="primary" plain @click="onLogout">退出登录</el-button>
      </div>
    </header>
    <el-container class="main-container">
      <el-aside v-if="!isMobile" width="200px" style="background: #fff; border-right: 1px solid #e6e8eb">
        <el-menu :default-active="activeMenu" router>
          <template v-for="item in menuItems" :key="item.key">
            <el-sub-menu v-if="item.children" :index="item.key">
              <template #title>
                <el-icon><component :is="icons[item.icon]" /></el-icon>
                <span>{{ item.label }}</span>
              </template>
              <el-menu-item v-for="child in item.children" :key="child.key" :index="child.key">
                {{ child.label }}
              </el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="item.key">
              <el-icon><component :is="icons[item.icon]" /></el-icon>
              <span>{{ item.label }}</span>
            </el-menu-item>
          </template>
        </el-menu>
      </el-aside>
      <!-- M15.28：窄屏抽屉菜单（默认收起，顶栏按钮打开；选中后自动关闭） -->
      <el-drawer v-model="drawerOpen" title="课栈智伴" direction="ltr" size="220px" :with-header="true">
        <el-menu :default-active="activeMenu" router @select="onMenuSelect">
          <template v-for="item in menuItems" :key="item.key">
            <el-sub-menu v-if="item.children" :index="item.key">
              <template #title>
                <el-icon><component :is="icons[item.icon]" /></el-icon>
                <span>{{ item.label }}</span>
              </template>
              <el-menu-item v-for="child in item.children" :key="child.key" :index="child.key">
                {{ child.label }}
              </el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="item.key">
              <el-icon><component :is="icons[item.icon]" /></el-icon>
              <span>{{ item.label }}</span>
            </el-menu-item>
          </template>
        </el-menu>
      </el-drawer>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { Reading, ChatDotRound, UserFilled, User, Notebook, Collection, Tickets, Ticket, Medal, VideoPlay, EditPen, Bell, DataAnalysis, Odometer, View, Document, Setting, BellFilled, Expand} from '@element-plus/icons-vue'
import { useUserStore } from '../store/user'
import { MENU_CONFIG as _MC, roleAllowed as _ra } from '../router/menuConfig'
import request, { tokenStore } from '../api'

const store = useUserStore()
const route = useRoute()
const router = useRouter()

const unread = ref(0)
// M15.15：顶栏头像与昵称（昵称优先，未设置回退账号名；头像缺失回退首字母）
const topbarName = computed(() => store.profile?.nickname || store.profile?.username || '')
const topbarAvatar = computed(() => {
  const u = store.profile?.avatarUrl
  // M15.15：avatarUrl 为服务端相对路径——统一加 /api 前缀（dev 走 vite 代理、prod 同源），
  // 避免相对路径被 dev server SPA fallback 截获返回 index.html 导致破图
  return u ? '/api' + u + (u.includes('?') ? '&' : '?') + 't=' + (store.profile?.avatarVersion || Date.now()) : ''
})
const avatarFallbackChar = computed(() => topbarName.value.slice(0, 1).toUpperCase())
// M15.14-R6 G3：通知下拉面板（最近通知/全部已读）
const recentList = ref([])
const recentLoading = ref(false)
const recentError = ref(false)
const readAllLoading = ref(false)

async function loadRecent() {
  recentLoading.value = true
  recentError.value = false
  try {
    const d = await request.get('/notifications', { params: { pageNum: 1, pageSize: 5 } })
    // M15.43：技术前缀（MOCK 演示等）收成普通摘要——不过滤不丢通知
    recentList.value = (d?.records || []).map(n => ({
      ...n,
      content: String(n.content || '').replace(/^\s*\[(?:MOCK 演示|MOCK)[^\]]*\]\s*/i, '').replace(/^\s*(?:【MOCK】|MOCK 演示[:：])\s*/i, '')
    }))
  } catch (e) {
    recentError.value = true
  } finally {
    recentLoading.value = false
  }
}

async function markAllRead() {
  readAllLoading.value = true
  try {
    await request.put('/notifications/read-all')
    await loadRecent()
    await loadUnread(true)
  } finally {
    readAllLoading.value = false
  }
}

function openNotification(n) {
  router.push('/notifications')
}

/** 真实未读数量（后端 GET /notifications/unread-count）——M15.41 节流：短时间只发一次（标记已读后强制刷新） */
let lastUnreadFetchAt = 0
let unreadTimer = null
async function loadUnread(force = false) {
  if (!tokenStore.get()) {
    unread.value = 0
    return
  }
  const now = Date.now()
  if (!force && now - lastUnreadFetchAt < 29000) return // 30s 内已拉取——跳过（路由切换节流）
  lastUnreadFetchAt = now
  try {
    const d = await request.get('/notifications/unread-count')
    unread.value = Number(d?.count || 0)
  } catch (e) {
    unread.value = 0
  }
}

// 路由切换节流刷新 + 30 秒轮询（仅此一处周期任务；M11 不引入 WebSocket）
watch(() => route.fullPath, () => loadUnread())
onMounted(() => {
  loadUnread()
  unreadTimer = setInterval(() => loadUnread(), 30000)
})

// M15.14-R8：角色-菜单映射（统一配置 menuConfig.js，不散落硬编码）
const MENU_CONFIG = _MC
const menuItems = computed(() => MENU_CONFIG.filter(item => item.roles.some(r => store.roles.includes(r))))

// M15.28：窄屏（≤768px）侧栏改抽屉——默认收起，顶栏按钮打开；桌面侧栏常驻
const isMobile = ref(false)
const drawerOpen = ref(false)
function syncViewport() {
  isMobile.value = window.innerWidth <= 768
  if (!isMobile.value) drawerOpen.value = false
}
syncViewport()
window.addEventListener('resize', syncViewport)
onUnmounted(() => {
  clearInterval(unreadTimer)
  unreadTimer = null
  window.removeEventListener('resize', syncViewport)
})
function onMenuSelect() {
  if (isMobile.value) drawerOpen.value = false
}
const icons = { Reading, VideoPlay, Tickets, Ticket, Medal, Notebook, UserFilled, Collection, EditPen, DataAnalysis, Document, Setting, ChatDotRound, User, BellFilled, Odometer, View }

const activeMenu = computed(() => {
  if (route.path.startsWith('/learn/')) return '/learning'
  if (route.path.startsWith('/courses')) return '/courses'
  if (route.path.startsWith('/teacher/courses') && !/^\/teacher\/courses\/(new|\d+\/edit)$/.test(route.path)) {
    return route.path
  }
  if (route.path.startsWith('/teacher')) return '/teacher/courses'
  if (route.path.startsWith('/admin/users')) return '/admin/users'
  if (route.path.startsWith('/admin')) return route.path
  return route.path
})

async function onLogout() {
  try {
    await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  } catch {
    return // 用户取消
  }
  try {
    await store.logout()
  } catch (e) {
    // M15.14-R9：logout 接口失败（如 token 已过期 401）也强制清场跳登录，
    // 避免 Unhandled rejection 导致「点击无响应」
    console.warn('[logout]', e?.message)
  } finally {
    router.push('/login')
  }
}
</script>

<style scoped>
.notify-bell { cursor: pointer; margin-right: 16px; display: inline-flex; align-items: center; }
.notify-panel__head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.notify-panel__list { max-height: 280px; overflow-y: auto; }
.notify-panel__item { padding: 8px 6px; border-radius: 6px; cursor: pointer; }
.notify-panel__item:hover { background: #f2f6fc; }
.notify-panel__item.is-unread .notify-panel__title { font-weight: 600; }
.notify-panel__title { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.notify-panel__time { font-size: 12px; color: #98a2b3; }
.notify-panel__empty { text-align: center; color: #98a2b3; font-size: 13px; padding: 20px 0; cursor: pointer; }
.notify-panel__foot { text-align: center; border-top: 1px solid #f0f0f0; padding-top: 6px; margin-top: 6px; }
.topbar-avatar { display: inline-flex; align-items: center; }
.topbar-avatar img { width: 30px; height: 30px; border-radius: 50%; object-fit: cover; border: 2px solid rgba(255,255,255,.5); }
.topbar-avatar__fallback { display: inline-flex; width: 30px; height: 30px; border-radius: 50%; background: rgba(255,255,255,.25); color: #fff; align-items: center; justify-content: center; font-weight: 600; }
.logo-row { display: flex; align-items: center; gap: 4px; }
.menu-toggle { padding: 4px; margin-right: 2px; }
.app-shell {
  height: 100dvh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  box-sizing: border-box;
  padding-bottom: env(safe-area-inset-bottom, 0px);
}
.main-container { flex: 1 1 auto; min-height: 0; overflow: hidden; }
.app-main {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
}
@media (max-width: 768px) {
  .app-main { padding: 8px; }
}
</style>

