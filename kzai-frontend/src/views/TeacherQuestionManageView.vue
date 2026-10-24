<template>
  <div class="tqa">
    <div class="tqa-head">
      <h2>问答管理</h2>
      <el-select v-model="courseId" clearable filterable placeholder="全部课程" style="width: 180px" @change="load">
        <el-option v-for="c in courseOptions" :key="c.id" :label="c.name" :value="String(c.id)" />
      </el-select>
      <el-button size="small" @click="load">查询</el-button>
    </div>

    <el-table :data="records" size="small" border v-loading="loading">
      <el-table-column prop="id" label="问题 ID" width="170" />
      <el-table-column prop="title" label="问题" min-width="200" show-overflow-tooltip />
      <el-table-column label="课程" width="160"><template #default="{ row }">{{ courseMap[row.courseId] || row.courseId }}</template></el-table-column>
      <el-table-column prop="answerTimes" label="回答数" width="80" />
      <el-table-column label="隐藏" width="70">
        <template #default="{ row }">
          <el-tag size="small" :type="row.hidden === 1 ? 'danger' : 'info'" effect="plain">
            {{ row.hidden === 1 ? '已隐藏' : '正常' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row)">管理</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination layout="prev, pager, next, total" :total="total" :page-size="size"
      :current-page="page" @current-change="onPage" style="margin-top:10px" />

    <el-dialog v-model="detailVisible" title="问答管理" width="760px">
      <div v-if="detail">
        <p><b>{{ detail.question.title }}</b></p>
        <p class="tqa-desc">{{ detail.question.description }}</p>
        <el-alert v-if="detail.aiTask && detail.aiTask.status === 3" type="error" :closable="false"
                  :title="'AI 生成失败：' + (detail.aiTask.error || '')" style="margin-bottom: 8px" />
        <div v-for="r in detail.replies" :key="r.id" class="tqa-reply">
          <div class="tqa-meta">
            <el-tag size="small" :type="tagType(r)" effect="plain">{{ label(r) }}</el-tag>
            <el-tag v-if="r.corrected === 1" size="small" type="warning" effect="plain">已人工修正</el-tag>
            <el-tag v-if="r.featured === 1" size="small" type="success" effect="plain">精华</el-tag>
            <el-tag v-if="r.hidden === 1" size="small" type="danger" effect="plain">已隐藏</el-tag>
            <span class="tqa-time">{{ r.createTime }}</span>
          </div>
          <div class="tqa-content">{{ r.content }}</div>
          <div class="tqa-ops">
            <el-button size="small" @click="replyTarget = r">回复此回复</el-button>
            <el-button v-if="r.source === 'SYSTEM_AI'" size="small" type="primary" plain
                       @click="correct(r)">人工修正</el-button>
            <el-button size="small" type="warning" plain
                       @click="regenerate">重新生成 AI 回复</el-button>
            <el-button size="small" @click="feature(r, r.featured !== 1)">
              {{ r.featured === 1 ? '取消精华' : '设为精华' }}</el-button>
            <el-button size="small" type="danger" plain @click="hide(r)">
              {{ r.hidden === 1 ? '恢复显示' : '隐藏' }}</el-button>
          </div>
        </div>
        <el-input v-model="replyContent" type="textarea" :rows="2" placeholder="教师回复…"
                  style="margin-top: 8px" />
        <el-button size="small" type="primary" style="margin-top: 6px" @click="sendReply">
          {{ replyTarget ? '回复该回复' : '直接回答问题' }}
        </el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request, { teacherApi } from '../api'

const records = ref([])
const loading = ref(false)
const courseId = ref('')
// M15.46：课程名映射+分页
const courseOptions = ref([])
const courseMap = ref({})
const page = ref(1)
const size = ref(10)
const total = ref(0)
const detailVisible = ref(false)
const detail = ref(null)
const detailId = ref(null)
const replyContent = ref('')
const replyTarget = ref(null)

const onPage = (p) => {
  page.value = p
  load()
}

const load = async () => {
  loading.value = true
  try {
    const params = { page: page.value, size: size.value }
    if (courseId.value) params.courseId = Number(courseId.value)
    const data = await request.get('/teacher/questions', { params })
    total.value = Number(data?.total || 0)
    records.value = data?.records || []
  } catch (e) { if (!e.notified) ElMessage.error(e.message || '加载失败') } finally { loading.value = false }
}

const openDetail = async (row) => {
  detailId.value = row.id
  try {
    detail.value = await request.get(`/teacher/questions/${row.id}`)
    detailVisible.value = true
  } catch (e) { ElMessage.error(e.message || '加载失败') }
}

const sendReply = async () => {
  if (!replyContent.value.trim()) return
  try {
    await request.post(`/teacher/questions/${detailId.value}/reply`, {
      content: replyContent.value,
      targetReplyId: replyTarget.value ? Number(replyTarget.value.id) : null
    })
    replyContent.value = ''; replyTarget.value = null
    ElMessage.success('回复成功'); await reload()
  } catch (e) { ElMessage.error(e.message || '回复失败') }
}

const correct = async (r) => {
  try {
    const { value } = await ElMessageBox.prompt('输入修正后的内容（保留修正前审计）', '人工修正', {
      inputValue: r.content, inputType: 'textarea'
    })
    await request.post(`/teacher/replies/${r.id}/correct`, { content: value })
    ElMessage.success('已修正（保留修正前审计）'); await reload()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message || '修正失败') }
}

const regenerate = async () => {
  try {
    await ElMessageBox.confirm('发起 AI 重新生成？原回复保留可见；已教师修正的内容不会被覆盖。', '重新生成')
    await request.post(`/teacher/questions/${detailId.value}/regenerate`)
    ElMessage.success('重生成任务已排队'); await reload()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message || '操作失败') }
}

const feature = async (r, on) => {
  try {
    await request.post(`/teacher/replies/${r.id}/feature`, { on })
    ElMessage.success(on ? '已设为精华' : '已取消精华'); await reload()
  } catch (e) { ElMessage.error(e.message || '操作失败') }
}

const hide = async (r) => {
  try {
    const visible = r.hidden === 1
    if (!visible) await ElMessageBox.confirm('隐藏后普通学员不可见，确认？', '隐藏')
    await request.patch(`/teacher/replies/${r.id}/visibility`, { visible })
    ElMessage.success('已更新'); await reload()
  } catch (e) { if (e !== 'cancel') ElMessage.error(e.message || '操作失败') }
}

const reload = async () => {
  detail.value = await request.get(`/teacher/questions/${detailId.value}`)
  await load()
}

const LABELS = { SYSTEM_AI: 'AI', TEACHER: '教师', STUDENT: '学员' }
const label = (r) => LABELS[r.source] || (r.isStudent === 1 ? '学员' : '回答')
const tagType = (r) => (r.source === 'SYSTEM_AI' ? 'warning' : r.source === 'TEACHER' ? 'success' : 'info')

onMounted(async () => {
  // M15.46：课程下拉数据（本人课程——课程名映射）
  try {
    // M15.46：size 后端上限 10——按 total 多页拉全（上限 30 页），保证课程名映射覆盖
    const first = await teacherApi.list({ pageNum: 1, pageSize: 10 })
    const all = first?.records || []
    const total = Number(first?.total || all.length)
    const pages = Math.min(Math.ceil(total / 10), 30)
    // M15.46 修正：该接口分页参数为 pageNum/pageSize（page/size 会被忽略致所有页返回第一页）
    const jobs = []
    for (let pg = 2; pg <= pages; pg++) jobs.push(teacherApi.list({ pageNum: pg, pageSize: 10 }).catch(() => null))
    const rest = await Promise.all(jobs)
    rest.forEach((d) => { if (d?.records) all.push(...d.records) })
    courseOptions.value = all
    const m = {}
    all.forEach((c) => { m[c.id] = c.name })
    courseMap.value = m
  } catch (e) { /* 课程选项失败不阻塞列表 */ }
  await load()
})
</script>

<style scoped>
.tqa { max-width: 1000px; }
.tqa-head { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; }
.tqa-head h2 { margin: 0; font-size: 18px; margin-right: auto; }
.tqa-desc { color: #475467; }
.tqa-reply { border-top: 1px dashed #e6e8eb; padding: 8px 0; }
.tqa-meta { display: flex; gap: 6px; align-items: center; }
.tqa-content { white-space: pre-wrap; margin: 4px 0; }
.tqa-ops { display: flex; gap: 6px; flex-wrap: wrap; }
.tqa-time { color: #98a2b3; font-size: 12px; margin-left: auto; }
</style>
