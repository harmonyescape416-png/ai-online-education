<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>操作日志（90 天保留）</span>
          <div>
            <el-button size="small" @click="exportCsv" :loading="exporting">导出 CSV</el-button>
            <el-button size="small" :loading="loading" @click="load">查询</el-button>
          </div>
        </div>
      </template>
      <div class="filters">
        <el-input v-model="f.operatorId" placeholder="操作人 ID" size="small" style="width:130px" clearable />
        <el-select v-model="f.module" placeholder="模块" size="small" style="width:130px" clearable>
          <el-option v-for="m in ['AUTH','USER','COURSE','QA','KNOWLEDGE','PROMPT','EVAL','CONFIG','SUPERVISION','OUTBOX']"
            :key="m" :label="m" :value="m" />
        </el-select>
        <el-input v-model="f.operation" placeholder="操作类型（如 LOGIN_SUCCESS）" size="small" style="width:200px" clearable @keyup.enter="load" />
        <el-select v-model="f.result" placeholder="结果" size="small" style="width:110px" clearable>
          <el-option label="成功" value="SUCCESS" /><el-option label="失败" value="FAIL" />
        </el-select>
        <el-input v-model="f.requestId" placeholder="requestId" size="small" style="width:200px" clearable @keyup.enter="load" />
        <el-date-picker v-model="f.range" type="daterange" size="small" start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DD" />
        <el-button size="small" type="primary" @click="load">查询</el-button>
      </div>
      <el-table :data="rows" size="small" v-loading="loading">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作人" width="100" />
        <el-table-column prop="module" label="模块" width="110" />
        <el-table-column prop="operation" label="操作" width="190" show-overflow-tooltip />
        <el-table-column prop="targetType" label="对象" width="150" show-overflow-tooltip />
        <el-table-column prop="detail" label="详情" show-overflow-tooltip />
        <el-table-column label="结果" width="80">
          <template #default="s">
            <el-tag v-if="isFail(s.row)" size="small" type="danger">失败</el-tag>
            <el-tag v-else size="small" type="success">成功</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination layout="prev, pager, next, total" :total="total" :page-size="size"
        :current-page="page" @current-change="onPage" style="margin-top:10px" />
    </el-card>
  </div>
</template>

<script setup>
import { fmtTime } from '../utils/format'
import { ref, onMounted } from 'vue'
import request from '../api'

const rows = ref([]); const total = ref(0); const page = ref(1); const size = 15
const loading = ref(false); const exporting = ref(false)
const f = ref({ operatorId: '', module: '', operation: '', result: '', requestId: '', range: null })

const params = () => {
  const p = { page: page.value, size }
  for (const k of ['operatorId', 'module', 'operation', 'result', 'requestId']) {
    if (f.value[k]) p[k] = f.value[k]
  }
  if (f.value.range && f.value.range[0]) { p.startTime = f.value.range[0]; p.endTime = f.value.range[1] }
  return p
}
const isFail = (row) => !!row.detail && row.detail.includes('"result":"FAIL"')
const load = async () => {
  loading.value = true
  try {
    const d = await request.get('/admin/operation-logs', { params: params() })
    rows.value = d.records || []
    total.value = Number(d.total)
  } finally { loading.value = false }
}
const onPage = (p) => { page.value = p; load() }
const exportCsv = async () => {
  exporting.value = true
  try {
    const resp = await fetch('/api/admin/operation-logs/export?' + new URLSearchParams(
      Object.fromEntries(Object.entries(params()).filter(([k]) => k !== 'page' && k !== 'size'))),
      { headers: { Authorization: 'Bearer ' + localStorage.getItem('kzai_token') } })
    const blob = await resp.blob()
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = 'operation-logs.csv'
    a.click()
    URL.revokeObjectURL(a.href)
  } finally { exporting.value = false }
}
onMounted(load)
</script>

<style scoped>
.filters { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
