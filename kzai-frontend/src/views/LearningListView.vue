<template>
  <div>
    <h2 class="kz-page-title">我的学习</h2>
    <el-card shadow="never" class="plan-entry">
      <strong>学习计划</strong>
      <el-button type="primary" @click="router.push('/learning/plans')">查看学习计划</el-button>
    </el-card>

    <el-card shadow="never" style="margin-bottom: 16px">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="学习状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 140px">
            <el-option label="未开始" :value="0" />
            <el-option label="学习中" :value="1" />
            <el-option label="已完成" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="课程名称">
          <el-input v-model="query.keyword" clearable placeholder="按课程名筛选" style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-alert v-if="loadError && list.length" class="learn-load-error" type="warning" :title="loadError" show-icon :closable="false">
      <el-button size="small" :loading="loading" @click="load">重试</el-button>
    </el-alert>
    <el-empty v-if="loadError && !list.length" :description="loadError">
      <el-button type="primary" :loading="loading" @click="load">重试</el-button>
    </el-empty>
    <el-empty v-else-if="!loading && !list.length" description="还没有加入学习的课程，去课程列表选择免费课程加入或购买课程吧" />
    <el-row v-else-if="list.length" :gutter="16">
      <el-col v-for="item in list" :key="item.id" :xs="24" :sm="12" :lg="8" style="margin-bottom: 16px">
        <el-card shadow="hover" class="kz-lesson-card">
          <div class="kz-lesson-card__cover">
            <img :src="coverUrlOf(item)" loading="lazy" alt="课程封面" @error="onCoverError" />
          </div>
          <div class="kz-lesson-card__body">
            <div class="kz-lesson-card__title">{{ item.courseName }}</div>
            <div class="kz-lesson-card__tags">
              <el-tag size="small" :type="item.status === 2 ? 'success' : 'primary'" effect="plain">
                {{ item.totalChapterCount === 0 ? '未开始' : item.statusText }}
              </el-tag>
              <el-tag size="small" effect="plain">{{ item.free ? '免费课程' : '付费课程' }}</el-tag>
              <el-tag v-if="!item.entitlementValid" size="small" type="danger" effect="plain">权益已失效</el-tag>
            </div>
            <StudyProgress
              v-if="item.totalChapterCount > 0"
              :progress="item.progress"
              :finished="item.finishedChapterCount"
              :total="item.totalChapterCount"
            />
            <div v-else class="kz-lesson-card__meta" style="color:#98a2b3">暂无章节</div>
            <div class="kz-lesson-card__meta">
              最近学习：{{ item.lastChapterName || '尚未开始' }}
            </div>
            <div class="kz-lesson-card__meta">
              上次学习时间：{{ item.lastStudyTime ? formatTime(item.lastStudyTime) : '—' }}
            </div>
            <div v-if="item.lastVideoPositionSec > 0" class="kz-lesson-card__meta">
              断点位置：{{ formatDuration(item.lastVideoPositionSec) }}
            </div>
            <div class="kz-lesson-card__meta">
              累计学习时长：{{ formatDuration(item.studySeconds) }}
            </div>
            <a
              v-if="learnPath(item)"
              class="learn-entry"
              :href="`#${learnPath(item)}`"
              @click="onContinue($event, item)"
            >{{ continueLabel(item) }}</a>
            <div v-if="item.status === 1 && item.progress === 0 && item.totalChapterCount > 0 && item.lastChapterName" class="kz-lesson-hint">
              已开始学习，尚未完成章节
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-pagination
      v-if="total > pageSize"
      background
      layout="prev, pager, next"
      :current-page="query.pageNum"
      :page-size="pageSize"
      :total="total"
      @current-change="onPageChange"
    />
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { learningApi } from '../api'
import { coverUrlOf, onCoverError } from '../utils/courseCover'
import StudyProgress from '../components/StudyProgress.vue'

const router = useRouter()
const list = ref([])
const total = ref(0)
const pageSize = ref(9)
const loading = ref(false)
const loadError = ref('')
const query = reactive({ status: null, keyword: '', pageNum: 1, pageSize: 9 })

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

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await learningApi.list({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: query.status === null || query.status === '' ? undefined : query.status,
      keyword: query.keyword || undefined
    })
    list.value = data?.records || []
    total.value = Number(data?.total || 0)
  } catch (e) {
    const message = e?.message && !String(e.message).startsWith('Request failed with status code')
      ? e.message
      : '学习列表暂时无法加载，请稍后重试'
    loadError.value = message
    if (!e?.notified) {
      ElMessage.error(message)
    }
  } finally {
    loading.value = false
  }
}

function onPageChange(page) {
  query.pageNum = page
  load()
}

function onReset() {
  query.status = null
  query.keyword = ''
  query.pageNum = 1
  load()
}

function chapterLabel(item) {
  const matched = String(item.lastChapterName || '').match(/第\s*\d+\s*章/)
  return matched ? matched[0].replace(/\s+/g, '') : ''
}

function continueLabel(item) {
  const chapter = chapterLabel(item)
  if (item.progress > 0 && item.lastChapterName) return chapter ? `继续学习${chapter}` : '继续学习'
  return '开始学习'
}

function learnPath(item) {
  if (item?.courseId == null || item.courseId === '') return ''
  const chapter = item.lastChapterId ? `?chapterId=${encodeURIComponent(item.lastChapterId)}` : ''
  return `/learn/${item.courseId}${chapter}`
}

function onContinue(event, item) {
  const path = learnPath(item)
  if (!path) {
    event.preventDefault()
    ElMessage.warning('暂时无法进入该课程')
    return
  }
  event.preventDefault()
  router.push(path).catch(() => {
    window.location.hash = path
  })
}

onMounted(load)
</script>

<style scoped>
.kz-lesson-card__cover {
  height: 130px;
  background: #f5f7fa;
  border-radius: 4px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
.kz-lesson-card__cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.kz-lesson-card__placeholder {
  color: #c0c4cc;
  font-size: 13px;
}
.kz-lesson-card__body {
  padding-top: 12px;
}
.kz-lesson-card__title {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.kz-lesson-card__tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.kz-lesson-card__meta {
  font-size: 12px;
  color: #909399;
  margin-top: 4px;
}
.kz-lesson-hint { font-size: 12px; color: #98a2b3; text-align: center; margin-top: 4px; }
.learn-entry {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  margin-top: 10px;
  box-sizing: border-box;
  min-height: 24px;
  padding: 5px 11px;
  border-radius: 4px;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 12px;
  line-height: 1;
  text-decoration: none;
  cursor: pointer;
}
.learn-entry:hover { color: #fff; opacity: 0.92; }
.learn-load-error { margin-bottom: 16px; }
.plan-entry { margin-bottom: 16px; }
.plan-entry :deep(.el-card__body) { display: flex; justify-content: space-between; gap: 12px; align-items: center; }
@media (max-width: 768px) {
  .plan-entry :deep(.el-card__body) { flex-direction: column; align-items: stretch; }
}
</style>
