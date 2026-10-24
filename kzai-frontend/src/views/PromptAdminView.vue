<template>
  <div class="prompt-admin">
    <div class="pa-head">
      <h2>AI Prompt 模板管理</h2>
      <el-tag size="small" :type="nacosAvailable ? 'success' : 'danger'" effect="plain">
        Nacos：{{ nacosAvailable ? '可用' : '不可用（使用最后有效配置）' }}
      </el-tag>
      <el-tag size="small" type="info" effect="plain">
        生效来源：{{ appliedSource }} · 版本：{{ appliedVersion }}
      </el-tag>
      <el-tag size="small" type="warning" effect="plain">直发模式（草稿/版本/回滚未启用）</el-tag>
      <el-button size="small" @click="load">刷新</el-button>
    </div>

    <el-alert v-if="!nacosInSync && nacosAvailable" type="warning" :closable="false"
              title="Nacos 配置与本地生效内容不一致，点击刷新或重新发布" style="margin-bottom: 12px" />

    <el-tabs v-model="activeAgent">
      <el-tab-pane v-for="a in agents" :key="a.agentType" :name="a.agentType">
        <template #label>
          {{ a.displayName }}
          <el-tag v-if="a.hasTools" size="small" effect="plain" style="margin-left: 4px">工具</el-tag>
        </template>
        <div class="pa-body">
          <el-form label-position="top">
            <el-form-item label="System Prompt">
              <el-input v-model="draft.systemPrompt" type="textarea" :rows="10" maxlength="8000" show-word-limit />
            </el-form-item>
            <el-form-item label="欢迎语">
              <el-input v-model="draft.welcomeText" maxlength="500" show-word-limit />
            </el-form-item>
            <el-form-item label="推荐问题（每行一条，最多 8 条）">
              <el-input v-model="draft.hotQuestionsText" type="textarea" :rows="3" />
            </el-form-item>
          </el-form>
          <div class="pa-ops">
            <el-button @click="preview">预览校验</el-button>
            <el-button @click="saveDraft" :loading="drafting">保存草稿</el-button>
            <el-button type="primary" @click="publish">发布到 Nacos</el-button>
            <el-button @click="openHistory">版本历史</el-button>
            <el-button @click="restoreDraftFromServer" :loading="restoringDraft">载入草稿</el-button>
          </div>
          <el-alert v-if="previewResult" :type="previewResult.valid ? 'success' : 'error'"
                    :closable="false" style="margin-top: 12px">
            <div v-if="previewResult.valid">校验通过，渲染结果见下方（预览无任何副作用）</div>
            <div v-else>
              <div v-for="e in previewResult.errors" :key="e">· {{ e }}</div>
            </div>
          </el-alert>
          <el-input v-if="previewResult && previewResult.valid" type="textarea" :rows="6" readonly
                    :model-value="previewText" style="margin-top: 8px" />
          <div class="pa-meta">
            当前来源：{{ activeSpec.source }} · 版本：{{ activeSpec.version }} ·
            工具权限：{{ activeSpec.hasTools ? '见最小授权矩阵' : '无业务工具' }}
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- M15.19-D：版本历史与回滚 -->
    <el-drawer v-model="historyVisible" title="版本历史（含草稿/发布/回滚）" size="55%">
      <el-table :data="historyRows" v-loading="historyLoading" border size="small">
        <el-table-column prop="versionNo" label="版本" width="70" />
        <el-table-column prop="action" label="动作" width="90">
          <template #default="{ row }">{{ { PUBLISH: '发布', ROLLBACK: '回滚', DRAFT: '草稿' }[row.action] || row.action }}</template>
        </el-table-column>
        <el-table-column prop="resultOk" label="结果" width="70">
          <template #default="{ row }"><el-tag size="small" :type="row.resultOk ? 'success' : 'danger'">{{ row.resultOk ? '成功' : '失败' }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="resultMsg" label="说明" min-width="140" show-overflow-tooltip />
        <el-table-column prop="createTime" label="时间" width="160" />
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <el-button size="small" link type="primary" :disabled="!row.resultOk || row.action === 'DRAFT'"
                       @click="doRollback(row)">回滚到此版</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="confirmVisible" title="确认发布" width="480px">
      <p>将把「{{ agentLabel(activeAgent) }}」的新内容与其余四类当前生效内容合成完整配置，发布到 Nacos：</p>
      <p class="mono">dataId=kzai-ai-prompts.json · group=tj.ai</p>
      <p>发布成功立即对新对话生效；失败时保留当前已验证配置。</p>
      <template #footer>
        <el-button @click="confirmVisible = false">取消</el-button>
        <el-button type="primary" :loading="publishing" @click="doPublish">确认发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../api'

const AGENT_ORDER = ['ROUTE', 'RECOMMEND', 'CONSULT', 'BUY', 'KNOWLEDGE']
const LABELS = { ROUTE: '路由', RECOMMEND: '课程推荐', CONSULT: '课程咨询', BUY: '购买助手', KNOWLEDGE: '知识讲解' }

const agents = ref([])
const activeAgent = ref('RECOMMEND')
const draft = ref({ systemPrompt: '', welcomeText: '', hotQuestionsText: '' })
const previewResult = ref(null)
const nacosAvailable = ref(true)
const nacosInSync = ref(true)
const appliedSource = ref('BUILTIN')
const appliedVersion = ref('builtin')
const confirmVisible = ref(false)
const publishing = ref(false)

const activeSpec = computed(() => agents.value.find((a) => a.agentType === activeAgent.value) || {})
const agentLabel = (t) => LABELS[t] || t
const previewText = computed(() => previewResult.value && previewResult.value.rendered
  ? JSON.stringify(previewResult.value.rendered, null, 2) : '')

const load = async () => {
  try {
    const data = await request.get('/admin/ai/prompts')
    agents.value = (data?.agents || []).slice()
      .sort((a, b) => AGENT_ORDER.indexOf(a.agentType) - AGENT_ORDER.indexOf(b.agentType))
    nacosAvailable.value = data?.nacos?.available !== false
    nacosInSync.value = data?.nacos?.inSync !== false
    appliedSource.value = data?.nacos?.appliedSource || 'BUILTIN'
    appliedVersion.value = data?.nacos?.appliedVersion || 'builtin'
    loadDraft()
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  }
}

const loadDraft = () => {
  const spec = activeSpec.value
  draft.value.systemPrompt = spec.systemPrompt || ''
  draft.value.welcomeText = spec.welcomeText || ''
  draft.value.hotQuestionsText = (spec.hotQuestions || []).join('\n')
}

const buildBody = () => ({
  systemPrompt: draft.value.systemPrompt,
  welcomeText: draft.value.welcomeText,
  hotQuestions: draft.value.hotQuestionsText.split('\n').map((s) => s.trim()).filter(Boolean)
})

const preview = async () => {
  try {
    previewResult.value = await request.post(`/admin/ai/prompts/${activeAgent.value}/preview`, buildBody())
  } catch (e) {
    ElMessage.error(e.message || '预览失败')
  }
}

const publish = () => { confirmVisible.value = true }

const doPublish = async () => {
  publishing.value = true
  try {
    const data = await request.post(`/admin/ai/prompts/${activeAgent.value}/publish`, buildBody())
    if (data?.published) {
      ElMessage.success(`发布成功：${data.version}（已热生效）`)
      confirmVisible.value = false
      await load()
    } else {
      ElMessage.error(`发布失败：${data?.error || '未知错误'}（保留当前已验证配置）`)
    }
  } catch (e) {
    ElMessage.error(e.message || '发布失败')
  } finally {
    publishing.value = false
  }
}

// M15.19-D：草稿/历史/回滚
const drafting = ref(false)
const historyVisible = ref(false)
const historyLoading = ref(false)
const historyRows = ref([])

// M15.20-A：载入最新草稿（恢复编辑入口）
const restoringDraft = ref(false)
const restoreDraftFromServer = async () => {
  restoringDraft.value = true
  try {
    const d = await request.get(`/admin/ai/prompts/${activeAgent.value}/load-draft`)
    if (!d?.exists) {
      ElMessage.info('该模板暂无草稿')
      return
    }
    draft.value.systemPrompt = d.systemPrompt || ''
    draft.value.welcomeText = d.welcomeText || ''
    draft.value.hotQuestionsText = (d.hotQuestions || []).join('\n')
    ElMessage.success(`已载入草稿（版本 ${d.versionNo}）`)
  } catch (e) {
    ElMessage.error(e?.message || '草稿载入失败')
  } finally {
    restoringDraft.value = false
  }
}

const saveDraft = async () => {
  drafting.value = true
  try {
    await request.post(`/admin/ai/prompts/${activeAgent.value}/save-draft`, buildBody())
    ElMessage.success('草稿已保存（不影响当前生效版本）')
  } catch (e) {
    ElMessage.error(e?.message || '草稿保存失败')
  } finally {
    drafting.value = false
  }
}

const openHistory = async () => {
  historyVisible.value = true
  historyLoading.value = true
  try {
    historyRows.value = await request.get(`/admin/ai/prompts/${activeAgent.value}/history`)
  } catch (e) {
    ElMessage.error(e?.message || '历史加载失败')
  } finally {
    historyLoading.value = false
  }
}

const doRollback = async (row) => {
  try {
    const d = await request.post(`/admin/ai/prompts/${activeAgent.value}/rollback/${row.versionNo}`)
    ElMessage.success(`已回滚至版本 ${row.versionNo}，当前生效：${d?.version || ''}`)
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '回滚失败')
  }
}

const switchTab = () => { previewResult.value = null; loadDraft() }

watch(activeAgent, switchTab)
onMounted(load)
</script>

<style scoped>
.prompt-admin { max-width: 1080px; }
.pa-head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.pa-head h2 { margin: 0; font-size: 18px; margin-right: 8px; }
.pa-body { max-width: 860px; }
.pa-ops { display: flex; gap: 8px; }
.pa-meta { margin-top: 12px; color: #98a2b3; font-size: 12px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
</style>
