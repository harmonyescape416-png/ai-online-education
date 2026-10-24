<template>
  <div class="ai-page" :class="{ 'ai-page--narrow': isNarrow }">
    <!-- 左侧：会话列表 -->
    <div v-if="isNarrow && showSidebar" class="ai-drawer-mask" @click="closeSessions" />
    <aside class="ai-aside" v-show="(!isNarrow && !asideCollapsed) || showSidebar">
      <div v-if="isNarrow" style="display:flex; justify-content:flex-end; padding: 4px 8px 0">
        <button type="button" class="ai-drawer-close" aria-label="关闭会话列表" @click="closeSessions">✕</button>
      </div>
      <div class="ai-aside__head">
        <span class="ai-aside__title">会话</span>
        <el-button type="primary" size="small" :icon="Plus" @click="newSession">新建</el-button>
      </div>
      <el-scrollbar class="ai-aside__list">
        <div
          v-for="s in filteredSessions"
          :key="s.sessionId"
          role="button"
          tabindex="0"
          :aria-label="'会话：' + (s.title || '新的对话')"
          :class="{ 'ai-session--active': s.sessionId === currentSessionId }"
          @click="openSession(s.sessionId)"
          @keydown.enter="openSession(s.sessionId)"
          @keydown.space.prevent="openSession(s.sessionId)"
        >
          <div class="ai-session__title" :title="s.title || '新的对话'">{{ s.title || '新的对话' }}</div>
          <div class="ai-session__meta">
            <span>{{ formatTime(s.updateTime) }}</span>
            <span class="ai-session__ops">
              <button type="button" class="ai-session__op" aria-label="重命名会话" @click.stop="renameSession(s)"><el-icon><EditPen /></el-icon></button>
              <button type="button" class="ai-session__op" aria-label="删除会话" @click.stop="deleteSession(s)"><el-icon><Delete /></el-icon></button>
            </span>
          </div>
        </div>
        <el-empty v-if="!sessions.length" description="暂无会话" :image-size="60" />
      </el-scrollbar>
    </aside>

    <!-- 中间：对话区 -->
    <section class="ai-main">
      <div class="ai-main__head">
        <span class="ai-main__title">{{ currentTitle }}</span>
        <el-tag :type="mock ? 'warning' : 'success'" size="small" effect="dark">
          {{ mock ? 'MOCK 模式（非真实模型）' : '真实模型' }}
        </el-tag>
        <el-tooltip content="会话列表" placement="bottom"><el-button ref="sessionsBtn" size="small" aria-label="会话列表" @click="toggleSessions">会话</el-button></el-tooltip>
        <el-tooltip content="辅助信息" placement="bottom"><el-button ref="infoBtn" size="small" aria-label="辅助信息" @click="toggleSide">信息</el-button></el-tooltip>
      </div>

      <RecommendationPanel :session-id="currentSessionId" @ask="draft = $event" @start-session="newSession" />
      <div class="ai-messages" ref="msgBox">
        <div v-if="!messages.length" class="ai-welcome">
          <p>{{ welcome }}</p>
        </div>
        <div v-for="(m, i) in messages" :key="i" class="ai-msg" :class="'ai-msg--' + m.role">
          <div class="ai-msg__bubble">
            <div v-if="m.agent" class="ai-msg__agent">
              <el-tag size="small" :type="agentTagType(m.agent)" effect="plain">{{ agentLabel(m.agent) }}</el-tag>
            </div>
            <div v-if="m.role === 'assistant'" class="md" v-html="renderMd(m.content)"></div>
            <!-- M14：TTS 播报（用户主动触发；MOCK 语音演示显著标识） -->
            <div v-if="m.role === 'assistant' && m.content && !(generating && i === messages.length - 1)" class="ai-msg__tts">
              <el-button v-if="speakingIndex !== i && ttsLoadingIndex !== i" size="small" text
                :disabled="!voiceEnabled" @click="speak(i)">▶ 播报</el-button>
              <el-button v-else-if="ttsLoadingIndex === i" size="small" text type="info" :loading>生成语音中…</el-button>
              <el-button v-else size="small" text type="danger" @click="onStopSpeak">⏹ 停止播报</el-button>
              <span v-if="voiceNotice" class="tts-notice">{{ voiceNotice }}</span>
            </div>
            <pre v-else class="ai-msg__text">{{ m.content }}</pre>
            <!-- M7：工具卡片（1003 / 历史回放），未知类型忽略（向前兼容） -->
            <div v-if="m.role === 'assistant' && cardsOf(m, 'COURSE_CARD').length" class="ai-msg__cards">
              <CourseCard
                v-for="c in cardsOf(m, 'COURSE_CARD')"
                :key="c.cardId || c.payload?.courseId"
                :payload="c.payload"
                @ask="draft = $event"
              />
            </div>
            <div v-if="m.role === 'assistant' && cardsOf(m, 'ORDER_CARD').length" class="ai-msg__cards">
              <OrderCard
                v-for="c in cardsOf(m, 'ORDER_CARD')"
                :key="c.cardId || c.payload?.orderId"
                :payload="c.payload"
              />
            </div>
          <span v-if="m.role === 'assistant' && generating && i === messages.length - 1" class="caret"></span>
        </div>
        </div>
      </div>

      <div v-if="errorMsg" class="ai-error">
        {{ errorMsg }}
        <el-button size="small" text @click="errorMsg = ''">关闭</el-button>
      </div>

      <div class="ai-input">
        <label class="sr-only" for="ai-draft">向 AI 助手提问</label>
        <el-input
          id="ai-draft"
          v-model="draft"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 6 }"
          resize="none"
          aria-label="向 AI 助手提问"
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          @keydown.enter.exact.prevent="send"
        />
        <div class="ai-input__bar">
          <span class="ai-input__hint">Enter 发送 / Shift+Enter 换行</span>
          <!-- M14：语音输入（录音→STT 回填→用户确认→既有发送流程） -->
          <template v-if="!recording && !sttLoading">
            <!-- M15.14：语音说明改为悬停 Popover（修复窄区竖排），首次使用前可见隐私告知 -->
            <el-popover placement="top" :width="280" trigger="hover">
              <template #reference>
                <span>
                  <el-button :disabled="!voiceEnabled || generating" circle aria-label="语音输入" @click="startRecord">🎤</el-button>
                </span>
              </template>
              <div class="voice-pop">
                <div class="voice-pop__title">{{ micTooltip }}</div>
                <div class="voice-pop__body">{{ voiceNotice || '语音能力加载中…' }}</div>
              </div>
            </el-popover>
          </template>
          <template v-else-if="sttLoading">
            <el-button circle type="info" :loading="true" />
            <span class="rec-hint">识别中…</span>
          </template>
          <template v-else>
            <span class="rec-hint rec-on">● 录音中 {{ recSeconds }}s / {{ recMax }}s</span>
            <el-button size="small" type="danger" @click="stopRecord">停止</el-button>
            <el-button size="small" @click="cancelRecord">取消</el-button>
          </template>
          <el-button v-if="!generating" type="primary" :disabled="!draft.trim()" @click="send">发送</el-button>
          <el-button v-else type="danger" @click="stopGenerate">停止生成</el-button>
        </div>
      </div>
    </section>

    <!-- 右侧：上下文与提示 -->
    <div v-if="isNarrow && showSide" class="ai-drawer-mask" @click="closeInfo" />
    <aside class="ai-side" v-show="(!isNarrow && !sideCollapsed) || showSide">
      <div v-if="isNarrow" style="display:flex; justify-content:flex-end; padding: 4px 8px 0">
        <button type="button" class="ai-drawer-close" aria-label="关闭信息面板" @click="closeInfo">✕</button>
      </div>
      <div class="ai-card">
        <div class="ai-card__title">AI 状态</div>
        <div class="ai-card__body">
          <!-- M15.14-R2 A2：面向学员的友好状态（REAL/MOCK 与模型名保留；技术细节收进可展开诊断区） -->
          <div>AI 助手：<b>{{ mock ? '演示模式（非真实模型）' : '在线 · 通义千问' }}</b></div>
          <div>状态：<el-tag size="small" :type="mock ? 'warning' : 'success'" effect="plain">{{ mock ? 'MOCK' : '可用' }}</el-tag></div>
          <el-collapse class="ai-diag">
            <el-collapse-item title="开发者诊断信息" name="diag">
              <div>会话 ID：{{ shortSessionId }}</div>
              <div>消息记忆：Redis（7 天 TTL）</div>
            </el-collapse-item>
          </el-collapse>
        </div>
      </div>
      <div class="ai-card">
        <div class="ai-card__title">欢迎语</div>
        <div class="ai-card__body">{{ welcome }}</div>
      </div>
      <div class="ai-card">
        <div class="ai-card__title">热门问题</div>
        <div class="ai-card__body">
          <div v-for="q in hotQuestions" :key="q" class="ai-hot" @click="useQuestion(q)">{{ q }}</div>
        </div>
      </div>
      <div class="ai-card">
        <div class="ai-card__title">功能提示</div>
        <div class="ai-card__body ai-tips">
          <div>问我课程相关的问题，我会查询真实课程库并给出课程卡片。</div>
          <div>购买课程需你在对话中明确确认后才会创建待支付订单（不会自动扣款）。</div>
          <div>课程/订单卡片可点击进入对应详情页；订单卡片仅代表待支付状态。</div>
        </div>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick, onBeforeUnmount } from 'vue'
// M14 语音（录音→STT 回填→用户确认；TTS 播报）
import { WavRecorder, MediaRecorderFallback, recorderSupported, isSecureContextOk, playTts, stopSpeak, playTtsStream, stopSpeakStream } from '../utils/speech'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, EditPen, Delete, ChatDotRound } from '@element-plus/icons-vue'
import { aiApi } from '../api'
import { renderMarkdown } from '../utils/markdown'
import { streamPost, SSE_DELTA, SSE_END, SSE_PARAM } from '../utils/sse'

// M15.22：停止播报（全量——Audio 单例+流式 WebAudio sources+状态复位）
function onStopSpeak() {
  stopSpeak()
  stopSpeakStream()
  speakingIndex.value = -1
  ttsLoadingIndex.value = -1
  voiceNotice.value = ''
}
import CourseCard from '../components/CourseCard.vue'
import RecommendationPanel from '../components/RecommendationPanel.vue'
import OrderCard from '../components/OrderCard.vue'

/** 已知卡片类型；未知类型静默忽略（向前兼容） */
const KNOWN_CARD_TYPES = ['COURSE_CARD', 'ORDER_CARD']

const cardsOf = (m, type) => {
  const cards = Array.isArray(m?.cards) ? m.cards : []
  return cards.filter((c) => c && c.type === type && c.payload && typeof c.payload === 'object')
}

/** M8：当前轮命中的智能体标识（来自 1002 终态 agent 字段；不展示路由 Prompt 内容） */
const AGENT_LABELS = {
  RECOMMEND: '课程推荐',
  CONSULT: '课程咨询',
  BUY: '购买助手',
  KNOWLEDGE: '知识讲解',
  OTHER: '直接回复'
}
const agentLabel = (a) => AGENT_LABELS[a] || ''
const agentTagType = (a) => (a === 'OTHER' ? 'info' : 'success')

const API = '/api'

const sessions = ref([])
const messages = ref([])
const currentSessionId = ref('')
const draft = ref('')
const generating = ref(false)
const errorMsg = ref('')
const welcome = ref('')
const hotQuestions = ref([])
const providerName = ref('')
const mock = ref(true)
const msgBox = ref(null)

const isNarrow = ref(false)
// M15.14：两侧栏可折叠（桌面端也生效），为聊天区释放宽度
const asideCollapsed = ref(false)
// M15.14-R2 A1：已自动命名的会话集合（每会话仅自动命名一次；手动重命名优先）
const autoNamedSessions = new Set()
const sideCollapsed = ref(false)
const showSidebar = ref(false)
// M15.49：Escape 关闭窄屏抽屉（焦点恢复到触发按钮）
function onDrawerKeydown(e) {
  if (e.key !== 'Escape') return
  if (showSidebar.value) closeSessions()
  else if (showSide.value) closeInfo()
}
window.addEventListener('keydown', onDrawerKeydown)
onBeforeUnmount(() => window.removeEventListener('keydown', onDrawerKeydown))
// M15.48：会话按钮——窄屏切 showSidebar（遮罩式），桌面切 asideCollapsed（折叠）
function toggleSessions() {
  if (isNarrow.value) { showSidebar.value = !showSidebar.value; if (showSidebar.value) showSide.value = false }
  else asideCollapsed.value = !asideCollapsed.value
}
// M15.49：信息按钮——窄屏切 showSide（与会话互斥）；桌面切 sideCollapsed
const showSide = ref(false)
function toggleSide() {
  if (isNarrow.value) { showSide.value = !showSide.value; if (showSide.value) showSidebar.value = false }
  else sideCollapsed.value = !sideCollapsed.value
}
const sessionsBtn = ref(null)
const infoBtn = ref(null)
function focusTrigger(btn) {
  nextTick(() => {
    const el = btn.value
    if (typeof el?.focus === 'function') el.focus()
    else el?.$el?.focus?.()
  })
}
function closeSessions() {
  showSidebar.value = false
  focusTrigger(sessionsBtn)
}
function closeInfo() {
  showSide.value = false
  focusTrigger(infoBtn)
}

// M15.14-R6 A7：会话关键词过滤（当前用户已加载会话）
const sessionKeyword = ref('')
const filteredSessions = computed(() => {
  const kw = sessionKeyword.value.trim().toLowerCase()
  if (!kw) return sessions.value
  return sessions.value.filter(x => String(x.title || '').toLowerCase().includes(kw))
})

const currentTitle = computed(() => {
  const hit = sessions.value.find((s) => s.sessionId === currentSessionId.value)
  return hit?.title || '新的对话'
})
const shortSessionId = computed(() =>
  currentSessionId.value ? currentSessionId.value.slice(0, 8) : '-'
)

const renderMd = (text) => renderMarkdown(text || '')

const scrollToBottom = async (force) => {
  await nextTick()
  const el = msgBox.value
  if (!el) return
  // M15.14：用户主动上翻（距底 >120px）时不强制拉回，仅流式追加时贴底
  const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 120
  if (force || nearBottom) el.scrollTop = el.scrollHeight
}

const onResize = () => {
  isNarrow.value = window.innerWidth < 992
}

let controller = null
let activeSessionId = ''

const abortActive = () => {
  if (controller) {
    try {
      controller.abort()
    } catch {
      // 忽略已关闭的流
    }
    controller = null
  }
}

const loadHistory = async () => {
  try {
    sessions.value = (await aiApi.history()) || []
  } catch (e) {
    sessions.value = []
    errorMsg.value = e.message || '历史会话加载失败'
  }
}

const loadTemplates = async () => {
  try {
    const data = await aiApi.templates()
    welcome.value = data?.welcome || ''
    providerName.value = data?.provider || ''
    mock.value = data?.mock !== false
  } catch {
    // 模板失败不影响主流程
  }
}

const loadHot = async () => {
  try {
    hotQuestions.value = (await aiApi.hot()) || []
  } catch {
    hotQuestions.value = []
  }
}

const newSession = async () => {
  abortActive()
  generating.value = false
  messages.value = []
  try {
    const data = await aiApi.createSession()
    currentSessionId.value = data?.sessionId || ''
  } catch (e) {
    errorMsg.value = e.message || '新建会话失败'
  }
  await loadHistory()
  showSidebar.value = false
}

const openSession = async (sessionId) => {
  // M14：切换会话时停止播报并清理音频资源
  stopSpeak()
  stopSpeakStream()
  speakingIndex.value = -1
  ttsLoadingIndex.value = -1
  // 切换会话：先取消旧的流，避免旧内容写入新会话
  abortActive()
  generating.value = false
  currentSessionId.value = sessionId
  messages.value = []
  errorMsg.value = ''
  try {
    const data = await aiApi.detail(sessionId)
    // M7 历史回放：助手消息携带 cards（1003 载荷），不重新调用任何工具
    messages.value = (data?.messages || []).map((m) => ({
      role: m.role === 'assistant' ? 'assistant' : 'user',
      content: m.content || '',
      cards: Array.isArray(m.cards) ? m.cards : []
    }))
  } catch (e) {
    errorMsg.value = e.message || '会话消息加载失败'
  }
  await scrollToBottom()
  showSidebar.value = false
}

const renameSession = async (s) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入新的会话标题', '重命名', {
      inputValue: s.title || '',
      maxlength: 100
    })
    await aiApi.rename(s.sessionId, value.trim())
    ElMessage.success('重命名成功')
    await loadHistory()
  } catch (e) {
    if (e !== 'cancel' && e?.message) {
      errorMsg.value = e.message
    }
  }
}

const deleteSession = async (s) => {
  try {
    await ElMessageBox.confirm(`删除会话「${s.title || '新的对话'}」？`, '确认删除', {
      type: 'warning'
    })
  } catch {
    return
  }
  try {
    if (s.sessionId === currentSessionId.value) {
      abortActive()
      generating.value = false
    }
    await aiApi.remove(s.sessionId)
    ElMessage.success('会话已删除')
    if (s.sessionId === currentSessionId.value) {
      currentSessionId.value = ''
      messages.value = []
    }
    await loadHistory()
  } catch (e) {
    errorMsg.value = e.message || '删除失败'
  }
}

const useQuestion = (q) => {
  draft.value = q
}

const send = async () => {
  const text = draft.value.trim()
  if (!text || generating.value) return
  const isFirstMessage = !currentSessionId.value
  if (isFirstMessage) {
    await newSession()
    if (!currentSessionId.value) return
    // M15.43：新会话标题=首条提问前 15 字（空才「新的对话」——本地列表即时更新）
    const autoTitle = text.replace(/\s+/g, ' ').slice(0, 15)
    if (autoTitle) {
      try {
        await aiApi.rename(currentSessionId.value, autoTitle)
        const s0 = (sessions.value || []).find((x) => x.sessionId === currentSessionId.value)
        if (s0) s0.title = autoTitle
      } catch (e) { /* 标题更新失败不阻塞提问 */ }
    }
  }
  errorMsg.value = ''
  draft.value = ''
  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'assistant', content: '', cards: [], agent: '' })
  generating.value = true
  activeSessionId = currentSessionId.value
  const sessionAtStart = currentSessionId.value
  controller = new AbortController()

  let gotTerminal = false

  try {
    await streamPost(`${API}/chat`,
      { sessionId: sessionAtStart, message: text, messageId: `web-${Date.now()}-${Math.random().toString(36).slice(2, 8)}` },
      (evt) => {
        // 会话已被切换：丢弃旧流的事件，防止串会话
        if (sessionAtStart !== currentSessionId.value) return
        if (evt.event === SSE_DELTA) {
          const bubble = messages.value[messages.value.length - 1]
          bubble.content += evt.data?.content || ''
          scrollToBottom()
        } else if (evt.event === SSE_PARAM) {
          // M7 工具卡片：后端已去重，前端仅做类型白名单校验；未知类型忽略（向前兼容）
          const card = evt.data
          if (card && KNOWN_CARD_TYPES.includes(card.type) && card.payload) {
            const bubble = messages.value[messages.value.length - 1]
            if (bubble && bubble.role === 'assistant') {
              if (!Array.isArray(bubble.cards)) bubble.cards = []
              if (!bubble.cards.some((c) => c.cardId && c.cardId === card.cardId)) {
                bubble.cards.push({ type: card.type, cardId: card.cardId, payload: card.payload })
              }
            }
            scrollToBottom()
          }
        } else if (evt.event === SSE_END) {
          gotTerminal = true
          // M8：记录本轮路由到的智能体（仅展示名称，不暴露内部 Prompt）
          const bubbleEnd = messages.value[messages.value.length - 1]
          if (bubbleEnd && bubbleEnd.role === 'assistant') {
            bubbleEnd.agent = evt.data?.agent || ''
          }
          if (evt.data?.code && evt.data.code !== 'OK') {
            errorMsg.value = friendlyError(evt.data.code, evt.data.message)
          }
          // Redis 不可用时后端明确声明本次对话未持久化，不得让用户误以为已保存
          if (evt.data?.persisted === false) {
            errorMsg.value = evt.data.message
              || '会话存储（Redis）不可用，本次对话为临时内容，未可靠保存'
          }
        }
      },
      { signal: controller.signal });
    if (!gotTerminal && sessionAtStart === currentSessionId.value) {
      errorMsg.value = '生成结束但未收到终态事件，请重试'
    }
  } catch (e) {
    if (e.name !== 'AbortError' && sessionAtStart === currentSessionId.value) {
      errorMsg.value = e.message || '生成失败，请稍后重试'
    }
  } finally {
    if (activeSessionId === sessionAtStart) {
      generating.value = false
      controller = null
      // M15.14-R2 A1：首条有意义的提问成功后自动生成会话标题（本地摘要，8-20 字；
      // 手动重命名过的会话不覆盖——仅当标题仍为空/默认时执行一次）
      try {
        if (text && !autoNamedSessions.has(sessionAtStart)) {
          const cur = sessions.value.find(x => x.sessionId === sessionAtStart)
          const t = (cur?.title || '').trim()
          if (!t || t === '新的对话') {
            const summary = text.replace(/\s+/g, ' ').slice(0, 14) + (text.length > 14 ? '…' : '')
            if (summary.replace(/…/g, '').trim().length >= 2) {
              await aiApi.rename(sessionAtStart, summary)
              autoNamedSessions.add(sessionAtStart)
                            await loadHistory()
            } else {
              autoNamedSessions.add(sessionAtStart) // 无有效摘要（如纯符号）不再重试
            }
          } else {
            autoNamedSessions.add(sessionAtStart)
          }
        }
      } catch {
        // 自动命名失败不影响对话主流程
      }
    }
    // 清空停止标识，防止影响下一轮
    try {
      await aiApi.stop(sessionAtStart)
    } catch {
      // 忽略
    }
  }
}

const stopGenerate = async () => {
  if (!currentSessionId.value) return
  try {
    await aiApi.stop(currentSessionId.value)
    abortActive()
    generating.value = false
    ElMessage.info('已停止生成，已输出内容已保留')
  } catch (e) {
    errorMsg.value = e.message || '停止失败'
  }
}

const friendlyError = (code, fallback) => {
  switch (code) {
    case 'UNAUTHORIZED':
      return '模型鉴权失败（401）：请检查 DASHSCOPE_API_KEY 配置'
    case 'RATE_LIMIT':
      return '模型限流（429）：请求过于频繁，请稍后再试'
    case 'TIMEOUT':
      return '模型响应超时，请稍后再试'
    case 'UPSTREAM_ERROR':
      return fallback || '上游模型返回错误，请稍后再试'
    default:
      return fallback || '生成失败，请稍后重试'
  }
}

const formatTime = (t) => (t ? String(t).replace('T', ' ').slice(5, 16) : '')

// ==================== M14：语音输入与播报 ====================
const voiceEnabled = ref(true)
const voiceNotice = ref('')
const recording = ref(false)
const recSeconds = ref(0)
const recMax = ref(60)
const sttLoading = ref(false)
let recorder = null
const speakingIndex = ref(-1)
const ttsLoadingIndex = ref(-1)
const micTooltip = computed(() => {
  if (!voiceEnabled.value) return '语音功能已关闭'
  if (!isSecureContextOk()) return '语音需要 HTTPS 或 localhost 环境'
  if (!recorderSupported()) return '当前浏览器不支持录音'
  return '点击开始录音（最长 ' + recMax.value + ' 秒），识别后需确认才发送'
})

const loadVoiceCapabilities = async () => {
  try {
    const resp = await fetch('/api/audio/capabilities', {
      headers: { Authorization: 'Bearer ' + (localStorage.getItem('kzai_token') || '') }
    })
    const body = await resp.json()
    if (body.code !== 200 || !body.data) {
      throw new Error(body.msg || 'capabilities unavailable')
    }
    const d = body.data
    // M15.11-R1：四态清晰区分——加载中（初始）/ 可用 / 不可用 / 请求失败
    if (!d.enabled) {
      voiceEnabled.value = false
      voiceNotice.value = '语音功能已关闭（管理员可在系统参数中开启）'
    } else {
      voiceEnabled.value = true
      recMax.value = Math.min(d.sttMaxSeconds || 60, 120)
      voiceNotice.value = d.mock
        ? (d.notice || 'MOCK 语音演示，非真实语音识别/合成')
        : (d.notice || '') // M15.22：REAL 模式无提示文案（移除「语音就绪」解释性文字）
    }
  } catch (e) {
    voiceEnabled.value = false
    voiceNotice.value = '语音能力状态获取失败（不影响文字对话）'
  }
}

const startRecord = async () => {
  errorMsg.value = ''
  try {
    // M15.11-R1：主路径 WebAudio PCM→WAV（16kHz 单声道，qwen3-asr 最稳输入）；
    // 不支持 WebAudio 采集的浏览器降级 MediaRecorder（上传文件名按实际 mime 映射）
    if (recorderSupported() && window.AudioContext) {
      recorder = new WavRecorder({
        maxSeconds: recMax.value,
        onTick: (s) => { recSeconds.value = s }
      })
    } else {
      recorder = new MediaRecorderFallback({
        maxSeconds: recMax.value,
        onTick: (s) => { recSeconds.value = s }
      })
    }
    await recorder.start()
    recording.value = true
    recSeconds.value = 0
  } catch (e) {
    errorMsg.value = e.message || '无法开始录音'
    recorder = null
  }
}

const stopRecord = async () => {
  if (!recorder) return
  const blob = await recorder.stop()
  recorder = null
  recording.value = false
  if (!blob) return
  // 上传 STT（可取消：AbortController）
  const ctrl = new AbortController()
  sttLoading.value = true
  try {
    const fd = new FormData()
    // M15.11-R1：扩展名与实际容器一致（WAV 主路径恒为 wav；降级路径按 mime 映射）
    const ext = blob.type.includes('wav') ? 'wav'
      : blob.type.includes('webm') ? 'webm'
      : blob.type.includes('ogg') ? 'ogg'
      : blob.type.includes('mp4') ? 'mp4'
      : blob.type.includes('mpeg') ? 'mp3' : 'webm'
    fd.append('file', blob, 'speech.' + ext)
    const resp = await fetch('/api/audio/stt', {
      method: 'POST',
      headers: { Authorization: 'Bearer ' + (localStorage.getItem('kzai_token') || '') },
      body: fd,
      signal: ctrl.signal
    })
    const body = await resp.json()
    if (body.code !== 200 || !body.data) {
      throw new Error(body.msg || '语音识别失败')
    }
    // 红线：识别文字仅回填输入框，绝不自动发送（防误识别触发业务操作）
    draft.value = (draft.value ? draft.value + ' ' : '') + (body.data.text || '').trim()
    if (body.data.mock) {
      voiceNotice.value = 'MOCK 语音演示，非真实语音识别/合成（识别文字为固定演示内容）'
    }
    ElMessage.success('识别完成，请确认或修改后发送')
  } catch (e) {
    if (e.name !== 'AbortError') {
      errorMsg.value = e.message || '语音识别失败，请改用文字输入'
    }
  } finally {
    sttLoading.value = false
  }
}

const cancelRecord = () => {
  if (recorder) { recorder.cancel(); recorder = null }
  recording.value = false
}

const speak = async (index) => {
  const m = messages.value[index]
  if (!m || !m.content) return
  // 朗读正文纯文本（剥离 Markdown 标记与卡片，不朗读 JSON/控制事件）
  const plain = stripForTts(m.content)
  if (!plain) { errorMsg.value = '该回答没有可朗读的文字内容'; return }
  stopSpeak()
  stopSpeakStream()
  speakingIndex.value = -1
  ttsLoadingIndex.value = index
  try {
    const resp = await fetch('/api/audio/tts-stream', {
      method: 'POST',
      headers: {
        Authorization: 'Bearer ' + (localStorage.getItem('kzai_token') || ''),
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: 'text=' + encodeURIComponent(plain)
    })
    const ct = resp.headers.get('Content-Type') || ''
    if (!ct.startsWith('audio/')) {
      const body = await resp.json()
      throw new Error(body.msg || '语音合成失败')
    }
    // M15.20-F：真流式播放（WAV 边收边播；其他编码自动降级整段并标记 buffered）
    ttsLoadingIndex.value = -1
    speakingIndex.value = index
    voiceNotice.value = '' // 播报中不残留就绪提示
    const { mode, done } = await playTtsStream(resp, ct)
    if (mode === 'buffered') voiceNotice.value = '当前音频编码不支持流式播放，已降级为整段播放'
    done.then(() => { if (speakingIndex.value === index) speakingIndex.value = -1 })
  } catch (e) {
    ttsLoadingIndex.value = -1
    errorMsg.value = e.message || '语音播报失败，请稍后重试'
  }
}

/** TTS 文本净化：去 Markdown 标记/链接/表格符号，保留可读文字 */
const stripForTts = (md) => {
  return String(md)
    .replace(/```[\s\S]*?```/g, '（代码略）')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/!\[[^\]]*\]\([^)]*\)/g, '')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s*/gm, '')
    .replace(/[*_~>|#]+/g, '')
    .replace(/^\s*[-+]\s+/gm, '')
    .replace(/\n{2,}/g, '\n')
    .trim()
}

// 切换会话/组件销毁：清理录音与播报资源
const cleanupVoice = () => {
  if (recorder) { recorder.destroy(); recorder = null }
  recording.value = false
  sttLoading.value = false
  stopSpeak()
  speakingIndex.value = -1
  ttsLoadingIndex.value = -1
}

onMounted(async () => {
  onResize()
  window.addEventListener('resize', onResize)
  loadVoiceCapabilities() // M14：语音能力先行加载（不阻塞会话初始化）
  await Promise.all([loadHistory(), loadTemplates(), loadHot()])
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  abortActive()
  cleanupVoice()
})
</script>

<style scoped>
.ai-page {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  gap: 12px;
  background: #f5f7fa;
  overflow: hidden;
}
.ai-aside,
.ai-side {
  width: 232px;
  background: #fff;
  border: 1px solid #e6e8eb;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.ai-side {
  width: 272px;
  padding: 12px;
  gap: 12px;
  overflow-y: auto;
}
.ai-aside__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid #eef0f2;
}
.ai-aside__title {
  font-weight: 600;
}
.ai-aside__list {
  flex: 1;
  padding: 6px;
}
.ai-session {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
}
.ai-session:hover {
  background: #f2f6fc;
}
.ai-session--active {
  background: #e8f2ff;
}
.ai-session__title {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ai-session__meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #98a2b3;
  margin-top: 2px;
}
.ai-session__ops .el-icon {
  margin-left: 8px;
}
.ai-session__ops .el-icon:hover {
  color: #409eff;
}
.ai-main {
  flex: 1;
  min-height: 0;
  background: #fff;
  border: 1px solid #e6e8eb;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.ai-main__head { flex: 0 0 auto; }
/* M15.14-R5 D1：品牌渐变横幅（全站唯一强调性装饰；深色底白字对比达标；无动画） */
.ai-main__head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: linear-gradient(90deg, var(--el-color-primary, #4F46E5), var(--kz-accent, #F59E0B));
  border-bottom: none;
  color: #fff;
}
.ai-main__head .ai-main__title { color: #fff; font-weight: 600; }
.ai-main__title {
  font-weight: 600;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ai-messages {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 14px;
}
.ai-welcome {
  color: #667085;
  line-height: 1.7;
  white-space: pre-line;
}
.ai-msg {
  display: flex;
  margin-bottom: 12px;
}
.ai-msg--user {
  justify-content: flex-end;
}
.ai-msg__bubble {
  max-width: min(78%, 760px);
  padding: 10px 12px;
  border-radius: 8px;
  background: #f4f6f8;
  word-break: break-word;
}
.ai-msg--user .ai-msg__bubble {
  background: #409eff;
  color: #fff;
}
.ai-msg__text {
  margin: 0;
  font-family: inherit;
  white-space: pre-wrap;
  word-break: break-word;
}
.ai-msg__cards {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ai-msg__agent {
  margin-bottom: 4px;
}
.ai-error {
  margin: 0 14px 8px;
  padding: 8px 12px;
  border-radius: 6px;
  background: #fef0f0;
  color: #f56c6c;
  font-size: 13px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.ai-input {
  flex: 0 0 auto;
  border-top: 1px solid #eef0f2;
  padding: 10px 14px 12px;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.ai-input__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.ai-input__hint {
  font-size: 12px;
  color: #98a2b3;
}
.ai-card {
  border: 1px solid #eef0f2;
  border-radius: 8px;
}
.ai-card__title {
  padding: 8px 10px;
  font-weight: 600;
  border-bottom: 1px solid #eef0f2;
}
.ai-card__body {
  padding: 10px;
  font-size: 13px;
  color: #475467;
  line-height: 1.8;
}
.ai-hot {
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
}
.ai-hot:hover {
  background: #f2f6fc;
  color: #409eff;
}
.ai-tips div {
  margin-bottom: 4px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.caret {
  display: inline-block;
  width: 6px;
  height: 14px;
  vertical-align: -2px;
  background: #409eff;
  animation: blink 1s steps(2, start) infinite;
}
@keyframes blink {
  to { visibility: hidden; }
}
/* v-html 内容：Markdown 渲染容器 */
:deep(.md pre) {
  background: #1f2430;
  color: #e6e6e6;
  padding: 10px 12px;
  border-radius: 6px;
  overflow-x: auto;
}
:deep(.md code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
:deep(.md p > code) {
  background: #eef1f5;
  padding: 1px 4px;
  border-radius: 4px;
}
:deep(.md table) {
  border-collapse: collapse;
}
:deep(.md th),
:deep(.md td) {
  border: 1px solid #dfe3e8;
  padding: 4px 8px;
}
.voice-pop__title { font-weight: 600; margin-bottom: 6px; }
.voice-pop__body { font-size: 12px; color: #475467; line-height: 1.7; word-break: break-all; }
.rec-hint { font-size: 12px; color: #e6a23c; margin: 0 6px; white-space: nowrap; }
.rec-on { color: #f56c6c; font-weight: 600; }
.ai-msg__tts { margin-top: 4px; display: flex; align-items: center; gap: 4px; }
.tts-notice { font-size: 11px; color: #e6a23c; }
.ai-input :deep(.el-textarea__inner:focus-visible) {
  outline: 2px solid #4F46E5;
  outline-offset: 1px;
}
@media (max-width: 768px) {
  .ai-input { padding: 8px 10px 10px; }
  .ai-input :deep(.el-textarea__inner) { width: 100%; box-sizing: border-box; }
  .ai-input__bar { flex-wrap: wrap; gap: 6px; }
  .ai-input__bar .el-button { margin-left: auto; }
  .ai-input__hint { max-width: 100%; }
}
</style>



