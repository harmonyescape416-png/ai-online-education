<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>课程问答监管</span>
          <el-button size="small" :loading="loading" @click="load">查询</el-button>
        </div>
      </template>
      <div class="filters">
        <el-select v-model="f.courseId" clearable filterable placeholder="全部课程" size="small" style="width:180px" @change="load">
          <el-option v-for="c in courseOptions" :key="c.id" :label="c.name" :value="String(c.id)" />
        </el-select>
        <el-date-picker v-model="f.range" type="daterange" size="small" start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DD" />
        <el-input v-model="f.keyword" placeholder="标题/内容关键词" size="small" style="width:170px" clearable @keyup.enter="load" />
        <el-select v-model="f.hidden" placeholder="隐藏状态" size="small" style="width:120px" clearable>
          <el-option label="正常显示" :value="0" /><el-option label="已隐藏" :value="1" />
        </el-select>
        <el-button size="small" type="primary" @click="load">查询</el-button>
      </div>
      <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" show-icon />
      <el-table :data="rows" size="small" v-loading="loading">
        <el-table-column prop="id" label="问题 ID" width="180" show-overflow-tooltip />
        <el-table-column prop="title" label="标题" show-overflow-tooltip />
        <el-table-column label="课程" width="180"><template #default="{ row }">{{ courseMap[row.courseId] || row.courseId }}</template></el-table-column>
        <el-table-column label="提问人" width="140"><template #default="{ row }">{{ row.anonymity === 1 ? '匿名' : (userMap[row.userId] || `用户 ${row.userId}`) }}</template></el-table-column>
        <el-table-column prop="answerTimes" label="回复数" width="70" />
        <el-table-column label="隐藏" width="80">
          <template #default="s"><el-tag v-if="s.row.hidden === 1" size="small" type="danger">已隐藏</el-tag>
            <span v-else>正常</span></template>
        </el-table-column>
        <el-table-column label="提问时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="s">
            <el-button size="small" link type="primary" @click="openDetail(s.row)">详情</el-button>
            <el-button size="small" link :type="s.row.hidden === 1 ? 'success' : 'warning'" @click="toggleQ(s.row)">
              {{ s.row.hidden === 1 ? '恢复' : '隐藏' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="prev, pager, next, total" :total="total" :page-size="size"
        :current-page="page" @current-change="onPage" style="margin-top:10px" />
    </el-card>

    <el-dialog v-model="dlg" title="问答详情" width="760px">
      <template v-if="cur">
        <h4>{{ cur.title }} <el-tag v-if="cur.hidden === 1" size="small" type="danger">已隐藏</el-tag></h4>
        <div class="desc">{{ cur.description }}</div>
        <div v-for="r in cur.replies || []" :key="r.id" class="reply">
          <div class="reply-head">
            <el-tag size="small" :type="r.source === 'SYSTEM_AI' ? 'warning' : (r.isStudent === 1 ? 'info' : 'success')">
              {{ r.source === 'SYSTEM_AI' ? ('AI · ' + (r.modelCode || '')) : (r.isStudent === 1 ? '学员' : '教师') }}
            </el-tag>
            <el-tag v-if="r.hidden === 1" size="small" type="danger">已隐藏</el-tag>
            <el-tag v-if="r.featured === 1" size="small" type="warning">精华</el-tag>
            <el-tag v-if="r.corrected === 1" size="small" type="info">已修正 {{ fmtTime(r.correctTime) }}</el-tag>
            <span class="reply-time">{{ fmtTime(r.createTime) }}</span>
            <el-button size="small" link :type="r.hidden === 1 ? 'success' : 'warning'"
              style="margin-left:auto" @click="toggleR(r)">{{ r.hidden === 1 ? '恢复' : '隐藏' }}</el-button>
          </div>
          <div class="reply-content">{{ r.content }}</div>
          <div v-if="r.prevContent" class="reply-prev">修正前：{{ r.prevContent }}</div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { fmtTime } from '../utils/format'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request, { courseApi } from '../api'

const rows = ref([]); const total = ref(0); const page = ref(1); const size = 10
// M15.46：课程名映射（下拉与列显示）+提问人回退显示
const courseOptions = ref([])
const courseMap = ref({})
const userMap = ref({})
const loading = ref(false); const dlg = ref(false); const cur = ref(null)
const loadError = ref('')
const f = ref({ courseId: '', userId: '', range: null, keyword: '', hidden: null })

const load = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const params = { page: page.value, size }
    for (const k of ['courseId', 'userId', 'keyword', 'hidden']) {
      if (f.value[k] !== '' && f.value[k] !== null && f.value[k] !== undefined) params[k] = f.value[k]
    }
    if (f.value.range && f.value.range[0]) { params.startTime = f.value.range[0]; params.endTime = f.value.range[1] }
    const d = await request.get('/admin/supervision/questions', { params })
    rows.value = d.records || []
    total.value = Number(d.total)
  } catch (error) {
    loadError.value = error?.message || '课程问答加载失败，请重试'
  } finally { loading.value = false }
}
const onPage = (p) => { page.value = p; load() }

const openDetail = async (row) => {
  cur.value = await request.get(`/admin/supervision/questions/${row.id}`)
  dlg.value = true
}
const askReason = () => ElMessageBox.prompt('请输入监管原因（必填，将写入审计）', '监管操作',
  { inputValidator: v => !!(v && v.trim()) || '原因不能为空' })
const toggleQ = async (row) => {
  const { value } = await askReason()
  await request.patch(`/admin/supervision/questions/questions/${row.id}/visibility`,
    { visible: row.hidden === 1, reason: value.trim() })
  ElMessage.success('已操作'); load()
}
const toggleR = async (r) => {
  const { value } = await askReason()
  await request.patch(`/admin/supervision/questions/replies/${r.id}/visibility`,
    { visible: r.hidden === 1, reason: value.trim() })
  ElMessage.success('已操作'); cur.value = await request.get(`/admin/supervision/questions/${cur.value.id}`)
}
onMounted(load)
</script>

<style scoped>
.filters { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.desc { color: #606266; margin: 8px 0 14px; }
.reply { border-top: 1px solid #ebeef5; padding: 10px 0; }
.reply-head { display: flex; gap: 6px; align-items: center; margin-bottom: 4px; }
.reply-time { color: #909399; font-size: 12px; }
.reply-content { white-space: pre-wrap; font-size: 13px; }
.reply-prev { color: #909399; font-size: 12px; margin-top: 4px; background: #f4f4f5; padding: 6px; border-radius: 4px; }
</style>
