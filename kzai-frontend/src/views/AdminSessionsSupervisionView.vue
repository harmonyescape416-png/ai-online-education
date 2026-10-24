<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>AI 会话监管</span>
          <el-button size="small" :loading="loading" @click="load">查询</el-button>
        </div>
      </template>
      <div class="filters">
        <el-input v-model="f.userId" placeholder="用户 ID" size="small" style="width:160px" clearable />
        <el-date-picker v-model="f.range" type="daterange" size="small" start-placeholder="开始日期" end-placeholder="结束日期" value-format="YYYY-MM-DD" />
        <el-select v-model="f.agent" placeholder="意图" size="small" style="width:140px" clearable>
          <el-option v-for="a in ['RECOMMEND','CONSULT','BUY','KNOWLEDGE','OTHER']" :key="a" :label="a" :value="a" />
        </el-select>
        <el-input v-model="f.keyword" placeholder="消息关键词" size="small" style="width:180px" clearable @keyup.enter="load" />
        <el-button size="small" type="primary" @click="load">查询</el-button>
      </div>
      <el-table :data="rows" size="small" v-loading="loading">
        <el-table-column prop="sessionId" label="会话 ID" width="300" show-overflow-tooltip />
        <el-table-column prop="userId" label="用户" width="180" />
        <el-table-column prop="title" label="标题" show-overflow-tooltip />
        <el-table-column prop="lastAgent" label="最近意图" width="110">
          <template #default="s"><span>{{ s.row.lastAgent || '未知' }}</span></template>
        </el-table-column>
        <el-table-column prop="messageCount" label="消息数" width="80" />
        <el-table-column prop="reviewStatusText" label="复核状态" width="110" />
        <el-table-column label="最近活跃" width="160">
          <template #default="{ row }">{{ fmtTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="s">
            <el-button size="small" link type="primary" @click="openDetail(s.row)">详情</el-button>
            <el-button size="small" link type="warning" @click="mark(s.row)">标记复核</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="prev, pager, next, total" :total="total" :page-size="size"
        :current-page="page" @current-change="onPage" style="margin-top:10px" />
      <div v-if="scanLimit" class="scan-note">消息级筛选仅扫描最近 {{ scanLimit }} 条会话（防全库扫描），可加条件缩小范围</div>
    </el-card>

    <el-dialog v-model="dlg" title="会话详情" width="760px">
      <template v-if="cur">
        <el-descriptions :column="2" size="small" border>
          <el-descriptions-item label="会话 ID">{{ cur.sessionId }}</el-descriptions-item>
          <el-descriptions-item label="用户">{{ cur.userId }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ cur.title || '（无标题）' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ fmtTime(cur.createTime) }}</el-descriptions-item>
        </el-descriptions>
        <div class="msg-box">
          <div v-for="(m, i) in cur.messages || []" :key="i"
            :class="['msg', m.type === 'USER' ? 'msg-u' : 'msg-a']">
            <div class="msg-role">{{ m.type === 'USER' ? '用户' : 'AI' }}
              <el-tag v-if="m.agent" size="small" type="info">{{ m.agent }}</el-tag></div>
            <div class="msg-content">{{ m.content }}</div>
            <div v-if="(m.cards || []).length" class="msg-cards">
              <el-tag v-for="(c, j) in m.cards" :key="j" size="small" type="warning" style="margin-right:6px">
                {{ c.type }}：{{ (c.payload && (c.payload.name || c.payload.orderId || c.payload.courseId)) || '' }}
              </el-tag>
            </div>
          </div>
        </div>
        <h4>复核历史</h4>
        <el-table :data="cur.reviews || []" size="small">
          <el-table-column prop="reason" label="标记原因" show-overflow-tooltip />
          <el-table-column prop="statusText" label="状态" width="110" />
          <el-table-column prop="markedByName" label="标记人" width="90" />
          <el-table-column prop="markedAt" label="标记时间" width="160" />
          <el-table-column label="操作" width="100">
            <template #default="s">
              <el-button v-if="s.row.status === 0" size="small" link type="primary"
                @click="handle(s.row)">处理</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { fmtTime } from '../utils/format'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'

const rows = ref([]); const total = ref(0); const page = ref(1); const size = 10
const loading = ref(false); const dlg = ref(false); const cur = ref(null); const scanLimit = ref(null)
const f = ref({ userId: '', range: null, agent: '', keyword: '' })
const STATUS = { 0: '待复核', 1: '已处理-无违规', 2: '已处理-确认违规', 3: '已处理-其他' }

const load = async () => {
  loading.value = true
  try {
    const params = { page: page.value, size }
    if (f.value.userId) params.userId = f.value.userId
    if (f.value.agent) params.agent = f.value.agent
    if (f.value.keyword) params.keyword = f.value.keyword
    if (f.value.range && f.value.range[0]) { params.startTime = f.value.range[0]; params.endTime = f.value.range[1] }
    const d = await request.get('/admin/supervision/sessions', { params })
    rows.value = (d.records || []).map(r => ({ ...r, reviewStatusText: r.reviewStatus == null ? '—' : STATUS[r.reviewStatus] }))
    total.value = Number(d.total)
    scanLimit.value = d.scanLimit
  } finally { loading.value = false }
}
const onPage = (p) => { page.value = p; load() }

const openDetail = async (row) => {
  const d = await request.get(`/admin/supervision/sessions/${row.sessionId}`)
  d.reviews = (d.reviews || []).map(r => ({ ...r, statusText: STATUS[r.status] }))
  cur.value = d
  dlg.value = true
}
const mark = async (row) => {
  const { value } = await ElMessageBox.prompt('请输入标记原因（必填）', '标记待复核：' + row.sessionId.slice(0, 12) + '…',
    { inputValidator: v => !!(v && v.trim()) || '原因不能为空' })
  await request.post(`/admin/supervision/sessions/${row.sessionId}/mark`, { sessionId: row.sessionId, reason: value.trim() })
  ElMessage.success('已标记待复核'); load()
}
const handle = async (row) => {
  const { value } = await ElMessageBox.prompt('处理备注', '处理复核', { inputValue: '' })
  await request.post(`/admin/supervision/sessions/reviews/${row.id}/handle`,
    { status: 2, handleNote: (value || '').trim() })
  ElMessage.success('已处理'); dlg.value = false
}
onMounted(load)
</script>

<style scoped>
.filters { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.scan-note { margin-top: 8px; font-size: 12px; color: #909399; }
.msg-box { max-height: 320px; overflow: auto; border: 1px solid #ebeef5; border-radius: 4px; padding: 8px; margin: 10px 0; }
.msg { padding: 6px 8px; border-radius: 4px; margin-bottom: 6px; }
.msg-u { background: #ecf5ff; }
.msg-a { background: #f4f4f5; }
.msg-role { font-size: 11px; color: #909399; margin-bottom: 2px; }
.msg-content { white-space: pre-wrap; word-break: break-all; font-size: 13px; }
.msg-cards { margin-top: 4px; }
</style>
