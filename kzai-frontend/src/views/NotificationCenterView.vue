<template>
  <div class="notify-center">
    <div class="notify-head">
      <h2>通知中心</h2>
      <el-button size="small" type="primary" plain :disabled="unread === 0" @click="readAll">
        全部标记已读（{{ unread }}）
      </el-button>
    </div>

    <div class="notify-filter">
      <el-radio-group v-model="readFilter" size="small" @change="reload">
        <el-radio-button :value="null">全部</el-radio-button>
        <el-radio-button :value="0">未读</el-radio-button>
        <el-radio-button :value="1">已读</el-radio-button>
      </el-radio-group>
      <el-select v-model="typeFilter" size="small" placeholder="全部类型" clearable style="width: 180px" @change="reload">
        <el-option label="AI 回复" value="AI_REPLY" />
        <el-option label="问答回复" value="QUESTION_REPLY" />
        <el-option label="课程审核通过" value="COURSE_APPROVED" />
      </el-select>
    </div>

    <el-table :data="records" size="small" border v-loading="loading">
      <template #empty>
        <div class="notify-empty">{{ error ? error : '暂无通知' }}</div>
      </template>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag size="small" :type="row.readStatus === 1 ? 'info' : 'danger'">
            {{ row.readStatus === 1 ? '已读' : '未读' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="120">
        <template #default="{ row }">{{ typeText(row.type) }}</template>
      </el-table-column>
      <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
      <!-- 安全文本展示：不解析 HTML，避免 XSS -->
      <el-table-column prop="content" label="摘要" min-width="220" show-overflow-tooltip />
      <el-table-column label="时间" width="175">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button size="small" @click="open(row)">查看</el-button>
          <el-button size="small" type="danger" plain @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="notify-page"
      layout="prev, pager, next, total"
      :total="total"
      :current-page="page"
      :page-size="size"
      @current-change="onPage" />

    <el-dialog v-model="detailVisible" :title="detail?.title" width="600px">
      <div v-if="detail">
        <p class="notify-detail-type">{{ typeText(detail.type) }} · {{ fmtTime(detail.createTime) }}</p>
        <!-- 纯文本渲染（Vue 插值默认转义），不注入 HTML -->
        <p class="notify-detail-content">{{ detail.content }}</p>
        <p class="notify-detail-tip">
          通知仅包含摘要；打开关联业务后仍以业务接口权限为准（通知存在不代表拥有该内容权限）。
        </p>
      </div>
      <template #footer>
        <el-button size="small" @click="detailVisible = false">关闭</el-button>
        <el-button size="small" type="primary" @click="gotoBiz(detail)">查看关联业务</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { fmtTime } from '../utils/format'
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'

const router = useRouter()

const records = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const unread = ref(0)
const loading = ref(false)
const error = ref('')
const readFilter = ref(null)
const typeFilter = ref('')
const detailVisible = ref(false)
const detail = ref(null)

function typeText(t) {
  if (t === 'AI_REPLY') return 'AI 回复'
  if (t === 'QUESTION_REPLY') return '问答回复'
  if (t === 'COURSE_APPROVED') return '课程审核通过'
  return t || '通知'
}

async function loadUnread() {
  try {
    const d = await request.get('/notifications/unread-count')
    unread.value = Number(d?.count || 0)
  } catch (e) {
    // 未读数失败不阻断列表，交由列表错误提示统一呈现
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const params = { page: page.value, size: size.value }
    if (readFilter.value !== null) params.readStatus = readFilter.value
    if (typeFilter.value) params.type = typeFilter.value
    const d = await request.get('/notifications', { params })
    const cleanPrefix = (v) => String(v || '').replace(/^\s*\[(?:MOCK 演示|MOCK)[^\]]*\]\s*/i, '').replace(/^\s*(?:【MOCK】|MOCK 演示[:：])\s*/i, '')
    records.value = (d?.records || []).map((n) => {
      const title = cleanPrefix(String(n.title || '')).replace(/\s*（MOCK 演示）\s*$/, '')
      const content = cleanPrefix(n.content)
      return { ...n, title, content }
    })
    total.value = Number(d?.total || 0)
  } catch (e) {
    error.value = e?.message || '通知服务异常，请稍后重试'
    records.value = []
  } finally {
    loading.value = false
    await loadUnread()
  }
}

function reload() {
  page.value = 1
  return load()
}

function onPage(p) {
  page.value = p
  return load()
}

async function open(row) {
  try {
    detail.value = await request.get(`/notifications/${row.id}`)
    detailVisible.value = true
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '打开通知失败')
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm('确认删除该通知？', '提示', { type: 'warning' })
    await request.delete(`/notifications/${row.id}`)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    if (e?.message && e.message !== 'cancel') ElMessage.error(e.message)
  }
}

async function readAll() {
  try {
    const d = await request.put('/notifications/read-all')
    ElMessage.success(`已标记 ${d?.updated || 0} 条为已读`)
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  }
}

/** 业务跳转：只允许站内固定路由，不使用通知携带的外部 URL */
function gotoBiz(n) {
  if (!n) return
  const bizId = String(n.bizId || '')
  detailVisible.value = false
  if (n.type === 'COURSE_APPROVED') {
    router.push(`/teacher/courses/${bizId}/edit`)
    return
  }
  router.push('/questions')
}

// 路由进入即刷新（页面切换保持真实数据）
watch(() => router.currentRoute.value.fullPath, (p) => {
  if (String(p).startsWith('/notifications')) load()
})

onMounted(load)
</script>

<style scoped>
.notify-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.notify-filter { display: flex; gap: 12px; margin-bottom: 12px; }
.notify-page { margin-top: 12px; }
.notify-empty { padding: 16px; color: #909399; }
.notify-detail-type { color: #909399; font-size: 12px; }
.notify-detail-content { white-space: pre-wrap; line-height: 1.7; }
.notify-detail-tip { color: #909399; font-size: 12px; margin-top: 12px; }
</style>
