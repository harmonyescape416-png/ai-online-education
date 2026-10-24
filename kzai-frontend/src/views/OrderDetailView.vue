<template>
  <div>
    <el-button link type="primary" @click="router.back()">← 返回我的订单</el-button>
    <h2 class="kz-page-title" style="margin-top: 12px">订单详情</h2>

    <el-card v-if="detail" shadow="never" style="max-width: 900px">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="订单号">{{ order.id }}</el-descriptions-item>
        <el-descriptions-item label="订单状态">
          <el-tag :type="order.status === 1 ? 'warning' : 'success'">{{ order.statusText }}</el-tag>
          <el-button v-if="showRetryEnroll" size="small" type="warning" :loading="retrying"
                     style="margin-left: 12px" @click="onRetryEnroll">重试开课</el-button>
        </el-descriptions-item>
        <el-descriptions-item v-if="enrollStatuses.length" label="开课状态" :span="2">
          <div v-for="st in enrollStatuses" :key="st.courseId" style="font-size: 12px; line-height: 1.8">
            课程 {{ st.courseId }}：
            <el-tag size="small" :type="enrollTagType(st.status)">{{ enrollStatusText(st.status) }}</el-tag>
            <span v-if="st.attempts != null" class="enroll-gap">已尝试 {{ st.attempts }} 次</span>
            <span v-if="st.lastError" class="enroll-gap enroll-err">{{ st.lastError }}</span>
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="订单总金额">
          <span class="kz-price">¥{{ order.totalAmountYuan }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="实付金额">¥{{ order.realAmountYuan }}</el-descriptions-item>
        <el-descriptions-item label="优惠金额">¥{{ order.discountAmount / 100 }}</el-descriptions-item>
        <el-descriptions-item label="下单时间">{{ formatTime(order.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ order.message || '-' }}</el-descriptions-item>
      </el-descriptions>

      <h4 style="margin: 16px 0 8px">课程明细</h4>
      <el-table :data="order.items || []" border>
        <el-table-column prop="name" label="课程名称" min-width="200" />
        <el-table-column label="价格快照" width="140">
          <template #default="{ row }">
            <span>¥{{ row.priceYuan }}</span>
    <!-- M15.26：待支付订单继续支付（支付方式选择 → 确认支付 → 刷新详情） -->
    <el-dialog v-model="payVisible" title="选择支付方式" width="420px" :close-on-click-modal="!paying">
      <div class="pay-amount">应付金额：<b class="kz-price">¥{{ order.totalAmountYuan }}</b></div>
      <div class="pay-options">
        <div class="pay-option" :class="{ 'is-selected': payChannel === 'WECHAT' }" @click="payChannel = 'WECHAT'">
          <span class="pay-option__icon" style="color:#09bb07">💬</span>
          <span>模拟微信支付</span>
          <el-icon v-if="payChannel === 'WECHAT'" color="#4F46E5"><Select /></el-icon>
        </div>
        <div class="pay-option" :class="{ 'is-selected': payChannel === 'ALIPAY' }" @click="payChannel = 'ALIPAY'">
          <span class="pay-option__icon" style="color:#1677ff">🅰️</span>
          <span>模拟支付宝</span>
          <el-icon v-if="payChannel === 'ALIPAY'" color="#4F46E5"><Select /></el-icon>
        </div>
      </div>
      <template #footer>
        <el-button @click="payVisible = false">暂不支付</el-button>
        <el-button type="primary" :disabled="!payChannel" :loading="paying" @click="onPay">确认支付</el-button>
      </template>
    </el-dialog>
          </template>
        </el-table-column>
        <el-table-column prop="validDuration" label="有效期(月)" width="110" />
        <el-table-column label="明细状态" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="售后" width="120">
          <template #default="{ row }">
            <el-button v-if="row.status === 2" size="small" :disabled="Number(row.refundStatus) === 2" @click="onRefund(row)">申请退款</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div style="margin-top: 16px">
        <!-- M15.14-R2 O1：仅待支付订单可取消（已支付/已关闭不渲染取消入口） -->
        <el-button v-if="order.status === 1" type="primary" :loading="paying" @click="payVisible = true">去支付</el-button>
            <el-button v-if="order.status === 1" type="danger" @click="onCancel">取消订单</el-button>
        <el-button v-else type="primary" @click="router.push('/orders')">返回订单列表</el-button>
      </div>
    </el-card>
    <div v-else class="kz-empty">加载中...</div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Select } from '@element-plus/icons-vue'
import { orderApi } from '../api'

const route = useRoute()
const router = useRouter()
const detail = ref(null)
// M15.22-一：统一响应契约 {order, enrollStatuses, enrollAllSuccess}
const order = computed(() => detail.value?.order || detail.value || null)

async function load() {
  try {
    detail.value = await orderApi.detail(route.params.id)
  } catch (e) {
    ElMessage.error(e?.message || '订单加载失败')
  }
}

// M15.26：待支付订单继续支付（支付方式选择 → 确认支付 → 四态反馈 → 刷新详情）
const payVisible = ref(false)
const payChannel = ref('')
const paying = ref(false)
// M15.26：打开支付弹窗前清残留焦点（防焦点轮廓残留/闪现——覆盖加载/跳转/成功失败各态）
function onOpenPay() {
  try { document.activeElement && document.activeElement.blur && document.activeElement.blur() } catch (e) { /* 忽略 */ }
  payVisible.value = true
}
async function onPay() {
  if (paying.value || !payChannel.value) return
  paying.value = true
  try {
    const result = await orderApi.pay(order.value.id, { channel: payChannel.value })
    payVisible.value = false
    if (result?.paymentStatus && result.paymentStatus !== 'SUCCESS') {
      ElMessage.info(result.message || '订单仍待支付，需等待渠道通知')
      await load()
      return
    }
    const sts = Array.isArray(result?.enrollStatuses) ? result.enrollStatuses : null
    if (sts === null || sts.length === 0) {
      ElMessage.warning('支付成功，开课状态待核实，请稍后刷新查看')
    } else if (sts.every((x) => x.status === 'SUCCESS')) {
      ElMessage.success('模拟支付已入账，课程已加入「我的学习」')
    } else if (sts.some((x) => x.status === 'FAILED_MANUAL')) {
      ElMessage.warning('支付成功；开课失败可点击「重试开课」')
    } else {
      ElMessage.info('支付成功；开课处理中，请稍后刷新查看')
    }
    payChannel.value = ''
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '支付失败，请稍后重试')
  } finally {
    paying.value = false
  }
}

async function onRefund(row) {
  if (Number(row.refundStatus) === 2) return
  try {
    const { value } = await ElMessageBox.prompt('退款按该课程实付金额申请', '申请退款', {
      inputPlaceholder: '原因',
      inputValidator: (v) => !!(v && v.trim()) || '请填写原因'
    })
    await orderApi.applyRefund(order.value.id, row.id, { reason: value.trim(), requestId: `${row.id}-${Date.now()}` })
    ElMessage.success('退款申请已提交')
    await load()
  } catch (e) {
    if (e === 'cancel' || e?.message === 'cancel') return
    const raw = e?.message || ''
    const message = !raw || raw.includes('服务异常') || raw.startsWith('Request failed with status code')
      ? '退款申请暂时无法提交，请稍后重试'
      : raw
    if (!e?.notified) {
      ElMessage.error(message)
    }
  }
}

async function onCancel() {
  try {
    await ElMessageBox.confirm('确认取消该订单？', '取消订单', { type: 'warning' })
  } catch {
    return
  }
  try {
    const msg = await orderApi.cancel(order.value.id)
    ElMessage.success(msg || '订单已取消')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '取消失败')
  }
}


// M15.20-C：重试开课（已支付订单开课失败补偿；enroll_compensation 状态判定）
// M15.21：结构化开课状态判定（不靠备注文本）——存在非 SUCCESS 状态即显示重试
const showRetryEnroll = computed(() => {
  if (String(order.value?.status) !== '2') return false
  const sts = detail.value?.enrollStatuses
  // 缺失开课记录显示「待核实」——不默认成功（按钮显示以提示核实）
  if (!Array.isArray(sts)) return true
  return sts.some((x) => x.status !== 'SUCCESS')
})
const enrollStatuses = computed(() => detail.value?.enrollStatuses || [])
const enrollStatusText = (st) => ({ PENDING: '等待重试', RUNNING: '处理中', SUCCESS: '已开课', FAILED_MANUAL: '重试耗尽' }[st] || '待核实')
const enrollTagType = (st) => ({ PENDING: 'warning', RUNNING: 'info', SUCCESS: 'success', FAILED_MANUAL: 'danger' }[st] || 'info')
const retrying = ref(false)
async function onRetryEnroll() {
  if (retrying.value) return
  retrying.value = true
  try {
    const d = await orderApi.retryEnroll(order.value.id)
    if (d?.allSuccess) {
      ElMessage.success(d.message || '开课成功')
      await load()
    } else {
      ElMessage.warning(d?.message || '部分课程仍未开课，系统将继续自动重试')
    }
  } catch (e) {
    ElMessage.error(e?.message || '重试失败')
  } finally {
    retrying.value = false
  }
}

const formatTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '-')

onMounted(load)
</script>

<style scoped>
.kz-tip {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
}
.enroll-gap { margin-left: 8px; color: #98a2b3; }
.enroll-err { color: #d03050; }
.pay-amount { text-align: center; margin-bottom: 14px; font-size: 15px; }
.pay-options { display: flex; flex-direction: column; gap: 10px; }
.pay-option { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border: 1px solid var(--el-border-color);
  border-radius: 8px; cursor: pointer; transition: border-color .15s; font-size: 14px; }
.pay-option:hover { border-color: var(--el-color-primary); }
.pay-option.is-selected { border-color: #4F46E5; background: rgba(79,70,229,.04); }
.pay-option__check { margin-left: auto; }
</style>
