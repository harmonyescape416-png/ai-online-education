<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span style="white-space: nowrap">运营数据看板</span>
          <div class="head-right">
            <el-radio-group v-model="days" size="small" @change="load">
              <el-radio-button :value="1">今日</el-radio-button>
              <el-radio-button :value="7">最近 7 天</el-radio-button>
              <el-radio-button :value="30">最近 30 天</el-radio-button>
            </el-radio-group>
            <el-button size="small" :loading="loading" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert v-if="loadError" type="error" :title="loadError" :closable="false" show-icon />
      <el-row :gutter="12" v-if="d">
        <el-col :span="4" :xs="12"><div class="kpi"><div class="kpi-v">{{ fmt(d.userCount) }}</div><div class="kpi-t">用户数</div></div></el-col>
        <el-col :span="4" :xs="12"><div class="kpi"><div class="kpi-v">{{ fmt(d.courseCount) }}</div><div class="kpi-t">课程数</div></div></el-col>
        <el-col :span="4" :xs="12"><div class="kpi"><div class="kpi-v">{{ fmt(d.todaySessions) }}</div><div class="kpi-t">今日 AI 会话数</div></div></el-col>
        <el-col :span="4" :xs="12"><div class="kpi"><div class="kpi-v">{{ fmt(d.todayQuestions) }}</div><div class="kpi-t">今日提问数</div></div></el-col>
        <el-col :span="4" :xs="12"><div class="kpi"><div class="kpi-v">{{ fmt(d.pendingQuestions) }}</div><div class="kpi-t">待处理问答数</div></div></el-col>
        <el-col :span="4" :xs="12">
          <div class="kpi">
            <div class="kpi-v">{{ d.modelSuccessRate == null ? '—' : d.modelSuccessRate + '%' }}</div>
            <div class="kpi-t">模型调用成功率（REAL）</div>
            <div class="kpi-sub" v-if="!d.modelCallTotal">暂无真实模型数据</div>
            <div class="kpi-sub" v-else>{{ d.modelCallSuccess }}/{{ d.modelCallTotal }} 轮</div>
          </div>
        </el-col>
      </el-row>

      <el-alert v-if="d && d.modelDataNote" type="info" :title="d.modelDataNote" :closable="false" style="margin-top:10px" />
      <el-alert v-if="d && (d.aigcAvailable === false || d.learningAvailable === false)"
        type="warning" style="margin-top:10px" :closable="false"
        :title="(d.aigcAvailable === false ? 'AI 服务统计不可用；' : '') + (d.learningAvailable === false ? '学习服务统计不可用' : '')" />
      <div class="upd" v-if="d">数据更新时间：{{ d.generatedAt }}</div>
    </el-card>

    <el-row :gutter="12" style="margin-top:12px" v-if="d">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><span>会话增长趋势（近 {{ days }} 天）</span></template>
          <el-table :data="d.sessionTrend || []" size="small" max-height="320">
            <el-table-column prop="date" label="日期" />
            <el-table-column prop="count" label="新增会话" />
          </el-table>
          <div v-if="!(d.sessionTrend || []).length" class="empty">暂无有效数据</div>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card shadow="never">
          <template #header><span>提问量趋势（近 {{ days }} 天）</span></template>
          <el-table :data="d.questionTrend || []" size="small" max-height="320">
            <el-table-column prop="date" label="日期" />
            <el-table-column prop="count" label="提问数" />
          </el-table>
          <div v-if="!(d.questionTrend || []).length" class="empty">暂无有效数据</div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api'

const days = ref(1)
const d = ref(null)
const loading = ref(false)
const loadError = ref('')

const load = async () => {
  loading.value = true
  loadError.value = ''
  try {
    d.value = await request.get('/admin/dashboard/overview', { params: { days: days.value } })
  } catch (error) {
    loadError.value = error?.message || '运营看板加载失败，请重试'
  } finally {
    loading.value = false
  }
}
const fmt = (v) => (v === null || v === undefined || typeof v === 'object') ? '—' : v
onMounted(load)
</script>

<style scoped>
.kpi { text-align: center; padding: 10px 0; }
.kpi-v { font-size: 26px; font-weight: 700; color: #303133; }
.kpi-t { font-size: 12px; color: #909399; margin-top: 4px; }
.kpi-sub { font-size: 11px; color: #c0c4cc; }
.card-head { display: flex; justify-content: space-between; align-items: center; }
.head-right { display: flex; gap: 8px; align-items: center; }
.upd { margin-top: 10px; font-size: 12px; color: #909399; }
.empty { text-align: center; color: #909399; padding: 20px 0; }
</style>

/* M15.47：看板标题窄屏不折行 */
@media (max-width: 768px) {
  .kpi { padding: 8px 6px; }
  .kpi-v { font-size: 20px; }
  .kpi-t { font-size: 12px; }
}

