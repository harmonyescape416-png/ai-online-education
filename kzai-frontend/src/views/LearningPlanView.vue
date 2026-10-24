<template>
  <div class="plan-page">
    <div class="plan-page__head">
      <h2 class="kz-page-title">学习计划</h2>
      <el-button link type="primary" @click="router.push('/learning')">返回我的学习</el-button>
    </div>

    <el-card v-if="loading" shadow="never">正在加载学习计划…</el-card>
    <el-card v-else-if="error" shadow="never">
      <el-empty :description="error">
        <el-button type="primary" @click="load">重试</el-button>
      </el-empty>
    </el-card>
    <template v-else>
      <el-alert :title="board.summaryText || '没有计划'" :type="alertType" show-icon :closable="false" />
      <p v-if="board.weekStart" class="plan-note">{{ board.weekStart }}</p>

      <el-card shadow="never" class="plan-form">
        <el-form :inline="true" @submit.prevent>
          <el-form-item label="课程">
            <el-select v-model="form.courseId" placeholder="选择已加入学习的课程" style="width: 240px">
              <el-option v-for="course in courses" :key="course.courseId" :label="course.courseName" :value="String(course.courseId)" />
            </el-select>
          </el-form-item>
          <el-form-item label="每周完成">
            <el-input-number v-model="form.weeklyGoal" :min="1" :max="20" />
            <span class="plan-unit">节</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="onJoin">加入计划</el-button>
          </el-form-item>
        </el-form>
      </el-card>

      <el-empty v-if="!board.plans.length" description="还没有学习计划" />
      <div v-else class="plan-list">
        <el-card v-for="item in board.plans" :key="item.id" shadow="hover" class="plan-card">
          <div class="plan-card__title">{{ item.courseName }}</div>
          <div class="plan-card__tags">
            <el-tag size="small">{{ item.statusText }}</el-tag>
            <el-tag size="small" :type="weekTag(item.weekStatus)">{{ item.weekStatusText }}</el-tag>
          </div>
          <p>本周目标 {{ item.weeklyGoal }} 节</p>
          <p v-if="item.nextWeekGoal">下周目标 {{ item.nextWeekGoal }} 节</p>
          <p v-if="item.finishedThisWeek == null">本周完成量暂不可用</p>
          <p v-else>已完成 {{ item.finishedThisWeek }} 节，剩余 {{ item.remaining }} 节，{{ item.ratio }}%</p>
          <p v-if="item.accessReason" class="plan-warn">{{ item.accessReason }}</p>
          <div class="plan-card__actions">
            <el-button v-if="item.canContinue" type="primary" size="small" @click="router.push(item.continuePath)">继续学习</el-button>
            <el-button v-if="item.status === 'ACTIVE'" size="small" :disabled="busyId === item.id" @click="onPause(item)">暂停</el-button>
            <el-button v-if="item.status === 'PAUSED'" size="small" type="primary" :disabled="busyId === item.id" @click="onResume(item)">恢复</el-button>
            <el-button v-if="item.status === 'ACTIVE' || item.status === 'PAUSED'" size="small" :disabled="busyId === item.id" @click="openGoal(item)">修改目标</el-button>
            <el-button v-if="item.status === 'ACTIVE' || item.status === 'PAUSED'" size="small" type="danger" plain :disabled="busyId === item.id" @click="onQuit(item)">退出</el-button>
          </div>
        </el-card>
      </div>
    </template>

    <el-dialog v-model="goalVisible" title="修改每周目标" width="420px">
      <el-input-number v-model="nextGoal" :min="1" :max="20" />
      <template #footer>
        <el-button @click="goalVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onChangeGoal">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { learningApi } from '../api'

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const busyId = ref('')
const courses = ref([])
const board = ref({ plans: [], summaryText: '', coverageText: '', weekStart: '', weekEndExclusive: '', timezone: '' })
const form = reactive({ courseId: '', weeklyGoal: 2 })
const goalVisible = ref(false)
const editing = ref(null)
const nextGoal = ref(2)

const alertType = computed(() => {
  if (board.value.summary === 'REACHED') return 'success'
  if (board.value.summary === 'INSUFFICIENT' || board.value.summary === 'NONE') return 'info'
  if (board.value.summary === 'NOT_STARTED' || board.value.summary === 'PAUSED') return 'warning'
  return 'warning'
})

function friendly(e, fallback) {
  const msg = e?.message || ''
  if (!msg || msg.includes('服务异常') || msg.includes('服务暂时不可用')) return fallback
  return msg
}

function weekTag(status) {
  if (status === 'REACHED' || status === 'COURSE_DONE') return 'success'
  if (status === 'PARTIAL') return 'warning'
  if (status === 'INSUFFICIENT') return 'info'
  return 'info'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [planData, lessonData] = await Promise.all([
      learningApi.plans(),
      learningApi.list({ pageNum: 1, pageSize: 50 })
    ])
    board.value = planData || { plans: [] }
    if (!board.value.plans) board.value.plans = []
    courses.value = lessonData?.records || []
  } catch (e) {
    error.value = friendly(e, '学习计划暂时无法加载，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function onJoin() {
  if (!form.courseId) {
    ElMessage.warning('请选择课程')
    return
  }
  saving.value = true
  try {
    const item = await learningApi.joinPlan(form.courseId, form.weeklyGoal)
    ElMessage.success(item?.alreadyJoined ? '已在计划中' : '已加入')
    await load()
  } catch (e) {
    ElMessage.error(friendly(e, '加入失败，请稍后重试'))
  } finally {
    saving.value = false
  }
}

function openGoal(item) {
  editing.value = item
  nextGoal.value = item.nextWeekGoal || item.weeklyGoal
  goalVisible.value = true
}

async function onChangeGoal() {
  saving.value = true
  try {
    await learningApi.changePlanGoal(editing.value.id, nextGoal.value)
    goalVisible.value = false
    ElMessage.success('已保存')
    await load()
  } catch (e) {
    ElMessage.error(friendly(e, '保存失败，请稍后重试'))
  } finally {
    saving.value = false
  }
}

async function onPause(item) {
  busyId.value = item.id
  try {
    await learningApi.pausePlan(item.id)
    ElMessage.success('已暂停')
    await load()
  } catch (e) {
    ElMessage.error(friendly(e, '暂停失败，请稍后重试'))
  } finally {
    busyId.value = ''
  }
}

async function onResume(item) {
  busyId.value = item.id
  try {
    await learningApi.resumePlan(item.id)
    ElMessage.success('已恢复')
    await load()
  } catch (e) {
    ElMessage.error(friendly(e, '恢复失败，请稍后重试'))
  } finally {
    busyId.value = ''
  }
}

async function onQuit(item) {
  try {
    await ElMessageBox.confirm(`退出「${item.courseName}」？`, '退出计划', { type: 'warning' })
  } catch {
    return
  }
  busyId.value = item.id
  try {
    await learningApi.quitPlan(item.id)
    ElMessage.success('已退出学习计划')
    await load()
  } catch (e) {
    ElMessage.error(friendly(e, '退出失败，请稍后重试'))
  } finally {
    busyId.value = ''
  }
}

onMounted(load)
</script>

<style scoped>
.plan-page__head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.plan-note { color: #606266; font-size: 13px; line-height: 1.6; }
.plan-form { margin: 12px 0 16px; }
.plan-unit { margin-left: 8px; color: #606266; }
.plan-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.plan-card__title { font-weight: 600; margin-bottom: 8px; }
.plan-card__tags { display: flex; gap: 6px; margin-bottom: 8px; }
.plan-card p { margin: 6px 0; font-size: 13px; color: #606266; }
.plan-warn { color: #c45656; }
.plan-card__actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
@media (max-width: 768px) {
  .plan-page__head { align-items: flex-start; flex-direction: column; }
  .plan-list { grid-template-columns: 1fr; }
  .plan-form :deep(.el-form) { display: flex; flex-direction: column; }
}
</style>
