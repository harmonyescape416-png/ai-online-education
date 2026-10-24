<template>
  <div class="qa-center">
    <div class="qa-head">
      <h2>课程问答</h2>
      <el-button size="small" :type="mode === 'mine' ? 'primary' : 'default'" @click="loadMine">我的问答</el-button>
      <el-button size="small" :type="mode === 'course' ? 'primary' : 'default'" @click="backToCourse">课程问答区</el-button>
    </div>

    <el-card class="qa-form">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="选择课程" required>
          <!-- M15.12：课程 ID 手动输入 → 可搜索课程下拉（value=真实 courseId 字符串） -->
          <el-select v-model="form.courseId" filterable remote :remote-method="searchCourses"
                     :loading="courseLoading" placeholder="请选择要提问的课程" style="width: 100%"
                     :loading-text="courseError ? '加载失败' : '课程加载中…'"
                     no-data-text="没有匹配的上架课程" @focus="onSelectFocus" @change="onCourseChange">
            <el-option v-for="c in courseOptions" :key="c.id" :value="c.id"
                       :label="c.name + (c.free === 1 || c.price === 0 ? '（免费）' : '（¥' + c.priceYuan + '）')">
              <span>{{ c.name }}</span>
              <span class="qa-option-meta">
                {{ c.free === 1 || c.price === 0 ? '免费' : '¥' + c.priceYuan }}
                <template v-if="duplicateNames.has(c.name)"> · ID 尾号 {{ String(c.id).slice(-6) }}</template>
              </span>
            </el-option>
            <template #footer>
              <div v-if="courseError" class="qa-course-error">
                课程加载失败
                <el-button size="small" link type="primary" @click="() => searchCourses(courseKeyword)">重试</el-button>
              </div>
            </template>
          </el-select>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="100" show-word-limit placeholder="一句话描述问题"
                    :class="{ 'qa-near-limit': form.title.length >= 90 }" />
        </el-form-item>
        <el-form-item label="问题描述" required>
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="2000" show-word-limit
                    placeholder="详细描述你的问题，AI 将自动回复"
                    :class="{ 'qa-near-limit': form.description.length >= 1800 }" />
        </el-form-item>
        <div class="qa-form__actions">
          <el-checkbox v-model="form.anonymity">匿名提问</el-checkbox>
          <span class="qa-anon-tip">匿名后问答区不公开你的账号身份</span>
          <el-button type="primary" :loading="submitting"
                     :disabled="submitting || !form.courseId || !form.title.trim() || !form.description.trim()"
                     @click="submit">提交问题（AI 将自动回复）</el-button>
        </div>
      </el-form>
    </el-card>

    <el-divider>{{ listTitle }}</el-divider>
    <el-table :data="records" size="small" border v-loading="loading" :empty-text="mode === 'mine' ? '暂无问答记录' : (form.courseId ? '该课程暂无问答，来提第一个问题吧' : '请先选择上方课程，查看该课程问答')">
      <el-table-column prop="title" label="问题" min-width="200" show-overflow-tooltip />
      <el-table-column label="提问者" width="110">
        <template #default="{ row }">{{ row.anonymity === 1 ? '匿名' : (row.userId ? '用户 ' + String(row.userId).slice(-4) : '匿名') }}</template>
      </el-table-column>
      <el-table-column prop="answerTimes" label="回答数" width="80" />
      <el-table-column prop="createTime" label="时间" width="170" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button size="small" @click="openDetail(row)">详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="detailVisible" :title="detail?.question?.title" width="720px">
      <div v-if="detail">
        <p class="qa-desc">{{ detail.question.description }}</p>
        <el-tag v-if="detail.aiTask && detail.aiTask.status === 1" size="small" type="warning">
          AI 正在生成回复…
        </el-tag>
        <el-tag v-else-if="detail.aiTask && detail.aiTask.status === 0" size="small" type="info">
          AI 回复排队中…
        </el-tag>
        <el-alert v-if="detail.aiTask && detail.aiTask.status === 3" type="error" :closable="false"
                  :title="'AI 回复生成失败：' + (detail.aiTask.error || '未知原因')" style="margin: 8px 0" />
        <div v-for="r in detail.replies" :key="r.id" class="qa-reply">
          <div class="qa-reply__meta">
            <el-tag size="small" :type="sourceTagType(r)" effect="plain">{{ sourceLabel(r) }}</el-tag>
            <span v-if="r.corrected" class="qa-corrected">（已经教师人工修正）</span>
            <span class="qa-time">{{ r.createTime }}</span>
          </div>
          <div class="qa-reply__content">{{ r.content }}</div>
          <!-- M15.21-E：AI 回复采纳（有帮助/撤销——唯一记录幂等；刷新状态保持） -->
          <div v-if="isAiReply(r)" class="qa-helpful">
            <el-button size="small" :type="helpfulMarked[r.id] ? 'primary' : 'default'"
                       :loading="helpfulLoading[r.id]" @click="toggleHelpful(r)">
              {{ helpfulMarked[r.id] ? '已标记有帮助（点击撤销）' : '有帮助' }}
            </el-button>
            <span style="color:#98a2b3; font-size:12px; margin-left:8px">{{ helpfulCount[r.id] || 0 }} 人觉得有帮助</span>
          </div>
          <el-button size="small" link @click="replyTarget = r; replyFocus = true">追问</el-button>
        </div>
        <el-input v-model="replyContent" type="textarea" :rows="2" placeholder="写下你的追问或回复…"
                  style="margin-top: 8px" />
        <div style="margin-top: 6px">
          <el-button size="small" type="primary" @click="sendReply">
            {{ replyTarget ? '追问 ' + sourceLabel(replyTarget) : '回复问题' }}
          </el-button>
          <el-button size="small" @click="refreshDetail">刷新（查看 AI 回复）</el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted , reactive} from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api'
import { useRoute } from 'vue-router'

const route = useRoute()
const mode = ref('course')
const records = ref([])
const loading = ref(false)
const submitting = ref(false)
// courseId 一律字符串（19 位雪花 ID 超 JS Number 安全整数，禁止 Number() 转换）
const form = ref({ courseId: '', title: '', description: '', anonymity: false })
const courseOptions = ref([])
const courseLoading = ref(false)
const courseError = ref(false)
const courseKeyword = ref('')
const courseLoaded = ref(false)
const detailVisible = ref(false)
const detail = ref(null)
const detailId = ref(null)
const replyContent = ref('')
const replyTarget = ref(null)
let pollTimer = null

const listTitle = computed(() => mode.value === 'mine' ? '我的问答' :
  (form.courseId ? '课程问答区（当前课程）' : '课程问答区（选择课程后显示该课程问答）'))

/** 同名课程辅助集合（下拉显示 ID 尾号区分） */
const duplicateNames = computed(() => {
  const seen = new Map()
  const dup = new Set()
  for (const c of courseOptions.value) {
    if (seen.has(c.name)) dup.add(c.name)
    seen.set(c.name, true)
  }
  return dup
})

/** 课程选项加载：初始走上架课程列表，关键词走搜索接口（均仅保留已上架 status=2） */
const fetchCourses = async (keyword) => {
  courseLoading.value = true
  courseError.value = false
  try {
    let list = []
    if (keyword && keyword.trim()) {
      const data = await request.get('/courses/search', { params: { keyword: keyword.trim() } })
      list = Array.isArray(data) ? data : []
    } else {
      const data = await request.get('/courses', { params: { page: 1, size: 50 } })
      list = data?.records || []
    }
    courseOptions.value = list.filter(c => c.status === 2)
    courseLoaded.value = true
  } catch (e) {
    courseError.value = true
    courseOptions.value = []
    ElMessage.error('课程列表加载失败，请重试')
  } finally {
    courseLoading.value = false
  }
}

const searchCourses = (keyword) => {
  courseKeyword.value = keyword || ''
  return fetchCourses(keyword)
}

const onSelectFocus = () => {
  if (!courseLoaded.value && !courseError.value) fetchCourses('')
}

/** 从课程详情携带 courseId 进入时自动选中（校验课程真实存在且上架，防 URL 篡改） */
const preselectFromRoute = async () => {
  const cid = route.query.courseId
  if (!cid) return
  const idStr = String(cid)
  try {
    const c = await request.get(`/courses/${idStr}`)
    if (c && c.id && String(c.id) === idStr && c.status === 2) {
      form.value.courseId = idStr
      if (!courseOptions.value.some(x => String(x.id) === idStr)) {
        courseOptions.value = [{ id: String(c.id), name: c.name, priceYuan: c.priceYuan, free: c.free, price: c.price, status: c.status }, ...courseOptions.value]
      }
    } else {
      ElMessage.warning('链接中的课程不存在或已下架，请重新选择')
    }
  } catch (e) {
    ElMessage.warning('链接中的课程校验失败，请重新选择')
  }
}

const onCourseChange = () => {
  if (mode.value === 'course' && form.value.courseId) load()
}

const load = async () => {
  if (loading.value) return
  loading.value = true
  try {
    if (mode.value === 'mine') {
      const data = await request.get('/questions/mine', { params: { page: 1, size: 20 } })
      records.value = data?.records || []
    } else if (form.value.courseId) {
      const data = await request.get('/questions', { params: { courseId: form.value.courseId, page: 1, size: 20 } })
      records.value = data?.records || []
    } else {
      records.value = []
    }
  } catch (e) {
    ElMessage.error(e.message || '问答列表加载失败')
  } finally { loading.value = false }
}

const loadMine = () => { mode.value = 'mine'; load() }
const backToCourse = () => { mode.value = 'course'; load() }

const submit = async () => {
  if (submitting.value) return // 防连点
  if (!form.value.courseId) { ElMessage.warning('请先选择要提问的课程'); return }
  if (!form.value.title.trim()) { ElMessage.warning('请填写问题标题'); return }
  if (!form.value.description.trim()) { ElMessage.warning('请填写问题描述'); return }
  submitting.value = true
  try {
    // courseId 按字符串提交（后端 Long 字段 JSON 字符串可安全反序列化，无精度丢失）
    await request.post('/questions', {
      title: form.value.title.trim(), description: form.value.description.trim(),
      courseId: form.value.courseId,
      anonymity: form.value.anonymity ? 1 : 0
    })
    ElMessage({
      type: 'success',
      message: '提问成功，AI 正在生成回复',
      // M15.14-R6 Q1：提交成功后引导查看「我的问答」
      showClose: true,
      duration: 4000
    })
    setTimeout(() => {
      ElMessage.info('可在「我的问答」中查看 AI 回复进度')
    }, 1200)
    form.value.title = ''; form.value.description = '' // 保留当前选中课程，便于连续提问
    mode.value = 'course'
    await load()
  } catch (e) {
    ElMessage.error(e.message || '提交失败')
  } finally { submitting.value = false }
}

const openDetail = async (row) => {
  detailId.value = row.id
  await refreshDetail()
  detailVisible.value = true
  clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    const t = detail.value?.aiTask
    if (!t || t.status === 2 || t.status === 3) { clearInterval(pollTimer); return }
    await refreshDetail(true)
  }, 3000)
}
const refreshDetail = async (silent) => {
  try {
    detail.value = await request.get(`/questions/${detailId.value}`)
  } catch (e) { if (!silent) ElMessage.error(e.message || '加载失败') }
}
const sendReply = async () => {
  if (!replyContent.value.trim()) return
  try {
    await request.post(`/questions/${detailId.value}/replies`, {
      content: replyContent.value,
      targetReplyId: replyTarget.value ? String(replyTarget.value.id) : null
    })
    replyContent.value = ''; replyTarget.value = null
    ElMessage.success('回复成功')
    await refreshDetail(true)
  } catch (e) { ElMessage.error(e.message || '回复失败') }
}

const LABELS = { SYSTEM_AI: 'AI 回复', TEACHER: '教师', STUDENT: '学员' }
const sourceLabel = (r) => LABELS[r.source] || (r.isStudent === 1 ? '学员' : '回答')
const sourceTagType = (r) => (r.source === 'SYSTEM_AI' ? 'warning' : r.source === 'TEACHER' ? 'success' : 'info')

onMounted(async () => {
  await fetchCourses('')
  await preselectFromRoute()
  if (mode.value === 'course' && form.value.courseId) load()
})
onUnmounted(() => clearInterval(pollTimer))
// M15.21-E：AI 回复采纳（SYSTEM_AI 判定；唯一记录幂等；刷新状态保持）
const helpfulMarked = reactive({})
const helpfulCount = reactive({})
const helpfulLoading = reactive({})
const isAiReply = (r) => String(r.source || '').toUpperCase().includes('AI')

async function loadHelpfulState(r) {
  if (!isAiReply(r)) return
  try {
    const d = await request.get(`/questions/${r.id}/helpful/status`)
    helpfulMarked[r.id] = !!d?.marked
    helpfulCount[r.id] = Number(d?.helpfulCount || 0)
  } catch (e) { /* 无状态不阻塞 */ }
}

async function toggleHelpful(r) {
  if (helpfulLoading[r.id]) return
  helpfulLoading[r.id] = true
  const undo = !!helpfulMarked[r.id]
  try {
    const d = await request.post(undo ? `/questions/${r.id}/helpful/undo` : `/questions/${r.id}/helpful`)
    helpfulMarked[r.id] = !!d?.marked
    helpfulCount[r.id] = Number(d?.helpfulCount || 0)
  } catch (e) {
    ElMessage.error(e?.message || (undo ? '撤销失败' : '标记失败'))
  } finally {
    helpfulLoading[r.id] = false
  }
}

</script>

<style scoped>
.qa-center { max-width: 900px; }
.qa-head { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.qa-head h2 { margin: 0; font-size: 18px; margin-right: auto; }
.qa-form { margin-bottom: 12px; }
.qa-form__actions { display: flex; align-items: center; gap: 12px; }
.qa-form__actions .el-button { margin-left: auto; }
.qa-option-meta { float: right; color: #98a2b3; font-size: 12px; margin-left: 12px; }
.qa-course-error { width: 100%; text-align: center; color: #b42318; }
.qa-desc { color: #475467; }
.qa-reply { border-top: 1px dashed #e6e8eb; padding: 8px 0; }
.qa-reply__meta { display: flex; gap: 8px; align-items: center; margin-bottom: 4px; }
.qa-reply__content { white-space: pre-wrap; }
.qa-corrected { color: #b54708; font-size: 12px; }
.qa-time { color: #98a2b3; font-size: 12px; margin-left: auto; }
.qa-anon-tip { font-size: 12px; color: #98a2b3; margin-left: 8px; }
.qa-near-limit :deep(.el-input__inner), .qa-near-limit :deep(.el-textarea__inner) { color: #e6a23c; }
</style>
