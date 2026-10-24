<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <span>我的订单</span>
      </template>

      <el-form :inline="true" :model="query" @submit.prevent>
        <el-form-item label="订单状态">
          <!-- M15.14-R5 O5：状态 Tab 快捷切换（全枚举，不隐藏历史状态）；与分页/查询同步 -->
          <el-radio-group v-model="query.status" @change="reload">
            <el-radio-button :value="null">全部</el-radio-button>
            <el-radio-button :value="1">待支付</el-radio-button>
            <el-radio-button :value="2">已支付</el-radio-button>
            <el-radio-button :value="3">已关闭</el-radio-button>
            <el-radio-button :value="4">已完成</el-radio-button>
            <el-radio-button :value="5">已报名</el-radio-button>
            <el-radio-button :value="6">已申请退款</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="reload">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="records" v-loading="loading" border stripe>
        <el-table-column label="订单号" min-width="200">
          <template #default="{ row }">
            <!-- M15.14-R2 O2：19 位雪花 ID 字符串处理；中段省略+悬停完整+一键复制 -->
            <span class="order-no" :title="String(row.id)" @click="copyNo(row.id)">
              {{ maskOrderNo(row.id) }}
              <el-icon class="order-no__copy"><CopyDocument /></el-icon>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="120">
          <template #default="{ row }">
            <!-- M15.14-R5 O3/N3：0 元=免费课程订单（真实业务）；已支付 0 门=明细缺失警示 -->
            <span v-if="Number(row.totalAmountYuan) === 0" class="kz-price-free">免费课程</span>
            <span v-else class="kz-price">¥{{ row.totalAmountYuan }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="退款结果" min-width="280">
          <template #default="{ row }">
            <div v-if="latest(row)" class="refund-notices">
              <div class="refund-notice">
                <el-tag size="small" :type="latest(row).outcome === 'REJECTED' ? 'warning' : 'success'">{{ latest(row).statusText }}</el-tag>
                <span v-if="latest(row).outcome === 'REJECTED'" class="refund-notice__text">{{ latest(row).message }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="课程数" width="110">
          <template #default="{ row }">
            <span v-if="row.itemCount > 0">{{ row.itemCount }}</span>
            <el-tag v-else-if="row.status === 2" size="small" type="warning" effect="plain">明细缺失</el-tag>
            <span v-else>0</span>
          </template>
        </el-table-column>
        <el-table-column label="下单时间" width="175">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row)">详情</el-button>
            <!-- M15.14-R2 O1：仅后端规则允许的待支付订单显示取消（已支付/已关闭/已完成不渲染） -->
            <el-button
              v-if="row.status === 1"
              link
              type="primary"
              :loading="cancellingId === String(row.id)"
              @click="onCancel(row)"
            >
              取消订单
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        class="kz-pager"
        background
        layout="total, sizes, prev, pager, next"
        :total="total"
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :page-sizes="[10, 20, 50]"
        @current-change="load"
        @size-change="reload"
      />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { CopyDocument } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderApi } from '../api'
import { fmtTime, maskOrderNo, copyText } from '../utils/format'

const router = useRouter()
const loading = ref(false)
const records = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10, status: null })
const cancellingId = ref('') // M15.14-R2 O1：取消中防重复点击
const knownRefundStatus = new Map()
let pollTimer = 0

function noticesOf(row) {
  return Array.isArray(row?.refundNotices) ? row.refundNotices : []
}

function latest(row) {
  return noticesOf(row).find((notice) => notice.outcome === 'PASSED' || notice.outcome === 'REJECTED') || null
}

function terminal(status) {
  return status === 'REJECTED' || status === 'SUCCESS' || status === 'FAILED'
}

/** 只在本页打开期间，退款从处理中变为通过、驳回或失败时提示一次。历史结果只显示在行上。 */
function announceNewResults(rows) {
  const fresh = []
  for (const row of rows) {
    for (const notice of noticesOf(row)) {
      const previous = knownRefundStatus.get(notice.refundId)
      if (previous && previous !== notice.status && terminal(notice.status)) {
        fresh.push(notice)
      }
      if (notice.refundId) knownRefundStatus.set(notice.refundId, notice.status)
    }
  }
  if (!fresh.length) return
  const passed = fresh.every((notice) => notice.outcome === 'PASSED')
  ElMessage({
    type: passed ? 'success' : 'warning',
    duration: 6000,
    message: fresh.map((notice) => notice.outcome === 'REJECTED' ? `${notice.statusText} ${notice.message}`.trim() : notice.statusText).join(' ')
  })
}

async function load(options = {}) {
  if (options.silent && loading.value) return
  if (!options.silent) loading.value = true
  try {
    const data = await orderApi.list({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: query.status || undefined
    })
    records.value = data.records || []
    total.value = Number(data.total) || 0 // M15.14-R4：同上，防字符串 total 导致分页渲染为空
    announceNewResults(records.value)
  } catch (e) {
    if (!options.silent && !e?.notified) {
      ElMessage.error(e?.message || '订单列表暂时无法加载，请稍后重试')
    }
  } finally {
    if (!options.silent) loading.value = false
  }
}

function onVisible() {
  if (document.visibilityState === 'visible') load({ silent: true })
}

function reload() {
  query.pageNum = 1
  return load()
}

function resetQuery() {
  query.status = null
  query.pageNum = 1
  load()
}

async function onCancel(row) {
  const idStr = String(row.id)
  if (cancellingId.value) return // M15.14-R2 O1：防重复提交
  try {
    await ElMessageBox.confirm(`确认取消订单 ${idStr}？`, '取消订单', { type: 'warning' })
  } catch {
    return
  }
  cancellingId.value = idStr
  try {
    const msg = await orderApi.cancel(row.id)
    ElMessage.success(msg || '订单已取消')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '取消失败')
  } finally {
    cancellingId.value = ''
  }
}

/** M15.14-R2 O2：订单号一键复制（字符串，不丢精度） */
async function copyNo(no) {
  const ok = await copyText(no)
  ok ? ElMessage.success('订单号已复制') : ElMessage.warning('复制失败，请手动复制')
}

const goDetail = (row) => router.push(`/orders/${row.id}`)

const statusTag = (s) => (s === 2 || s === 4 || s === 5 ? 'success' : s === 3 ? 'info' : s === 6 ? 'warning' : undefined)
const formatTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

function hasPendingRefund(row) {
  return noticesOf(row).some((notice) => !terminal(notice.status))
}

function pollPending() {
  if (document.visibilityState !== 'visible') return
  if (records.value.some(hasPendingRefund)) load({ silent: true })
}

onMounted(() => {
  load()
  document.addEventListener('visibilitychange', onVisible)
  pollTimer = window.setInterval(pollPending, 15000)
})

onUnmounted(() => {
  document.removeEventListener('visibilitychange', onVisible)
  if (pollTimer) window.clearInterval(pollTimer)
})
</script>

<style scoped>
.refund-notices {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.refund-notice {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.refund-notice__text {
  font-size: 12px;
  line-height: 1.5;
  color: #606266;
}
.refund-notice__empty {
  color: #909399;
}
</style>
