<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>失败通知事件运维（FAILED Outbox）</span>
          <el-button size="small" :loading="loading" @click="load">查询</el-button>
        </div>
      </template>
      <el-alert type="info" :closable="false"
        title="重投保持 eventKey 不变，由 Worker 真实重新投递；通知侧唯一约束保证不重复产生通知。禁止直接改状态伪造送达。" style="margin-bottom:10px" />
      <div class="filters">
        <el-input v-model="f.keyword" placeholder="eventKey / 错误信息" size="small" style="width:240px" clearable @keyup.enter="load" />
        <el-button size="small" type="primary" @click="load">查询</el-button>
      </div>
      <el-table :data="rows" size="small" v-loading="loading">
        <el-table-column prop="id" label="ID" width="180" show-overflow-tooltip />
        <el-table-column prop="type" label="类型" width="140" />
        <el-table-column prop="eventKey" label="eventKey" width="220" show-overflow-tooltip />
        <el-table-column prop="attempt" label="尝试" width="60" />
        <el-table-column prop="error" label="最近错误" show-overflow-tooltip />
        <el-table-column label="最近更新" width="160">
          <template #default="{ row }">{{ fmtTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="s">
            <el-button size="small" link type="warning" @click="retry(s.row)">重投</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="prev, pager, next, total" :total="total" :page-size="size"
        :current-page="page" @current-change="onPage" style="margin-top:10px" />
      <div v-if="!rows.length && !loading" class="empty">当前没有 FAILED 事件</div>
    </el-card>
  </div>
</template>

<script setup>
import { fmtTime } from '../utils/format'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'

const rows = ref([]); const total = ref(0); const page = ref(1); const size = 10
const loading = ref(false)
const f = ref({ keyword: '' })

const load = async () => {
  loading.value = true
  try {
    const p = { page: page.value, size }
    if (f.value.keyword) p.keyword = f.value.keyword
    const d = await request.get('/admin/outbox', { params: p })
    rows.value = d.records || []
    total.value = Number(d.total)
  } finally { loading.value = false }
}
const onPage = (p) => { page.value = p; load() }
const retry = async (row) => {
  await ElMessageBox.confirm(
    `确认重投事件 ${row.eventKey}？Worker 将真实重新投递（结果以投递为准）。`, '重投确认', { type: 'warning' })
  await request.post(`/admin/outbox/${row.id}/retry`)
  ElMessage.success('已重新入队，Worker 将真实投递')
  load()
}
onMounted(load)
</script>

<style scoped>
.filters { display: flex; gap: 8px; margin-bottom: 10px; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.empty { text-align: center; color: #909399; padding: 16px 0; }
</style>
