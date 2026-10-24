<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="kz-card-header">
          <span>课程内容与资料 · {{ courseName }}</span>
          <el-button @click="$router.push('/teacher/courses')">返回列表</el-button>
        </div>
      </template>

      <el-form :inline="true">
        <el-form-item label="上传文件">
          <el-upload
            :http-request="onUpload"
            :show-file-list="false"
            accept=".jpg,.jpeg,.png,.gif,.webp,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.zip,.mp4"
          >
            <el-button type="primary" :loading="uploading">
              {{ uploading ? '上传中…' : '选择文件上传' }}
            </el-button>
          </el-upload>
        </el-form-item>
        <el-form-item>
          <span class="kz-tip">支持图片 / 文档 / 压缩包 / mp4，单文件不超过 20MB</span>
        </el-form-item>
      </el-form>

      <!-- M15.21-E：历史版本抽屉 -->
      <el-drawer v-model="historyVisible" title="历史版本（可追溯）" size="45%">
        <el-table :data="historyRows" size="small" border>
          <el-table-column prop="fileName" label="文件名" min-width="180" />
          <el-table-column prop="createTime" label="上传时间" width="160" />
          <el-table-column label="下载" width="90">
            <template #default="{ row }">
              <a :href="'/api' + row.fileUrl" target="_blank">下载</a>
            </template>
          </el-table-column>
        </el-table>
      </el-drawer>

      <el-table :data="resources" v-loading="loading" border stripe>
        <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
        <el-table-column label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ row.kindText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="fileSizeText" label="大小" width="100" />
        <el-table-column label="绑定章节" width="180">
          <template #default="{ row }">
            <span v-if="!row.chapterId">课程级</span>
            <span v-else>{{ chapterName(row.chapterId) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="知识库" min-width="160">
          <template #default="{ row }">
            <div>{{ kbText(row) }}</div>
            <div v-if="row.kbFailReason" style="color:#b42318;font-size:12px">{{ row.kbFailReason }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="onBind(row, 1)">设为封面</el-button>
            <el-button link type="primary" @click="onBind(row, 2)">教学资料</el-button>
            <el-button link type="primary" @click="onBind(row, 3, true)">章节视频</el-button>
            <el-button link type="primary" @click="onPreview(row)">预览</el-button>
            <el-button link type="primary" @click="onReplace(row)">替换</el-button>
            <el-button link type="primary" @click="onHistory(row)">历史</el-button>
            <el-button link type="primary" :disabled="kbBusy(row) || submittingId === row.id"
                       @click="onSubmitKb(row)">{{ kbAction(row) }}</el-button>
            <el-button link type="danger" @click="onRemove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="chapterDialog" title="绑定到章节" width="420px">
      <el-form label-width="90px">
        <el-form-item label="目标章节">
          <el-select v-model="bindChapterId" placeholder="请选择章节" style="width: 100%">
            <el-option v-for="c in chapters" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="chapterDialog = false">取消</el-button>
        <el-button type="primary" @click="confirmBind">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { resourceApi, chapterApi, teacherApi } from '../api'

const route = useRoute()
const courseId = String(route.params.id)
// M15.20-D：本人未绑定文件（绑定失败恢复；刷新后仍在）
const unbound = ref([])
const loading = ref(false)
const uploading = ref(false)
const resources = ref([])
const chapters = ref([])
const courseName = ref('')
const chapterDialog = ref(false)
const bindChapterId = ref(null)
let pendingBind = null
let pollTimer = null
const submittingId = ref(null)

const KB_TEXT = {
  NOT_SUBMITTED: '未提交',
  REVIEW_PENDING: '待审核',
  INDEXING: '索引处理中',
  SEARCHABLE: '可检索',
  FAILED: '失败',
  WITHDRAWN: '已撤回'
}

function kbText(row) {
  return KB_TEXT[row.kbPhase] || '未提交'
}

function kbBusy(row) {
  return row.kbPhase === 'REVIEW_PENDING' || row.kbPhase === 'INDEXING' || row.kbPhase === 'SEARCHABLE' || row.kbPhase === 'WITHDRAWN'
}

function kbAction(row) {
  if (row.kbPhase === 'FAILED' && row.kbRetryable) return '重试索引'
  if (row.kbPhase === 'FAILED') return '查看失败'
  if (kbBusy(row)) return kbText(row)
  return '加入知识库'
}

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function ensurePoll() {
  const pending = resources.value.some(r => r.kbPhase === 'INDEXING')
  if (!pending) {
    stopPoll()
    return
  }
  if (pollTimer) return
  let ticks = 0
  pollTimer = setInterval(async () => {
    ticks += 1
    if (ticks > 20) {
      stopPoll()
      return
    }
    await load(false)
    if (!resources.value.some(r => r.kbPhase === 'INDEXING')) stopPoll()
  }, 3000)
}

async function load(showLoading = true) {
  if (showLoading) loading.value = true
  try {
    const [list, chapterList] = await Promise.all([
      resourceApi.list(courseId),
      chapterApi.list(courseId)
    ])
    resources.value = list || []
    chapters.value = chapterList || []
    ensurePoll()
  } finally {
    loading.value = false
  }
}

// M15.21-E：预览（图片/PDF/视频浏览器原生；其他提示下载）/替换/历史/加入知识库
function onPreview(row) {
  const ct = (row.contentType || '').toLowerCase()
  const url = '/api' + row.fileUrl
  if (ct.startsWith('image/') || ct.includes('pdf') || ct.includes('video') || /\.(png|jpe?g|gif|webp|pdf|mp4)$/i.test(row.fileName || '')) {
    window.open(url, '_blank')
  } else {
    ElMessage.info(`该格式（${ct || row.fileName}）不支持在线预览，已触发下载`)
    window.location.href = url
  }
}

const replacingId = ref(null)
function onReplace(row) {
  const input = document.createElement('input')
  input.type = 'file'
  input.onchange = async () => {
    const file = input.files && input.files[0]
    if (!file) return
    try {
      await resourceApi.replaceRes(row.id, file)
      ElMessage.success('替换成功（旧版本保留历史）')
      await load()
    } catch (e) {
      ElMessage.error(e?.message || '替换失败')
    } finally {
      replacingId.value = null
    }
  }
  input.click()
}

const historyVisible = ref(false)
const historyRows = ref([])
async function onHistory(row) {
  try {
    historyRows.value = await resourceApi.history(row.id)
    historyVisible.value = true
  } catch (e) {
    ElMessage.error(e?.message || '历史加载失败')
  }
}

async function onSubmitKb(row) {
  if (submittingId.value) return
  submittingId.value = row.id
  const retry = row.kbPhase === 'FAILED' && row.kbRetryable
  try {
    if (retry) {
      const data = await resourceApi.retryKb(row.id)
      const queued = data?.alreadyQueued ? '索引任务已在处理中' : '已重新排队索引'
      ElMessage.success(`${queued}，完成前不会显示为可检索${data?.jobId ? '（任务 ' + data.jobId + '）' : ''}`)
    } else {
      await resourceApi.submitKb(row.id)
      ElMessage.success('已提交知识库。管理员审核通过后才会解析和索引，当前还不能检索')
    }
    await load()
  } catch (e) {
    ElMessage.error(e?.message || (retry ? '重试失败' : '提交失败'))
  } finally {
    submittingId.value = null
  }
}

async function bindUnbound(row) {
  try {
    const name = (row.fileName || '').toLowerCase()
    const kind = name.endsWith('.mp4') ? 3 : 2
    await resourceApi.bind(courseId, { resourceId: String(row.id), kind, chapterId: null })
    ElMessage.success('已加入当前课程')
    unbound.value = unbound.value.filter(x => x.id !== row.id)
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '加入失败')
  }
}

async function onUpload(options) {
  uploading.value = true
  try {
    const saved = await resourceApi.upload(options.file)
    // M15.19-A：上传后自动绑定当前课程（按扩展名判定类型：mp4→视频，其余→教学资料），
    // 修复「上传成功却不显示」的死路；后续可在列表中改绑章节/类型
    const name = (saved.fileName || '').toLowerCase()
    const kind = name.endsWith('.mp4') ? 3 : 2
    try {
      await resourceApi.bind(courseId, { resourceId: String(saved.id), kind, chapterId: null })
      ElMessage.success(`上传成功并已加入当前课程：${saved.fileName}`)
    } catch (be) {
      ElMessage.warning(`文件已上传（ID：${saved.id}）但绑定课程失败：${be?.message || '未知'}。可在列表重试或联系管理员`)
    }
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

function onBind(row, kind, needChapter = false) {
  if (needChapter) {
    if (!chapters.value.length) {
      ElMessage.warning('请先创建章节')
      return
    }
    pendingBind = { resourceId: row.id, kind }
    bindChapterId.value = row.chapterId || chapters.value[0].id
    chapterDialog.value = true
    return
  }
  doBind({ resourceId: row.id, kind, chapterId: null })
}

async function confirmBind() {
  chapterDialog.value = false
  await doBind({ ...pendingBind, chapterId: bindChapterId.value })
  pendingBind = null
}

async function doBind(payload) {
  try {
    await resourceApi.bind(courseId, payload)
    ElMessage.success('绑定成功')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '绑定失败')
  }
}

async function onRemove(row) {
  try {
    await ElMessageBox.confirm(`确认删除资料「${row.fileName}」？`, '删除资料', { type: 'warning' })
  } catch {
    return
  }
  try {
    await resourceApi.remove(row.id)
    ElMessage.success('资料已删除')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

const chapterName = (id) => chapters.value.find((c) => c.id === id)?.name || id

onUnmounted(stopPoll)

onMounted(async () => {
  try { unbound.value = await resourceApi.unbound() || [] } catch { unbound.value = [] }

  await load()
  try {
    const detail = await teacherApi.detail(courseId)
    courseName.value = detail.name
  } catch {
    courseName.value = ''
  }
})
</script>

<style scoped>
.kz-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.kz-tip {
  font-size: 12px;
  color: #909399;
}
</style>
