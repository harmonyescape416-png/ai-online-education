<template>
  <div>
    <h2 class="kz-page-title">签到与积分</h2>
    <el-card shadow="never">
      <div>连续签到 {{ today.streak || 0 }} 天</div>
      <div>今日积分 {{ summary.todayPoints || 0 }} · 累计 {{ summary.totalPoints || 0 }} · 本月 {{ summary.periodPoints || 0 }}</div>
      <el-button type="primary" :disabled="today.signed" :loading="signing" @click="sign">
        {{ today.signed ? '已签到' : '签到' }}
      </el-button>
      <div v-if="error" class="point-error">{{ error }} <el-button link type="primary" @click="load">重试</el-button></div>
    </el-card>
    <h3>本月签到</h3>
    <div>{{ (month.days || []).join('、') || '暂无签到' }}</div>
    <h3>积分明细</h3>
    <el-table :data="ledger" border>
      <el-table-column prop="bizDate" label="日期" width="120" />
      <el-table-column prop="source" label="来源" width="120" />
      <el-table-column prop="points" label="积分" width="80" />
      <el-table-column prop="reason" label="原因" />
    </el-table>
    <div v-if="!ledger.length" class="kz-empty">暂无积分明细</div>
    <h3>学霸排行</h3>
    <el-select v-model="period" style="width: 160px" @change="loadRank">
      <el-option label="本月" value="current" />
      <el-option v-for="item in history" :key="item" :label="item" :value="item" />
    </el-select>
    <div v-if="rank.source" class="kz-meta">{{ rank.settled ? '已结算' : '进行中' }}</div>
    <div v-if="rank.me">我的排名 {{ rank.me.ranked ? rank.me.rank : '未上榜' }} · {{ rank.me.points }} 分</div>
    <el-table :data="rank.records || []" border>
      <el-table-column prop="rank" label="排名" width="80" />
      <el-table-column prop="name" label="学员" />
      <el-table-column prop="points" label="积分" width="100" />
    </el-table>
    <div v-if="!(rank.records || []).length" class="kz-empty">暂无排行</div>
    <el-pagination
      layout="prev, pager, next"
      :total="Number(rank.total || 0)"
      :page-size="20"
      @current-change="changeRankPage"
    />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { pointApi } from '../api'

const today = ref({})
const summary = ref({})
const month = ref({})
const ledger = ref([])
const rank = ref({})
const period = ref('current')
const history = ref([])
const signing = ref(false)
const error = ref('')
const rankPage = ref(1)

async function load() {
  error.value = ''
  try {
    today.value = await pointApi.today()
    summary.value = await pointApi.summary()
    month.value = await pointApi.month()
    const page = await pointApi.ledger({ pageNum: 1, pageSize: 20 })
    ledger.value = page?.records || []
    await loadRank()
  } catch (e) {
    error.value = e?.message || '加载失败'
    if (!e?.notified) ElMessage.error(error.value)
  }
}

async function sign() {
  signing.value = true
  try {
    await pointApi.checkin()
    await load()
  } catch (e) {
    if (!e?.notified) ElMessage.error(e?.message || '签到失败')
  } finally {
    signing.value = false
  }
}

async function loadRank() {
  const data = await pointApi.ranks({ period: period.value, pageNum: rankPage.value, pageSize: 20 })
  rank.value = data || {}
  if (period.value === 'current' && data?.settled && data?.period && !history.value.includes(data.period)) {
    history.value = [data.period, ...history.value]
  }
}

function changeRankPage(page) {
  rankPage.value = page
  loadRank().catch((e) => {
    error.value = e?.message || '排行加载失败'
  })
}

onMounted(load)
</script>

<style scoped>
.point-error { margin-top: 8px; color: #e11d48; }
</style>
