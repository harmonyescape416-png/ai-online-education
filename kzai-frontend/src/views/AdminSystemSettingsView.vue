<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>系统参数（Nacos 热更新）</span>
          <div>
            <el-button size="small" @click="loadPublishes">发布历史</el-button>
            <el-button size="small" type="primary" :loading="publishing" @click="publish">发布配置</el-button>
          </div>
        </div>
      </template>
      <el-table :data="rows" size="small" v-loading="loading">
        <el-table-column prop="paramName" label="参数" width="170" />
        <el-table-column prop="paramKey" label="键" width="190" show-overflow-tooltip />
        <el-table-column prop="description" label="说明" show-overflow-tooltip />
        <el-table-column label="生效值" width="110">
          <template #default="s"><b>{{ s.row.effectiveValue }}</b></template>
        </el-table-column>
        <el-table-column label="草稿" width="150">
          <template #default="s">
            <el-input v-model="s.row.draftEdit" size="small" :placeholder="'(' + s.row.minValue + ' ~ ' + s.row.maxValue + ')'" />
          </template>
        </el-table-column>
        <el-table-column prop="runtimeValue" label="运行时值" width="100" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="s">
            <el-button size="small" link type="primary" @click="saveDraft(s.row)">保存草稿</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="histDlg" title="发布历史" width="720px">
      <el-table :data="publishes" size="small">
        <el-table-column prop="publishVersion" label="版本" width="70" />
        <el-table-column prop="publishStatusText" label="发布结果" width="110" />
        <el-table-column prop="runtimeVerifiedText" label="运行时生效" width="130" />
        <el-table-column prop="paramKeys" label="参数" show-overflow-tooltip />
        <el-table-column prop="error" label="失败原因" show-overflow-tooltip />
        <el-table-column prop="publishedByName" label="发布人" width="90" />
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { fmtTime } from '../utils/format'
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'

const rows = ref([]); const loading = ref(false); const publishing = ref(false)
const publishes = ref([]); const histDlg = ref(false)

const load = async () => {
  loading.value = true
  try {
    const d = await request.get('/admin/system-settings')
    rows.value = (d || []).map(r => ({ ...r, draftEdit: r.draftValue || '' }))
  } finally { loading.value = false }
}
const saveDraft = async (row) => {
  await request.post('/admin/system-settings/draft', {
    id: row.id, draftValue: row.draftEdit, expectedVersion: row.version
  })
  ElMessage.success('草稿已保存（不影响运行）')
  load()
}
const publish = async () => {
  await ElMessageBox.confirm('确认发布全部草稿到 Nacos 并热生效？', '发布配置', { type: 'warning' })
  publishing.value = true
  try {
    const d = await request.post('/admin/system-settings/publish')
    ElMessage.success('发布完成（运行时生效验证：' + (d.runtimeVerified ? '已确认' : '待跨实例轮询') + '）')
    load()
  } finally { publishing.value = false }
}
const loadPublishes = async () => {
  publishes.value = (await request.get('/admin/system-settings/publishes', { params: { page: 1, size: 20 } }) || [])
    .map(p => ({
      ...p,
      publishStatusText: { 0: '发布中', 1: '成功', 2: '失败已回退' }[p.publishStatus],
      runtimeVerifiedText: { 0: '未验证', 1: '已验证生效', 2: '验证失败已回退' }[p.runtimeVerified]
    }))
  histDlg.value = true
}
onMounted(load)
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; }
</style>
