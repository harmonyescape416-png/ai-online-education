<template>
  <div>
    <el-button link type="primary" @click="router.back()">← 返回课程</el-button>
    <h2 class="kz-page-title" style="margin-top: 12px">订单确认</h2>

    <el-card v-if="course" shadow="never" style="max-width: 760px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="课程名称">{{ course.name }}</el-descriptions-item>
        <el-descriptions-item label="课程形式">{{ course.courseType === 1 ? '直播' : '录播' }}</el-descriptions-item>
        <el-descriptions-item label="有效期">{{ course.validDuration || '-' }} 个月</el-descriptions-item>
        <el-descriptions-item label="课程价格">
          <span :class="course.free === 1 ? 'kz-price-free' : 'kz-price'">
            {{ course.free === 1 ? '免费' : `¥${course.priceYuan}` }}
          </span>
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="course.free !== 1" style="margin-top: 12px">
        <div style="margin-bottom: 8px">优惠券</div>
        <el-radio-group v-model="selectedCoupon">
          <el-radio value="">不使用</el-radio>
          <el-radio v-for="item in couponOptions" :key="item.userCouponId" :value="item.userCouponId" :disabled="!item.usable">
            {{ item.name }} {{ item.usable ? `优惠 ¥${item.discountYuan}` : item.reason }}
          </el-radio>
        </el-radio-group>
      </div>
      <div v-if="couponNotice" class="kz-tip">{{ couponNotice }}</div>
      <div class="kz-total">
        <span>应付金额：</span>
        <span class="kz-price">{{ payableText }}</span>
      </div>

      <!-- M15.16：提交订单 → 支付方式选择（微信/支付宝，模拟支付）→ 支付成功 -->
      <el-button type="primary" :loading="submitting" :disabled="submitting" @click="onSubmit">
        提交订单
      </el-button>
      <el-button @click="router.back()">取消</el-button>
    </el-card>
    <div v-else class="kz-empty">加载中...</div>

    <!-- 支付方式选择弹窗 -->
    <el-dialog v-model="payVisible" title="选择支付方式" width="420px" :close-on-click-modal="!paying"
               @close="onPayDialogClose">
      <div class="pay-amount">应付金额：<b class="kz-price">{{ payableText }}</b></div>
      <div class="pay-options">
        <div class="pay-option" :class="{ 'is-selected': payChannel === 'WECHAT' }" @click="payChannel = 'WECHAT'">
          <span class="pay-option__icon" style="color:#09bb07">💬</span>
          <span>模拟微信支付</span>
          <el-icon v-if="payChannel === 'WECHAT'" class="pay-option__check" color="#4F46E5"><Select /></el-icon>
        </div>
        <div class="pay-option" :class="{ 'is-selected': payChannel === 'ALIPAY' }" @click="payChannel = 'ALIPAY'">
          <span class="pay-option__icon" style="color:#1677ff">🅰️</span>
          <span>模拟支付宝</span>
          <el-icon v-if="payChannel === 'ALIPAY'" class="pay-option__check" color="#4F46E5"><Select /></el-icon>
        </div>
      </div>
      <template #footer>
        <el-button @click="payVisible = false">暂不支付</el-button>
        <el-button type="primary" :disabled="!payChannel" :loading="paying" @click="onPay">
          确认支付
        </el-button>
      </template>
    </el-dialog>

    <!-- 支付成功反馈 -->
    <el-dialog v-model="successVisible" width="400px" :show-close="false" align-center>
      <div class="pay-success">
        <el-icon class="pay-success__icon" color="#2ba471"><CircleCheckFilled /></el-icon>
        <div class="pay-success__title">{{ enrollOk ? '模拟支付已入账' : '模拟支付已入账，开课未全部完成' }}</div>
        <div v-if="enrollOk" class="pay-success__desc">课程已加入「我的学习」，状态为待学习</div>
        <div v-else class="pay-success__desc">支付已完成；开课未全部成功，可重试支付补偿</div>
        <div v-if="enrollError" class="pay-success__warn">{{ enrollError }}</div>
        <div style="margin-top: 18px; display: flex; gap: 10px; justify-content: center">
          <el-button @click="goOrders">查看订单</el-button>
          <el-button type="primary" @click="goLearning">去我的学习</el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Select, CircleCheckFilled } from '@element-plus/icons-vue'
import { courseApi, couponApi, orderApi } from '../api'

const route = useRoute()
const router = useRouter()
const course = ref(null)
const submitting = ref(false)
// M15.16：支付方式选择与模拟支付
const payVisible = ref(false)
const payChannel = ref('WECHAT')
const paying = ref(false)
const successVisible = ref(false)
const enrollError = ref('')
const enrollOk = ref(true)
const paidOrderId = ref(null)

/** 生成幂等请求号：同一次确认操作重复提交只生成一笔订单 */
function newRequestId() {
  const rnd = Math.random().toString(36).slice(2, 10)
  return `web-${Date.now()}-${rnd}`
}
const requestId = ref(newRequestId())
const couponOptions = ref([])
const selectedCoupon = ref('')
const couponNotice = ref('')
const payableText = computed(() => {
  if (course.value?.free === 1) return '免费'
  const chosen = couponOptions.value.find((item) => item.userCouponId === selectedCoupon.value && item.usable)
  return chosen ? `¥${chosen.payableYuan}` : `¥${course.value?.priceYuan ?? ''}`
})

async function onSubmit() {
  if (submitting.value) return
  submitting.value = true
  try {
    // 课程 ID 为 19 位雪花ID，必须按字符串透传，禁止 Number() 转换（会丢失精度）
    const data = await orderApi.prePlace({
      courseIds: [String(route.query.courseId)],
      requestId: requestId.value,
      userCouponId: selectedCoupon.value || undefined
    })
    paidOrderId.value = String(data.orderId)
    ElMessage.success(data.idempotentHit ? '订单已存在，请完成支付' : '订单已生成，请完成支付')
    // 打开支付方式选择（模拟支付）
    payVisible.value = true
  } catch (e) {
    ElMessage.error(e?.message || '下单失败')
  } finally {
    submitting.value = false
  }
}

async function onPay() {
  if (paying.value || !payChannel.value) return
  paying.value = true
  try {
    const result = await orderApi.pay(paidOrderId.value, { channel: payChannel.value })
    payVisible.value = false
    if (result?.paymentStatus && result.paymentStatus !== 'SUCCESS') {
      ElMessage.info(result.message || '订单仍待支付，需等待渠道通知')
      return
    }
    // M15.21：统一开课状态契约——enrollStatuses 四态；缺失关键字段不默认成功
    const sts = Array.isArray(result?.enrollStatuses) ? result.enrollStatuses : null
    if (sts === null || sts.length === 0) {
      enrollError.value = '开课状态未知，请稍后在「我的订单」查看'
      enrollOk.value = false
    } else {
      const allOk = sts.every((x) => x.status === 'SUCCESS')
      const pending = sts.filter((x) => x.status === 'PENDING' || x.status === 'RUNNING').length
      const exhausted = sts.filter((x) => x.status === 'FAILED_MANUAL').length
      enrollOk.value = allOk
      if (allOk) enrollError.value = ''
      else if (exhausted > 0) enrollError.value = `重试耗尽 ${exhausted} 门：请在「我的订单」点击「重试开课」`
      else if (pending > 0) enrollError.value = `${pending} 门等待系统重试，请稍后在「我的订单」查看`
      else enrollError.value = '开课状态异常，请稍后在「我的订单」查看'
    }
    successVisible.value = true
  } catch (e) {
    ElMessage.error(e?.message || '支付失败，请稍后重试')
  } finally {
    paying.value = false
  }
}

function onPayDialogClose() {
  // 暂不支付：可去订单详情继续支付
  if (paidOrderId.value && !successVisible.value) {
    ElMessage.info('订单已生成（待支付），可在「我的订单」中继续支付')
    router.push('/orders')
  }
}

function goOrders() {
  successVisible.value = false
  router.push('/orders')
}
function goLearning() {
  successVisible.value = false
  router.push('/learning')
}

async function loadConfirm() {
  couponNotice.value = ''
  try {
    course.value = await courseApi.detail(route.query.courseId)
    if (course.value?.free !== 1) {
      try {
        const quote = await couponApi.quote([String(route.query.courseId)])
        couponOptions.value = quote?.coupons || []
        const prefer = String(route.query.couponId || '')
        const carried = couponOptions.value.find((item) => item.userCouponId === prefer)
        selectedCoupon.value = carried?.usable ? prefer : (prefer ? '' : (quote?.recommendedCouponId || ''))
        couponNotice.value = carried && !carried.usable ? carried.reason : ''
      } catch (err) {
        couponOptions.value = []
        if (!err?.notified) ElMessage.error(err?.message || '优惠券暂时无法加载')
      }
    }
  } catch (e) {
    ElMessage.error(e?.message || '课程加载失败')
  }
}

onMounted(loadConfirm)
watch(() => [route.query.courseId, route.query.couponId], () => {
  requestId.value = newRequestId()
  loadConfirm()
})
</script>

<style scoped>
.kz-total {
  margin: 12px 0 16px;
  font-size: 15px;
}
.pay-amount { text-align: center; font-size: 15px; margin-bottom: 14px; }
.pay-options { display: flex; flex-direction: column; gap: 10px; }
.pay-option { display: flex; align-items: center; gap: 10px; padding: 14px 16px;
  border: 1.5px solid #e6e8eb; border-radius: 8px; cursor: pointer; transition: border-color .15s ease; }
.pay-option:hover { border-color: #c3bdfd; }
.pay-option.is-selected { border-color: #4F46E5; background: #f7f6ff; }
.pay-option__icon { font-size: 20px; }
.pay-option__check { margin-left: auto; }
.pay-success { text-align: center; padding: 10px 0; }
.pay-success__icon { font-size: 56px; }
.pay-success__title { font-size: 20px; font-weight: 600; margin: 10px 0 6px; }
.pay-success__desc { color: #64748b; }
.pay-success__warn { margin-top: 8px; color: #e6a23c; font-size: 13px; }
</style>
