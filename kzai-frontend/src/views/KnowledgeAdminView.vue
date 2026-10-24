<template>
  <div class="kb-admin">
    <div class="kb-head">
      <h2>AI 知识库管理</h2>
      <el-tag size="small" type="info" effect="plain">
        TopK {{ topK }} · 阈值 {{ threshold }} · Embedding {{ provider }}
      </el-tag>
      <el-upload :show-file-list="false" :before-upload="beforeUpload" :http-request="doUpload"
                 accept=".pdf,.md,.markdown">
        <el-button type="primary" size="small">上传 PDF / Markdown</el-button>
      </el-upload>
    </div>

    <el-table :data="records" v-loading="loading" size="small" border>
      <el-table-column prop="title" label="文档" min-width="160" show-overflow-tooltip />
      <el-table-column label="来源" width="110">
        <template #default="{ row }">{{ row.sourceResourceId ? '教师资料' : '管理员上传' }}</template>
      </el-table-column>
      <el-table-column prop="docType" label="类型" width="90" />
      <el-table-column label="大小" width="80">
        <template #default="{ row }">{{ (row.fileSize / 1024).toFixed(1) }} KB</template>
      </el-table-column>
      <el-table-column prop="charCount" label="字符数" width="80" />
      <el-table-column prop="chunkCount" label="切片数" width="70" />
      <el-table-column prop="indexVersion" label="索引版本" width="80" />
      <el-table-column label="索引状态" width="150">
        <template #default="{ row }">
          <el-tag size="small" :type="statusType(row.indexStatus)">{{ statusText(row.indexStatus) }}</el-tag>
          <el-tooltip v-if="row.failReason" :content="row.failReason">
            <el-icon><WarningFilled /></el-icon>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="审核" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="reviewType(row.reviewStatus)" effect="plain">
            {{ reviewText(row.reviewStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="250">
        <template #default="{ row }">
          <el-button v-if="row.reviewStatus === 0" size="small" type="success" plain
                     @click="review(row, true)">审核发布</el-button>
          <el-button v-if="row.reviewStatus === 1" size="small" plain
                     @click="review(row, false)">撤回</el-button>
          <el-button size="small" @click="act(row, 'reparse')">重新解析</el-button>
          <el-button size="small" @click="act(row, 'reindex')">重建索引</el-button>
          <el-button size="small" type="danger" plain @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-divider>检索测试（真实 ES TopK + 阈值）</el-divider>
    <div class="kb-search">
      <el-input v-model="testQuestion" placeholder="输入问题，如：退款规则是什么？" style="max-width: 480px"
                @keydown.enter="searchTest" />
      <el-button type="primary" :loading="searching" @click="searchTest">检索测试</el-button>
    </div>
    <el-alert v-if="searchMsg" :type="searchDegraded ? 'warning' : 'info'" :closable="false"
              :title="searchMsg" style="margin-top: 10px" />
    <el-table v-if="hits.length" :data="hits" size="small" border style="margin-top: 10px">
      <el-table-column prop="docTitle" label="来源文档" min-width="140" />
      <el-table-column prop="chunkIndex" label="切片" width="60" />
      <el-table-column prop="score" label="相似度" width="90" />
      <el-table-column prop="provider" label="向量" width="90" />
      <el-table-column prop="snippet" label="片段" min-width="260" show-overflow-tooltip />
    </el-table>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import request from '../api'

const records = ref([])
const loading = ref(false)
const topK = ref(6)
const threshold = ref(0.6)
const provider = ref('-')
const testQuestion = ref('')
const hits = ref([])
const searching = ref(false)
const searchMsg = ref('')
const searchDegraded = ref(false)

const STATUS = { 0: '待处理', 1: '解析中', 2: '已切片', 3: '索引中', 4: '索引成功', 5: '失败' }
const statusText = (s) => STATUS[s] || s
const statusType = (s) => (s === 4 ? 'success' : s === 5 ? 'danger' : 'info')
const reviewText = (s) => (s === 1 ? '已发布' : s === 2 ? '已撤回' : '待审核')
const reviewType = (s) => (s === 1 ? 'success' : s === 2 ? 'warning' : 'info')

const load = async () => {
  loading.value = true
  try {
    const data = await request.get('/admin/ai/knowledge/documents', { params: { page: 1, size: 50 } })
    records.value = data?.records || []
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const beforeUpload = (file) => {
  const ok = /\.(pdf|md|markdown)$/i.test(file.name)
  if (!ok) ElMessage.error('仅支持 PDF / Markdown')
  if (file.size > 10 * 1024 * 1024) {
    ElMessage.error('文件超过 10MB')
    return false
  }
  return ok
}

const doUpload = async ({ file }) => {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('title', file.name.replace(/\.[^.]+$/, ''))
  fd.append('accessLevel', 'PUBLIC')
  try {
    await request.post('/admin/ai/knowledge/documents', fd,
      { headers: { 'Content-Type': 'multipart/form-data' } })
    ElMessage.success('上传成功（待审核），请点击「审核发布」以建立索引')
    await load()
  } catch (e) {
    ElMessage.error(e.message || '上传失败')
  }
}

const act = async (row, action) => {
  try {
    await request.post(`/admin/ai/knowledge/documents/${row.id}/${action}`)
    ElMessage.success('已加入任务队列')
    setTimeout(load, 2500)
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

const review = async (row, publish) => {
  try {
    if (!publish) {
      await ElMessageBox.confirm('撤回后该文档立即不可被检索，确认？', '撤回', { type: 'warning' })
    }
    await request.post(`/admin/ai/knowledge/documents/${row.id}/review`, { publish })
    ElMessage.success(publish ? '已发布，解析/索引任务已排队' : '已撤回（立即不可召回）')
    setTimeout(load, 1500)
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '操作失败')
  }
}

const del = async (row) => {
  try {
    await ElMessageBox.confirm(`删除文档「${row.title}」？向量将一并清理。`, '删除', { type: 'warning' })
    await request.delete(`/admin/ai/knowledge/documents/${row.id}`)
    ElMessage.success('已删除（向量清理任务排队）')
    setTimeout(load, 2500)
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '删除失败')
  }
}

const searchTest = async () => {
  if (!testQuestion.value.trim()) return
  searching.value = true
  try {
    const data = await request.post('/admin/ai/knowledge/search-test',
      { question: testQuestion.value.trim() })
    hits.value = data?.hits || []
    searchDegraded.value = Boolean(data?.degraded)
    searchMsg.value = data?.message || `命中 ${hits.value.length} 条（阈值 ${data?.threshold}）`
  } catch (e) {
    ElMessage.error(e.message || '检索失败')
  } finally {
    searching.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.kb-admin { max-width: 1200px; }
.kb-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.kb-head h2 { margin: 0; font-size: 18px; margin-right: 8px; }
.kb-search { display: flex; gap: 8px; }
</style>
