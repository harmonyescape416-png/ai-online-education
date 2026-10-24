<template>
  <div class="teacher-stats">
    <!-- M15.19-E：教师工作台摘要（本人课程口径；无样本显示 0 不显示 null%） -->
    <el-row :gutter="12" class="teacher-stats__summary">
      <el-col v-for="c in wbCards" :key="c.label" :span="4" :xs="12" :sm="8" :md="4">
        <el-card shadow="never" body-style="padding: 12px">
          <div class="teacher-stats__kpi">{{ wbError || !wb ? '—' : (wb[c.key] ?? 0) }}</div>
          <div class="teacher-stats__kpi-label">{{ c.label }}</div>
        </el-card>
      </el-col>
    </el-row>

    <h2 class="kz-page-title">课程学情统计</h2>

    <el-card shadow="never" class="teacher-stats__filter">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="我的课程">
          <el-select
            v-model="courseId"
            clearable
            filterable
            placeholder="全部课程"
            style="width: 260px"
            @change="onFilter"
          >
            <el-option v-for="c in courses" :key="c.id" :label="c.name" :value="String(c.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="统计区间">
          <el-date-picker
            v-model="dateRange"
            type="daterange"
            value-format="YYYY-MM-DD"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onFilter">查询</el-button>
          <el-button @click="onReset">重置</el-button>
          <el-button type="success" @click="onExport">导出 CSV</el-button>
        </el-form-item>
      </el-form>
      <div class="kz-tip">
        未选日期时，下方课程指标是累计口径。选择日期后，学习人数、时长和首次完课按上海时区的业务日统计，区间为开始日 00:00 到结束日次日 00:00（不含）。顶部工作台仍是累计人数和近 7 天提问，与区间无关。
      </div>
    </el-card>

    <div v-if="statsTotal > statsPageSize && !statsLoadError" class="teacher-stats__pager">
      <el-pagination
        layout="prev, pager, next, total"
        :total="statsTotal"
        :page-size="statsPageSize"
        :current-page="statsPage"
        @current-change="onPage"
      />
    </div>

    <el-empty v-if="statsLoadError && !loadingStats" description="学情数据加载失败">
      <el-button type="primary" size="small" @click="load">重试</el-button>
    </el-empty>
    <el-empty v-else-if="!loadingStats && !statsList.length" description="暂无学情数据" />

    <div v-loading="loadingStats">
      <el-card v-for="s in statsList" :key="s.courseId" class="teacher-stats__course" shadow="never">
        <template #header>
          <div class="kz-stats__head">
            <span class="kz-stats__title">{{ s.courseName }}</span>
            <el-tag size="small" effect="plain">{{ s.free ? '免费课程' : '付费课程' }}</el-tag>
            <el-tag size="small" :type="s.statsScope === 'RANGE' ? 'warning' : 'info'" effect="plain">
              {{ s.statsScope === 'RANGE' ? '所选区间' : '累计' }}
            </el-tag>
          </div>
        </template>

        <el-alert v-if="s.learningCoverage === 'INSUFFICIENT'" type="warning" :closable="false"
                  :title="s.coverageMessage || '所选区间缺少学习事实，不能把缺失显示成 0'" style="margin-bottom: 8px" />
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item :label="scopePrefix(s) + '学习人数'">{{ metric(s, s.learnerCount) }}</el-descriptions-item>
          <el-descriptions-item :label="scopePrefix(s) + '完课人数'">{{ metric(s, s.finishedLearnerCount) }}</el-descriptions-item>
          <el-descriptions-item :label="scopePrefix(s) + '完课率'">{{ rateText(s, s.finishRate) }}</el-descriptions-item>
          <el-descriptions-item label="章节数">{{ s.chapterCount }}</el-descriptions-item>
          <el-descriptions-item :label="scopePrefix(s) + '有效学习时长'">{{ durationText(s, s.totalStudySeconds) }}</el-descriptions-item>
          <el-descriptions-item :label="scopePrefix(s) + '平均学习时长'">{{ durationText(s, s.avgStudySeconds) }}</el-descriptions-item>
          <el-descriptions-item label="最近学习时间">{{ formatTime(s.lastStudyTime) }}</el-descriptions-item>
          <el-descriptions-item :label="scopePrefix(s) + '提问数'">{{ s.questionCount == null ? '—' : s.questionCount }}</el-descriptions-item>
          <el-descriptions-item label="AI 回复采纳率">{{ adoptText(s) }}</el-descriptions-item>
        </el-descriptions>

        <div class="teacher-stats__fold">
          <el-button link type="primary" @click="toggleDetail(s.courseId, 'chapters')">
            {{ isOpen(s.courseId, 'chapters') ? '收起章节' : '展开章节' }}
          </el-button>
          <el-button link type="primary" @click="toggleDetail(s.courseId, 'learners')">
            {{ isOpen(s.courseId, 'learners') ? '收起学员' : '展开学员' }}
          </el-button>
        </div>

        <template v-if="isOpen(s.courseId, 'chapters')">
          <el-divider content-position="left">章节完成分布</el-divider>
          <el-table :data="s.chapterStats || []" size="small" border>
            <el-table-column prop="chapterOrder" label="序号" width="70" />
            <el-table-column prop="chapterName" label="章节" min-width="160" />
            <el-table-column prop="learnerCount" label="学习人数" width="100" />
            <el-table-column prop="finishedCount" label="完成人数" width="100" />
            <el-table-column label="完成率" width="110">
              <template #default="{ row }">{{ row.finishRate }}%</template>
            </el-table-column>
            <el-table-column label="累计有效学习时长" min-width="160">
              <template #default="{ row }">{{ formatDuration(row.totalStudySeconds) }}</template>
            </el-table-column>
          </el-table>
        </template>

        <template v-if="isOpen(s.courseId, 'learners')">
          <el-divider content-position="left">{{ s.statsScope === 'RANGE' ? '区间内有学习活动的学员（进度是当前状态，不是历史快照）' : '最近学习情况' }}</el-divider>
          <el-table :data="s.recentStudies || []" size="small" border>
            <el-table-column prop="username" label="学员" width="120" />
            <el-table-column prop="chapterName" label="最近学习章节" min-width="160" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 2 ? 'success' : 'primary'" effect="plain">
                  {{ row.statusText }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="课程进度" width="110">
              <template #default="{ row }">{{ row.progress }}%</template>
            </el-table-column>
            <el-table-column label="本章有效学习时长" width="150">
              <template #default="{ row }">{{ formatDuration(row.studySeconds) }}</template>
            </el-table-column>
            <el-table-column label="最近学习时间" min-width="160">
              <template #default="{ row }">{{ formatTime(row.lastStudyTime) }}</template>
            </el-table-column>
          </el-table>
        </template>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { teacherStatsApi, teacherApi } from '../api'

const wb = ref({})
const statsPage = ref(1)
const statsPageSize = 5
const statsTotal = ref(0)
const expandedCourses = ref({})
const loadingStats = ref(false)
const statsLoadError = ref(false)
const courses = ref([])
const statsList = ref([])
const courseId = ref('')
const dateRange = ref(null)
const wbError = ref(false)

const wbCards = [
  { key: 'courseCount', label: '我的课程（累计）' },
  { key: 'learners', label: '学习人数（累计）' },
  { key: 'pendingQuestions', label: '待回复提问（累计）' },
  { key: 'aiUnchecked', label: 'AI 回复待检查' },
  { key: 'weeklyQuestions', label: '近 7 天提问' }
]

function scopePrefix(s) {
  return s?.statsScope === 'RANGE' ? '区间' : '累计'
}

function covered(s) {
  return s?.learningCoverage !== 'INSUFFICIENT'
}

function metric(s, value) {
  if (!covered(s)) return '数据覆盖不足'
  return value == null ? '—' : value
}

function rateText(s, value) {
  if (!covered(s)) return '数据覆盖不足'
  return value == null ? '—' : `${value}%`
}

function durationText(s, value) {
  if (!covered(s)) return '数据覆盖不足'
  return formatDuration(value)
}

function adoptText(s) {
  if (s.interactionReady === false) return '统计失败'
  if (s.aiSampleCount == null || Number(s.aiSampleCount) === 0) return '暂无AI回复样本'
  const rate = Number(s.aiAdoptRate || 0).toFixed(1)
  return `${rate}%（${s.aiAdoptedCount}/${s.aiSampleCount}）`
}

function filterParams() {
  const params = {
    courseId: courseId.value || undefined,
    pageNum: statsPage.value,
    pageSize: statsPageSize
  }
  if (Array.isArray(dateRange.value) && dateRange.value[0] && dateRange.value[1]) {
    params.dateFrom = dateRange.value[0]
    params.dateTo = dateRange.value[1]
  }
  return params
}

function isOpen(id, part) {
  return Boolean(expandedCourses.value[id]?.[part])
}

function toggleDetail(id, part) {
  const cur = expandedCourses.value[id] || { chapters: false, learners: false }
  expandedCourses.value = {
    ...expandedCourses.value,
    [id]: { ...cur, [part]: !cur[part] }
  }
}

async function loadWorkbench() {
  wbError.value = false
  try {
    wb.value = await teacherStatsApi.workbenchSummary() || {}
  } catch (e) {
    wb.value = null
    wbError.value = true
    if (!e?.notified) ElMessage.error(e?.message || '工作台摘要加载失败，请刷新重试')
  }
}

function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function formatDuration(sec) {
  const s = Math.max(0, Math.floor(Number(sec) || 0))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const ss = s % 60
  if (h > 0) return `${h} 小时 ${m} 分`
  if (m > 0) return `${m} 分 ${ss} 秒`
  return `${ss} 秒`
}

async function loadCourses() {
  try {
    const data = await teacherApi.list({ pageNum: 1, pageSize: 100 })
    courses.value = data.records || []
  } catch {
    courses.value = []
  }
}

let loadSeq = 0
async function load() {
  loadingStats.value = true
  statsLoadError.value = false
  const seq = ++loadSeq
  try {
    const data = await teacherStatsApi.stats(filterParams())
    if (seq !== loadSeq) return
    const records = data?.records || []
    statsList.value = records
    statsTotal.value = Number(data?.total ?? records.length)
    const maxPage = Math.max(1, Math.ceil(statsTotal.value / statsPageSize) || 1)
    if (statsPage.value > maxPage) {
      statsPage.value = maxPage
      if (seq === loadSeq) return load()
    }
  } catch (e) {
    if (seq !== loadSeq) return
    if (!e?.notified) ElMessage.error(e?.message || '学情统计加载失败')
    statsLoadError.value = true
    statsList.value = []
    statsTotal.value = 0
  } finally {
    if (seq === loadSeq) loadingStats.value = false
  }
}

function onFilter() {
  statsPage.value = 1
  expandedCourses.value = {}
  load()
}

function onReset() {
  courseId.value = ''
  dateRange.value = null
  onFilter()
}

function onPage(page) {
  if (page === statsPage.value) return
  statsPage.value = page
  expandedCourses.value = {}
  load()
}

async function onExport() {
  try {
    const params = filterParams()
    delete params.pageNum
    delete params.pageSize
    const blob = await teacherStatsApi.exportCsv(params)
    const url = URL.createObjectURL(new Blob([blob], { type: 'text/csv;charset=utf-8' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `learning-stats-${Date.now()}.csv`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('学情统计 CSV 已导出（UTF-8 BOM，含公式注入防护）')
  } catch (e) {
    ElMessage.error(e?.message || '导出失败')
  }
}

loadWorkbench()
onMounted(async () => {
  await loadCourses()
  await load()
})
</script>

<style scoped>
.teacher-stats__summary { margin-bottom: 14px; }
.teacher-stats__filter { margin-bottom: 16px; }
.teacher-stats__pager { margin-top: 8px; display: flex; justify-content: flex-end; }
.teacher-stats__course { margin-bottom: 16px; }
.teacher-stats__kpi { font-size: 24px; font-weight: 600; }
.teacher-stats__kpi-label { color: #64748b; font-size: 12px; }
.teacher-stats__fold { display: flex; gap: 8px; margin-top: 12px; }
.kz-stats__head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.kz-stats__title {
  font-size: 15px;
  font-weight: 600;
}
.kz-tip {
  font-size: 12px;
  color: #909399;
}
</style>
