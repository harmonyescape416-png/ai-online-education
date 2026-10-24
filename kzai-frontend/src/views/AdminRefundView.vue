<template>
  <div>
    <h2 class="kz-page-title">退款审核</h2>
    <el-button :loading="loading" @click="load">刷新</el-button>
    <el-alert v-if="loadError" type="warning" :closable="false" show-icon style="margin-top: 12px" :title="loadError">
      <el-button link type="primary" @click="load">重试</el-button>
    </el-alert>
    <el-table v-if="rows.length" v-loading="loading" :data="rows" border style="margin-top: 12px">
      <el-table-column prop="refundNo" label="退款单" min-width="180" />
      <el-table-column prop="orderId" label="订单" width="180" />
      <el-table-column prop="detailId" label="明细" width="180" />
      <el-table-column label="金额(分)" width="100" prop="amount" />
      <el-table-column prop="reason" label="原因" min-width="140" />
      <el-table-column prop="statusText" label="状态" width="100" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button v-if="row.status === 'APPLYING'" size="small" type="primary" @click="approve(row)">批准</el-button>
          <el-button v-if="row.status === 'APPLYING'" size="small" @click="reject(row)">驳回</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !loadError && !rows.length" description="没有退款申请" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { refundAdminApi } from '../api'

const rows = ref([])
const loading = ref(false)
const loadError = ref('')

function friendly(error, fallback) {
  const raw = error?.message || ''
  if (!raw || raw.includes('服务异常') || raw.startsWith('Request failed with status code')) {
    return fallback
  }
  return raw
}

function tell(error, fallback) {
  if (error?.notified) return
  ElMessage.error(friendly(error, fallback))
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const data = await refundAdminApi.list()
    rows.value = Array.isArray(data) ? data : []
  } catch (e) {
    loadError.value = friendly(e, '退款列表暂时无法加载，请稍后重试')
    tell(e, '退款列表暂时无法加载，请稍后重试')
  } finally {
    loading.value = false
  }
}

async function approve(row) {
  try {
    const result = await refundAdminApi.approve(row.id)
    ElMessage.success(result?.statusText ? `已处理：${result.statusText}` : '已处理')
    await load()
  } catch (e) {
    tell(e, '批准暂时无法提交，请稍后重试')
  }
}

async function reject(row) {
  try {
    const { value } = await ElMessageBox.prompt('驳回原因', '驳回退款', {
      inputPlaceholder: '原因',
      inputValidator: (v) => !!(v && v.trim()) || '请填写驳回原因'
    })
    const result = await refundAdminApi.reject(row.id, value.trim())
    ElMessage.success(result?.statusText ? `已处理：${result.statusText}` : '已驳回')
    await load()
  } catch (e) {
    if (e === 'cancel' || e?.message === 'cancel') return
    tell(e, '驳回暂时无法提交，请稍后重试')
  }
}

onMounted(load)
</script>
