<template>
  <el-card class="note-panel" shadow="never">
    <div class="note-panel__bar">
      <el-radio-group v-model="tab" size="small" @change="switchTab">
        <el-radio-button value="mine">我的笔记</el-radio-button>
        <el-radio-button value="public">公开笔记</el-radio-button>
      </el-radio-group>
      <div class="note-panel__tools">
        <el-input v-if="tab === 'mine'" v-model="keyword" clearable placeholder="搜索我的笔记" @keyup.enter="load" />
        <el-select v-else v-model="sort" style="width: 110px" @change="load">
          <el-option label="最新" value="time" />
          <el-option label="最热" value="hot" />
        </el-select>
        <el-button type="primary" :disabled="!chapterId" @click="openCreate">记一笔</el-button>
      </div>
    </div>

    <el-alert v-if="error" class="note-panel__error" type="warning" :title="error" show-icon :closable="false">
      <el-button size="small" :loading="loading" @click="load">重试</el-button>
    </el-alert>
    <div v-else-if="loading" class="note-panel__state">加载中...</div>
    <el-empty v-else-if="!records.length" description="还没有笔记" />
    <div v-else class="note-list">
      <article v-for="item in records" :key="item.id" class="note-card">
        <template v-if="item.locked">
          <div class="note-card__title">公开笔记</div>
          <p class="note-card__locked">{{ item.reason }}</p>
        </template>
        <template v-else>
          <div class="note-card__title">
            <span>{{ item.title }}</span>
            <el-tag size="small" effect="plain">{{ item.visibility === 'PUBLIC' ? '公开' : '私有' }}</el-tag>
          </div>
          <div class="note-card__meta">
            <span>{{ item.authorName }}</span>
            <span>{{ formatTime(item.updateTime || item.createTime) }}</span>
            <button v-if="item.videoPositionSec != null" type="button" class="note-card__time" @click="onSeek(item)">
              {{ formatDuration(item.videoPositionSec) }}
            </button>
          </div>
          <div class="note-card__body" v-html="renderMarkdown(item.content)"></div>
          <div class="note-card__actions">
            <el-button v-if="tab === 'public'" size="small" @click="onLike(item)">
              {{ item.liked ? '已赞' : '点赞' }} {{ item.likeCount || 0 }}
            </el-button>
            <el-button v-if="tab === 'public'" size="small" @click="toggleComments(item)">评论 {{ item.commentCount || 0 }}</el-button>
            <el-button v-if="item.mine" size="small" @click="openEdit(item)">编辑</el-button>
            <el-button v-if="item.mine" size="small" type="danger" plain @click="onRemove(item)">删除</el-button>
          </div>
          <div v-if="item.commentsOpen" class="note-comments">
            <div v-if="item.commentError" class="note-card__locked">
              {{ item.commentError }}
              <el-button link type="primary" @click="loadComments(item)">重试</el-button>
            </div>
            <p v-else-if="!item.comments.length" class="note-card__locked">还没有评论</p>
            <div v-for="comment in item.comments" :key="comment.id" class="note-comment">
              <span>{{ comment.authorName }}：{{ comment.content }}</span>
              <el-button v-if="comment.canDelete" link type="danger" @click="onRemoveComment(item, comment)">删除</el-button>
            </div>
            <div class="note-comment__form">
              <el-input v-model="item.draft" maxlength="500" placeholder="写下评论" />
              <el-button type="primary" :loading="item.commenting" @click="onComment(item)">发送</el-button>
            </div>
          </div>
        </template>
      </article>
    </div>
    <el-pagination
      v-if="total > pageSize"
      layout="prev, pager, next"
      :current-page="pageNum"
      :page-size="pageSize"
      :total="total"
      @current-change="onPage"
    />

    <el-dialog v-model="editor" :title="form.id ? '编辑笔记' : '记一笔'" width="520px" class="note-dialog">
      <el-form label-position="top">
        <el-form-item label="标题">
          <el-input v-model="form.title" maxlength="80" />
        </el-form-item>
        <el-form-item label="正文">
          <el-input v-model="form.content" type="textarea" :rows="6" maxlength="4000" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.withTime">关联当前播放位置 {{ formatDuration(form.position) }}</el-checkbox>
        </el-form-item>
        <el-form-item label="可见性">
          <el-radio-group v-model="form.visibility">
            <el-radio value="PRIVATE">私有</el-radio>
            <el-radio value="PUBLIC">公开</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editor = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { noteApi } from '../api'
import { renderMarkdown } from '../utils/markdown'

const emit = defineEmits(['seek'])
const props = defineProps({
  courseId: { type: String, required: true },
  chapterId: { type: String, default: '' },
  player: { type: Object, default: null }
})

const tab = ref('mine')
const keyword = ref('')
const sort = ref('time')
const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = 8
const loading = ref(false)
const error = ref('')
const editor = ref(false)
const saving = ref(false)
const form = ref({ id: '', title: '', content: '', visibility: 'PRIVATE', withTime: false, position: 0 })

function formatTime(value) {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(0, 16)
}

function formatDuration(sec) {
  const s = Math.max(0, Math.floor(Number(sec) || 0))
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

function switchTab() {
  pageNum.value = 1
  load()
}

async function load() {
  if (!props.courseId) return
  loading.value = true
  error.value = ''
  try {
    const data = tab.value === 'mine'
      ? await noteApi.mine({ courseId: props.courseId, keyword: keyword.value || undefined, pageNum: pageNum.value, pageSize })
      : await noteApi.publicList({
        courseId: props.courseId,
        chapterId: props.chapterId || undefined,
        sort: sort.value,
        pageNum: pageNum.value,
        pageSize
      })
    records.value = (data?.records || []).map((item) => ({ ...item, commentsOpen: false, comments: [], draft: '' }))
    total.value = Number(data?.total || 0)
  } catch (e) {
    records.value = []
    error.value = e?.message || '笔记暂时无法加载'
  } finally {
    loading.value = false
  }
}

function onPage(page) {
  pageNum.value = page
  load()
}

function openCreate() {
  form.value = {
    id: '',
    title: '',
    content: '',
    visibility: 'PRIVATE',
    withTime: false,
    position: props.player?.position?.() || 0
  }
  editor.value = true
}

async function openEdit(item) {
  try {
    const data = await noteApi.detail(item.id)
    form.value = {
      id: data.id,
      title: data.title || '',
      content: data.content || '',
      visibility: data.visibility || 'PRIVATE',
      withTime: data.videoPositionSec != null,
      position: data.videoPositionSec || props.player?.position?.() || 0
    }
    editor.value = true
  } catch (e) {
    ElMessage.error(e?.message || '笔记读取失败')
  }
}

async function save() {
  if (!form.value.title.trim() || !form.value.content.trim()) {
    ElMessage.warning('请填写标题和正文')
    return
  }
  saving.value = true
  const body = {
    courseId: props.courseId,
    chapterId: props.chapterId,
    title: form.value.title,
    content: form.value.content,
    visibility: form.value.visibility,
    videoPositionSec: form.value.withTime ? Math.floor(form.value.position || 0) : null
  }
  try {
    if (form.value.id) await noteApi.update(form.value.id, body)
    else await noteApi.create(body)
    editor.value = false
    pageNum.value = 1
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onRemove(item) {
  try {
    await ElMessageBox.confirm('删除后不能再查看这篇笔记', '删除笔记', { type: 'warning' })
  } catch {
    return
  }
  try {
    await noteApi.remove(item.id)
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

async function onLike(item) {
  try {
    const data = item.liked ? await noteApi.unlike(item.id) : await noteApi.like(item.id)
    item.liked = !!data.liked
    item.likeCount = data.likeCount
  } catch (e) {
    ElMessage.error(e?.message || '点赞失败')
  }
}

async function toggleComments(item) {
  item.commentsOpen = !item.commentsOpen
  if (item.commentsOpen) await loadComments(item)
}

async function loadComments(item) {
  item.commentError = ''
  try {
    const data = await noteApi.comments(item.id, { pageNum: 1, pageSize: 20 })
    item.comments = data?.records || []
  } catch (e) {
    item.commentError = e?.message || '评论加载失败'
  }
}

async function onComment(item) {
  if (!item.draft || !item.draft.trim()) return
  item.commenting = true
  try {
    await noteApi.comment(item.id, item.draft.trim())
    item.draft = ''
    await loadComments(item)
    item.commentCount = (item.comments || []).length
  } catch (e) {
    ElMessage.error(e?.message || '评论失败')
  } finally {
    item.commenting = false
  }
}

async function onRemoveComment(item, comment) {
  try {
    await ElMessageBox.confirm('删除这条评论', '删除评论', { type: 'warning' })
  } catch {
    return
  }
  try {
    await noteApi.removeComment(item.id, comment.id)
    await loadComments(item)
  } catch (e) {
    ElMessage.error(e?.message || '删除评论失败')
  }
}

function onSeek(item) {
  emit('seek', item)
}

watch(() => [props.courseId, props.chapterId], () => {
  pageNum.value = 1
  load()
}, { immediate: true })
</script>

<style scoped>
.note-panel { margin-top: 16px; }
.note-panel__bar, .note-panel__tools, .note-card__title, .note-card__meta, .note-card__actions, .note-comment, .note-comment__form {
  display: flex;
  gap: 8px;
  align-items: center;
}
.note-panel__bar { justify-content: space-between; flex-wrap: wrap; margin-bottom: 12px; }
.note-panel__tools { flex: 1; justify-content: flex-end; min-width: 220px; }
.note-panel__state, .note-card__locked { color: #909399; font-size: 13px; }
.note-card { padding: 12px 0; border-top: 1px solid #ebeef5; }
.note-card__title { justify-content: space-between; font-weight: 600; }
.note-card__meta, .note-card__actions { margin-top: 6px; color: #909399; font-size: 12px; flex-wrap: wrap; }
.note-card__time { border: 0; background: transparent; color: #4f46e5; cursor: pointer; padding: 0; }
.note-card__body { margin-top: 8px; font-size: 14px; line-height: 1.6; overflow-wrap: anywhere; }
.note-comments { margin-top: 8px; }
.note-comment { justify-content: space-between; padding: 4px 0; font-size: 13px; }
.note-comment__form { margin-top: 8px; }
@media (max-width: 768px) {
  .note-panel__bar, .note-panel__tools, .note-comment__form { align-items: stretch; flex-direction: column; }
  .note-dialog { width: 92vw; }
}
</style>
