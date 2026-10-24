<template>
  <div class="eval-admin">
    <div class="eval-head">
      <h2>AI 批量评测</h2>
      <el-alert v-if="isMockRun" type="warning" :closable="false" show-icon
                title="MOCK 协议验证，非真实 AI 质量评测"
                style="flex:1;margin-left:16px" />
      <el-alert v-if="!realAvailable" type="info" :closable="false" show-icon
                title="REAL 模式未配置（缺少 DASHSCOPE_API_KEY），真实模型评测不可执行"
                style="flex:1;margin-left:16px" />
    </div>

    <el-tabs v-model="tab">
      <!-- ============ 用例管理 ============ -->
      <el-tab-pane label="评测用例" name="cases">
        <div class="toolbar">
          <el-select v-model="caseFilter" placeholder="全部分类" size="small" clearable style="width:150px"
                     @change="loadCases">
            <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
          <el-input v-model="caseKeyword" placeholder="搜索问题" size="small" clearable style="width:200px"
                    @keydown.enter="loadCases" @clear="loadCases" />
          <el-button size="small" @click="loadCases">查询</el-button>
          <span class="spacer" />
          <el-button size="small" type="primary" @click="openEdit(null)">新增用例</el-button>
          <el-upload :show-file-list="false" :before-upload="importCsv" accept=".csv" >
            <el-button size="small">CSV 导入</el-button>
          </el-upload>
          <el-button size="small" :disabled="!selected.length" type="success"
                     @click="startRun">批量评测（已选 {{ selected.length }}）</el-button>
          <el-button size="small" type="success" plain @click="startRunAll">全量评测（{{ total }}）</el-button>
        </div>

        <el-table :data="cases" v-loading="casesLoading" size="small" border
                  @selection-change="s => selected = s">
          <el-table-column type="selection" width="40" />
          <el-table-column prop="question" label="问题" min-width="220" show-overflow-tooltip />
          <el-table-column prop="category" label="分类" width="120">
            <template #default="{ row }"><el-tag size="small">{{ row.category }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="expectedIntent" label="期望意图" width="110" />
          <el-table-column prop="expectedDocId" label="期望文档/课程" width="120" show-overflow-tooltip />
          <el-table-column label="标注" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.shouldRefuse === 1" size="small" type="danger" effect="plain">应拒答</el-tag>
              <el-tag v-if="row.needsCitation === 1" size="small" type="primary" effect="plain">需引用</el-tag>
              <el-tag v-if="row.isRegression === 1" size="small" type="success" effect="plain">回归集</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="标注理由" min-width="180" show-overflow-tooltip />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button size="small" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" type="danger" plain @click="delCase(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination layout="prev, pager, next, total" :total="total" :page-size="pageSize"
                       v-model:current-page="page" @current-change="loadCases" style="margin-top:10px" />
      </el-tab-pane>

      <!-- ============ 批次历史 ============ -->
      <el-tab-pane label="批次历史" name="runs">
        <el-table :data="runs" v-loading="runsLoading" size="small" border>
          <el-table-column prop="id" label="批次" width="180" show-overflow-tooltip />
          <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
          <el-table-column label="模式" width="90">
            <template #default="{ row }">
              <el-tag :type="row.execMode === 'REAL' ? 'success' : 'warning'" size="small">{{ row.execMode }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="runStatusType(row.status)">{{ runStatusText(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="modelCode" label="模型" width="100" />
          <el-table-column prop="embeddingProvider" label="Embedding" width="100" />
          <el-table-column prop="kbPhysicalIndex" label="知识库版本" width="170" show-overflow-tooltip />
          <el-table-column label="结果（通过/失败/执行失败/跳过）" width="200">
            <template #default="{ row }">{{ row.successCount }} / {{ row.failedCount }} / {{ row.errorCount }} / {{ row.skippedCount }}（共 {{ row.totalCases }}）</template>
          </el-table-column>
          <el-table-column label="执行时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain @click="openRun(row)">查看</el-button>
              <el-button v-if="row.status === 0" size="small" @click="poll(row.id)">刷新</el-button>
              <el-button v-if="[1,2,3,4].includes(row.status)" size="small" @click="retry(row.id)">重试失败</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ 批次详情（指标看板） ============ -->
      <el-tab-pane label="批次看板" name="board" :disabled="!currentRun">
        <template v-if="currentRun">
          <div class="board-head">
            <el-descriptions :column="4" size="small" border>
              <el-descriptions-item label="批次">{{ currentRun.name }}</el-descriptions-item>
              <el-descriptions-item label="模式">
                <el-tag :type="currentRun.execMode === 'REAL' ? 'success' : 'warning'" size="small">
                  {{ currentRun.execMode }}
                </el-tag>
                <el-tag v-if="isMockRun" size="small" type="danger" effect="dark">MOCK 协议验证，非真实 AI 质量评测</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="模型">{{ currentRun.modelCode }}（{{ currentRun.provider }}）</el-descriptions-item>
              <el-descriptions-item label="知识库版本">{{ currentRun.kbPhysicalIndex || '-' }}</el-descriptions-item>
              <el-descriptions-item label="Prompt 快照" :span="2">
                <span class="mono small">{{ currentRun.promptSnapshot || '-' }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="执行时间">{{ fmtTime(currentRun.createTime) }}</el-descriptions-item>
              <el-descriptions-item label="进度">
                {{ progress.doneCases }} / {{ currentRun.totalCases }}
                <span v-if="currentRun.status === 0">（运行中…）</span>
              </el-descriptions-item>
            </el-descriptions>
            <div class="board-actions">
              <el-button size="small" @click="exportCsv">导出 CSV</el-button>
            </div>
          </div>

          <el-alert v-if="emptyData" type="info" :closable="false" title="尚无有效数据" style="margin-top:10px" />

          <template v-if="!emptyData">
            <div class="metric-row">
              <el-card shadow="never" class="metric">
                <div class="metric-label">意图准确率（{{ summary.intentAccuracy?.numerator }}/{{ summary.intentAccuracy?.denominator }}）</div>
                <div class="metric-value">{{ fmt(summary.intentAccuracy?.accuracy) }}%</div>
                <div class="metric-note">执行失败计为未正确分类；执行成功率 {{ fmt(summary.intentAccuracy?.execSuccessRate) }}%</div>
              </el-card>
              <el-card shadow="never" class="metric">
                <div class="metric-label">RAG Hit@6（单相关文档口径）</div>
                <div class="metric-value">{{ fmt(summary.rag?.hitAt6) }}%</div>
                <div class="metric-note">{{ summary.rag?.hitAt6Numerator }}/{{ summary.rag?.hitAt6Denominator }} · 空召回 {{ summary.rag?.emptyRecallCount }}</div>
              </el-card>
              <el-card shadow="never" class="metric">
                <div class="metric-label">正确拒答率</div>
                <div class="metric-value">{{ fmt(summary.refusal?.correctRefusalRate) }}%</div>
                <div class="metric-note">错误拒答 {{ summary.refusal?.falseRefusalCount }} · 无证据仍作答 {{ summary.refusal?.unansweredEvidenceCount }} · 疑似泄露 {{ summary.refusal?.leakSuspectedCount }}（待人工复核）</div>
              </el-card>
              <el-card shadow="never" class="metric">
                <div class="metric-label">端到端耗时 P50 / P95</div>
                <div class="metric-value">{{ summary.latency?.e2e?.p50 ?? '-' }} / {{ summary.latency?.e2e?.p95 ?? '-' }} ms</div>
                <div class="metric-note">均值 {{ summary.latency?.e2e?.avg ?? '-' }} ms · {{ summary.latency?.e2e?.samples }} 样本</div>
              </el-card>
              <el-card shadow="never" class="metric">
                <div class="metric-label">实际 Token 用量</div>
                <div class="metric-value">{{ summary.token?.totalTokensSum ?? 'null' }}</div>
                <div class="metric-note">{{ summary.token?.note || '（provider 真实 usage）' }}</div>
              </el-card>
            </div>

            <el-row :gutter="12" style="margin-top:12px">
              <el-col :span="12">
                <el-card shadow="never">
                  <template #header>按意图准确率</template>
                  <el-table :data="summary.byIntent" size="small" border>
                    <el-table-column prop="expectedIntent" label="期望意图" />
                    <el-table-column prop="samples" label="样本" width="70" />
                    <el-table-column prop="correct" label="正确" width="70" />
                    <el-table-column label="准确率" width="90">
                      <template #default="{ row }">{{ fmt(row.accuracy) }}%</template>
                    </el-table-column>
                  </el-table>
                </el-card>
              </el-col>
              <el-col :span="12">
                <el-card shadow="never">
                  <template #header>混淆矩阵（行=期望 / 列=实际）</template>
                  <el-table :data="summary.confusionMatrix" size="small" border>
                    <el-table-column prop="expected" label="期望＼实际" width="120" />
                    <el-table-column label="RECOMMEND" prop="RECOMMEND" width="105" />
                    <el-table-column label="CONSULT" prop="CONSULT" width="85" />
                    <el-table-column label="BUY" prop="BUY" width="70" />
                    <el-table-column label="KNOWLEDGE" prop="KNOWLEDGE" width="100" />
                    <el-table-column label="OTHER" prop="OTHER" width="80" />
                  </el-table>
                </el-card>
              </el-col>
            </el-row>

            <el-card shadow="never" style="margin-top:12px">
              <template #header>
                失败用例详情（{{ failedRows.length }} 条；执行失败与模型判断错误分开统计）
                <el-button size="small" style="float:right" @click="loadResults">刷新</el-button>
              </template>
              <el-table :data="failedRows" size="small" border max-height="360">
                <el-table-column prop="category" label="分类" width="110" />
                <el-table-column label="期望→实际" width="170">
                  <template #default="{ row }">{{ row.expectedIntent || '-' }} → {{ row.actualIntent || '-' }}</template>
                </el-table-column>
                <el-table-column label="状态" width="90">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.status === 1 ? 'info' : 'danger'">{{ resultStatusText(row.status) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column prop="failReason" label="失败原因（判定依据）" min-width="260" show-overflow-tooltip />
                <el-table-column prop="error" label="执行错误" min-width="140" show-overflow-tooltip />
                <el-table-column prop="tokenSource" label="token 来源" width="100" />
                <el-table-column label="操作" width="130">
                  <template #default="{ row }">
                    <el-button size="small" @click="detail(row)">详情</el-button>
                    <el-button size="small" type="success" plain @click="toRegression(row)">转回归</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
          </template>
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- 用例编辑 -->
    <el-dialog v-model="editVisible" :title="editForm.id ? '编辑用例' : '新增用例'" width="640px">
      <el-form :model="editForm" label-width="110px" size="small">
        <el-form-item label="问题" required>
          <el-input v-model="editForm.question" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="分类" required>
          <el-select v-model="editForm.category">
            <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="期望意图">
          <el-select v-model="editForm.expectedIntent" clearable>
            <el-option v-for="i in INTENTS" :key="i" :label="i" :value="i" />
          </el-select>
        </el-form-item>
        <el-form-item label="期望文档/课程">
          <el-input v-model="editForm.expectedDocId" placeholder="真实文档 ID 或课程 ID（无则留空）" />
        </el-form-item>
        <el-form-item label="应拒答"><el-switch v-model="editForm.shouldRefuse" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="需引用来源"><el-switch v-model="editForm.needsCitation" :active-value="1" :inactive-value="0" /></el-form-item>
        <el-form-item label="期望工具"><el-input v-model="editForm.expectTool" placeholder="如 searchKnowledge / createPreOrder（可空）" /></el-form-item>
        <el-form-item label="拒答原因"><el-input v-model="editForm.refuseReason" /></el-form-item>
        <el-form-item label="标注理由" required><el-input v-model="editForm.note" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="数据依赖"><el-input v-model="editForm.dataDeps" placeholder="如 doc:xxx / course:1001 / admin_test_access" /></el-form-item>
        <el-form-item label="标注来源"><el-input v-model="editForm.source" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveCase">保存</el-button>
      </template>
    </el-dialog>

    <!-- 结果详情 -->
    <el-dialog v-model="detailVisible" title="结果详情" width="680px">
      <el-descriptions :column="2" size="small" border v-if="detailRow">
        <el-descriptions-item label="用例">{{ detailRow.caseId }}</el-descriptions-item>
        <el-descriptions-item label="attempt">{{ detailRow.attempt }}</el-descriptions-item>
        <el-descriptions-item label="实际意图">{{ detailRow.actualIntent || '-' }}（路由 {{ detailRow.routeLatencyMs ?? '-' }}ms）</el-descriptions-item>
        <el-descriptions-item label="RAG">Hit={{ detailRow.ragHit ?? '-' }} 空召回={{ detailRow.ragEmptyRecall ?? '-' }}（{{ detailRow.ragLatencyMs ?? '-' }}ms）</el-descriptions-item>
        <el-descriptions-item label="召回文档" :span="2"><span class="mono small">{{ detailRow.actualDocIds || '-' }}</span></el-descriptions-item>
        <el-descriptions-item label="拒答">{{ detailRow.refusal }}（{{ detailRow.refusalRule || '-' }}）</el-descriptions-item>
        <el-descriptions-item label="引用">{{ detailRow.hasCitation }} · 泄露疑似 {{ detailRow.leakSuspected }}</el-descriptions-item>
        <el-descriptions-item label="Token">{{ detailRow.totalTokens ?? 'null' }}（{{ detailRow.tokenSource }}）</el-descriptions-item>
        <el-descriptions-item label="端到端">{{ detailRow.latencyMs }}ms</el-descriptions-item>
        <el-descriptions-item label="判定">{{ detailRow.passed === 1 ? '通过' : '失败' }} · {{ detailRow.verdictRule }}</el-descriptions-item>
        <el-descriptions-item label="失败原因" :span="2">{{ detailRow.failReason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="回答全文" :span="2"><div class="mono small">{{ detailRow.answer || '-' }}</div></el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup>
import { fmtTime } from '../utils/format'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '../api'

const CATEGORIES = ['RECOMMEND', 'CONSULT', 'BUY', 'KNOWLEDGE', 'GREETING', 'OUT_OF_SCOPE']
const INTENTS = ['RECOMMEND', 'CONSULT', 'BUY', 'KNOWLEDGE', 'GREETING', 'OUT_OF_SCOPE', 'OTHER']

const tab = ref('cases')
const cases = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const caseFilter = ref('')
const caseKeyword = ref('')
const casesLoading = ref(false)
const selected = ref([])

const runs = ref([])
const runsLoading = ref(false)

const currentRun = ref(null)
const summary = ref({})
const progress = ref({ doneCases: 0 })
const resultRows = ref([])
const realAvailable = ref(true)
const pollTimer = ref(null)

const editVisible = ref(false)
const editForm = ref({})
const detailVisible = ref(false)
const detailRow = ref(null)

const isMockRun = computed(() => currentRun.value?.execMode === 'MOCK')
const emptyData = computed(() => summary.value?.emptySampleNotice != null)
const failedRows = computed(() => resultRows.value.filter(r => r.passed !== 1 || r.status !== 1))

const fmt = v => (v == null ? '-' : v)

async function loadCases() {
  casesLoading.value = true
  try {
    const d = await request.get('/eval/cases', {
      params: { page: page.value, size: pageSize.value, category: caseFilter.value || undefined,
                keyword: caseKeyword.value || undefined }
    })
    cases.value = d.records
    total.value = Number(d.total)
  } finally {
    casesLoading.value = false
  }
}

function openEdit(row) {
  editForm.value = row ? { ...row } : {
    question: '', category: 'CONSULT', expectedIntent: null, expectedDocId: '',
    shouldRefuse: 0, needsCitation: 0, expectTool: '', refuseReason: '', note: '',
    dataDeps: '', source: '管理员手工标注', isRegression: 0
  }
  editVisible.value = true
}

async function saveCase() {
  const f = editForm.value
  if (!f.question || !f.question.trim()) return ElMessage.error('问题不能为空')
  if (!f.note || !f.note.trim()) return ElMessage.error('请填写标注理由')
  const url = f.id ? `/eval/cases/${f.id}` : '/eval/cases'
  const method = f.id ? 'put' : 'post'
  await request[method](url, f)
  ElMessage.success('已保存')
  editVisible.value = false
  loadCases()
}

async function delCase(row) {
  await ElMessageBox.confirm('确认删除该用例？', '提示', { type: 'warning' })
  await request.delete(`/eval/cases/${row.id}`)
  ElMessage.success('已删除')
  loadCases()
}

async function importCsv(file) {
  const fd = new FormData()
  fd.append('file', file)
  try {
    const d0 = await request.post('/eval/cases/import', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    const d = d0
    ElMessage.alert(`导入 ${d.imported} 条；重复跳过 ${d.skipped} 条；错误 ${d.errors.length} 条`
      + (d.errors.length ? '\n' + d.errors.join('\n') : ''), 'CSV 导入结果')
    loadCases()
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '导入失败')
  }
  return false
}

async function loadRuns() {
  runsLoading.value = true
  try {
    const d = await request.get('/eval/runs', { params: { page: 1, size: 20 } })
    runs.value = d.records
  } finally {
    runsLoading.value = false
  }
}

async function startRun() {
  if (!selected.value.length) return
  await doRun(selected.value.map(r => r.id))
}

async function startRunAll() {
  await doRun(null)
}

async function doRun(caseIds) {
  const body = { name: `评测批次 ${new Date().toLocaleString()}`, execMode: 'MOCK' }
  if (caseIds) body.caseIds = caseIds
  try {
    const run = await request.post('/eval/run', body)
    if (run.status === 5) {
      ElMessage.error(run.error || 'REAL 模式不可执行')
      return
    }
    ElMessage.success('批次已启动')
    tab.value = 'runs'
    loadRuns()
    openRunById(run.id)
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '启动失败')
  }
}

async function openRun(row) {
  openRunById(row.id)
}

async function openRunById(id) {
  tab.value = 'board'
  const run = await request.get(`/eval/runs/${id}`)
  currentRun.value = run
  progress.value = { doneCases: run.doneCases }
  realAvailable.value = run.realAvailable !== false
  await loadBoard(id)
  stopPoll()
  if (currentRun.value.status === 0) {
    pollTimer.value = setInterval(() => poll(id), 2500)
  }
}

async function poll(id) {
  const run = await request.get(`/eval/runs/${id}`)
  currentRun.value = run
  progress.value = { doneCases: run.doneCases }
  await loadBoard(id)
  if (run.status !== 0) stopPoll()
}

function stopPoll() {
  if (pollTimer.value) { clearInterval(pollTimer.value); pollTimer.value = null }
}

async function loadBoard(id) {
  const rid = id || currentRun.value?.id
  const [s, r] = await Promise.all([
    request.get(`/eval/runs/${rid}/summary`),
    request.get(`/eval/runs/${rid}/results`, { params: { page: 1, size: 200 } })
  ])
  summary.value = s
  resultRows.value = r.records
}

async function loadResults() {
  if (currentRun.value) await loadBoard(currentRun.value.id)
}

function detail(row) {
  detailRow.value = row
  detailVisible.value = true
}

async function toRegression(row) {
  await request.post(`/eval/results/${row.id}/add-to-regression`)
  ElMessage.success('已加入回归用例集')
}

async function retry(id) {
  try {
    const d = await request.post(`/eval/runs/${id}/retry`)
    ElMessage.success(`重试 ${d.retryCases} 条失败用例`)
    openRunById(id)
  } catch (e) {
    ElMessage.error(e.response?.data?.msg || '重试失败')
  }
}

function exportCsv() {
  const token = localStorage.getItem('kzai_token') || ''
  fetch(`/api/eval/runs/${currentRun.value.id}/export`,
    { headers: { Authorization: `Bearer ${token}` } })
    .then(r => r.blob()).then(b => {
      const url = URL.createObjectURL(b)
      const a = document.createElement('a')
      a.href = url
      a.download = `eval-run-${currentRun.value.id}.csv`
      a.click()
      URL.revokeObjectURL(url)
    })
}

function runStatusText(s) {
  return { 0: '运行中', 1: '成功', 2: '失败', 3: '部分成功', 4: '已终止', 5: 'REAL 未配置' }[s] || s
}
function runStatusType(s) {
  return { 0: 'primary', 1: 'success', 2: 'danger', 3: 'warning', 4: 'info', 5: 'info' }[s] || 'info'
}
function resultStatusText(s) {
  return { 0: '待执行', 1: '完成', 2: '执行失败', 3: '超时', 4: '跳过' }[s] || s
}

onMounted(() => { loadCases(); loadRuns() })
onBeforeUnmount(stopPoll)
</script>

<style scoped>
.eval-admin { padding: 4px; }
.eval-head { display: flex; align-items: center; }
.toolbar { display: flex; gap: 8px; align-items: center; margin-bottom: 10px; }
.spacer { flex: 1; }
.board-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
.board-actions { display: flex; flex-direction: column; gap: 8px; }
.metric-row { display: flex; gap: 12px; margin-top: 12px; }
.metric { flex: 1; }
.metric-label { font-size: 12px; color: #909399; }
.metric-value { font-size: 26px; font-weight: 600; margin: 4px 0; }
.metric-note { font-size: 11px; color: #909399; }
.mono { font-family: Menlo, Consolas, monospace; word-break: break-all; }
.small { font-size: 11px; }
</style>
