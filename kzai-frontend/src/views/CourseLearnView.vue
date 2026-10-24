<template>
  <div v-if="detail">
    <div class="kz-learn__head">
      <div>
        <el-button link type="primary" @click="router.push('/learning')">← 返回我的学习</el-button>
        <h2 class="kz-page-title" style="margin-top: 8px">{{ detail.courseName }}</h2>
      </div>
      <div style="width: 240px">
        <StudyProgress
          :progress="detail.progress"
          :finished="detail.finishedChapterCount"
          :total="detail.totalChapterCount"
          label="课程进度"
        />
      </div>
    </div>

    <el-alert
      v-if="!detail.entitlementValid"
      type="warning"
      :closable="false"
      show-icon
      :title="detail.entitlementReason || '当前未取得该课程的学习权益，仅可试看标记为试看的章节'"
      style="margin-bottom: 16px"
    />
    <el-alert
      v-else-if="detail.entitlementSource === 'PAID'"
      type="success"
      :closable="false"
      show-icon
      :title="detail.expireTime ? `已取得学习权益，有效期至 ${formatTime(detail.expireTime)}` : '已取得学习权益（长期有效）'"
      style="margin-bottom: 16px"
    />

    <el-row :gutter="16">
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>
            <div class="kz-learn__chapter-head">
              <span>{{ current?.name || '请选择章节' }}</span>
              <el-tag v-if="current?.status === 2" type="success" size="small" effect="plain">已完成</el-tag>
              <el-tag v-else-if="current" type="primary" size="small" effect="plain">{{ current.statusText }}</el-tag>
            </div>
          </template>

          <VideoPlayer
            v-if="videoSrc"
            ref="playerRef"
            :src="videoSrc"
            :initial-position="current?.videoPositionSec || 0"
            @progress="onProgress"
            @ended="onEnded"
          />
          <el-empty v-else-if="!current" description="请从右侧目录选择章节" />
          <div v-else>
            <p style="white-space: pre-wrap">{{ current.description || '本章暂无图文说明' }}</p>
          </div>

          <el-divider v-if="materials.length" />
          <div v-if="materials.length">
            <div class="kz-learn__subtitle">本章资料（下载时服务端重新校验学习权益）</div>
            <ul class="kz-learn__materials">
              <li v-for="m in materials" :key="m.id">
                <span>{{ m.fileName }}</span>
                <el-button link type="primary" @click="onDownload(m)">下载</el-button>
              </li>
            </ul>
          </div>

          <div class="kz-learn__actions">
            <el-button :disabled="!prevChapter" @click="onGo(prevChapter)">上一章</el-button>
            <el-button type="primary" :disabled="!current || !current.accessible" @click="onComplete">
              标记本章完成
            </el-button>
            <el-button :disabled="!nextChapter" @click="onGo(nextChapter)">下一章</el-button>
          </div>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card shadow="never">
          <ChapterNav :chapters="detail.chapters || []" :current-id="currentId" @select="onSelect" />
        </el-card>
      </el-col>
    </el-row>
    <NotePanel
      :course-id="String(courseId)"
      :chapter-id="currentId == null ? '' : String(currentId)"
      :player="playerRef"
      @seek="onNoteSeek"
    />
  </div>
  <div v-else-if="errorMsg" class="kz-empty">
    <el-result icon="warning" :title="errorMsg" sub-title="请确认课程状态与学习权益后重试">
      <template #extra>
        <el-button type="primary" @click="router.push('/learning')">返回我的学习</el-button>
      </template>
    </el-result>
  </div>
  <div v-else class="kz-empty">加载中...</div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { learningApi } from '../api'
import ChapterNav from '../components/ChapterNav.vue'
import StudyProgress from '../components/StudyProgress.vue'
import VideoPlayer from '../components/VideoPlayer.vue'
import NotePanel from '../components/NotePanel.vue'

const route = useRoute()
const router = useRouter()
const courseId = computed(() => route.params.courseId)

const detail = ref(null)
const currentId = ref(null)
const errorMsg = ref('')
const playerRef = ref(null)

const current = computed(() => (detail.value?.chapters || []).find((c) => String(c.id) === String(currentId.value)))
const chapters = computed(() => detail.value?.chapters || [])
const videoSrc = computed(() => {
  const res = (current.value?.resources || []).find((r) => r.kind === 3)
  return res ? withAuth(res.fileUrl) : ''
})
const materials = computed(() => (current.value?.resources || []).filter((r) => r.kind === 2))
const prevChapter = computed(() => {
  const idx = chapters.value.findIndex((c) => String(c.id) === String(currentId.value))
  return idx > 0 ? chapters.value[idx - 1] : null
})
const nextChapter = computed(() => {
  const idx = chapters.value.findIndex((c) => String(c.id) === String(currentId.value))
  return idx >= 0 && idx < chapters.value.length - 1 ? chapters.value[idx + 1] : null
})

/** 受控资源访问：下载/播放都走网关，服务端重新校验权益 */
function withAuth(url) {
  return url
}

function formatTime(value) {
  if (!value) return '—'
  return String(value).replace('T', ' ').slice(0, 16)
}

function pickChapter(list, preferId, lastId) {
  const byId = (id) => list.find((chapter) => String(chapter.id) === String(id))
  const prefer = preferId ? byId(preferId) : null
  if (prefer?.accessible) return prefer
  const last = lastId ? byId(lastId) : null
  if (last?.accessible) return last
  return list.find((chapter) => chapter.accessible) || list[0] || null
}

async function load() {
  const id = courseId.value
  if (!id) {
    detail.value = null
    errorMsg.value = '课程不存在'
    return
  }
  try {
    detail.value = await learningApi.detail(id)
    errorMsg.value = ''
    const list = detail.value.chapters || []
    const target = pickChapter(list, route.query.chapterId, detail.value.lastChapterId)
    currentId.value = target ? target.id : null
  } catch (e) {
    detail.value = null
    errorMsg.value = e?.message || '课程学习信息加载失败'
  }
}

function onSelect(ch) {
  currentId.value = ch.id
}

async function onProgress(payload) {
  if (!currentId.value || payload?.error) return
  try {
    detail.value = await learningApi.progress(courseId.value, {
      chapterId: String(currentId.value),
      videoPositionSec: payload.videoPositionSec,
      videoDurationSec: payload.videoDurationSec,
      completed: payload.completed === true
    })
  } catch (e) {
    ElMessage.error(e?.message || '进度上报失败')
  }
}

async function onEnded() {
  ElMessage.success('本章视频已播完，学习状态由服务端更新')
}

async function onComplete() {
  try {
    detail.value = await learningApi.complete(courseId.value, String(currentId.value))
    ElMessage.success('已标记本章完成，课程进度由服务端重算')
  } catch (e) {
    ElMessage.error(e?.message || '标记完成失败')
  }
}

async function onNoteSeek(note) {
  const chapter = (detail.value?.chapters || []).find((item) => String(item.id) === String(note.chapterId))
  if (!chapter?.accessible) {
    ElMessage.warning('当前没有该章节的播放权限')
    return
  }
  if (String(currentId.value) !== String(chapter.id)) {
    currentId.value = chapter.id
    await nextTick()
  }
  if (!playerRef.value?.seek?.(Number(note.videoPositionSec))) {
    ElMessage.warning('播放器尚未就绪')
  }
}

function onGo(ch) {
  if (!ch) return
  currentId.value = ch.id
}

function onDownload(m) {
  // 下载链接为受控路径 /files/{id}/download，服务端会在下载时重新校验学习权益
  window.open(withAuth(m.fileUrl), '_blank')
}

watch(
  () => [route.params.courseId, route.query.chapterId],
  () => load(),
  { immediate: true }
)
</script>

<style scoped>
.kz-learn__head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 24px;
}
.kz-learn__chapter-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.kz-learn__subtitle {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}
.kz-learn__materials {
  list-style: none;
  margin: 0;
  padding: 0;
}
.kz-learn__materials li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px dashed #ebeef5;
}
.kz-learn__actions {
  display: flex;
  gap: 12px;
  margin-top: 20px;
}
</style>
