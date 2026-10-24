<template>
  <div class="kz-video">
    <video
      v-if="src"
      ref="videoRef"
      class="kz-video__el"
      controls
      preload="metadata"
      :src="src"
      @loadedmetadata="onLoadedMetadata"
      @timeupdate="onTimeUpdate"
      @ended="onEnded"
      @error="onError"
    ></video>
    <el-empty v-else description="本章暂无视频资源" />
    <div class="kz-video__meta">
      <span v-if="restored">已从服务端恢复上次播放位置 {{ formatTime(lastPosition) }}</span>
      <span v-else>播放位置由服务端持久化，刷新或换设备后可续播</span>
      <span class="kz-video__pos">当前 {{ formatTime(Math.floor(currentSeconds)) }} / {{ formatTime(durationSeconds) }}</span>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'

/**
 * 视频播放与断点续学组件（M5）。
 *
 * 断点位置由服务端持久化：进入时从服务端返回的 videoPositionSec 恢复播放位置，
 * 播放过程中按固定间隔向服务端上报真实播放位置，刷新页面或跨设备重新登录均可续播。
 * 不使用 localStorage 冒充跨设备续学。
 */
const props = defineProps({
  src: { type: String, default: '' },
  /** 服务端持久化的断点位置（秒） */
  initialPosition: { type: Number, default: 0 },
  /** 上报间隔（秒） */
  reportInterval: { type: Number, default: 10 }
})
const emit = defineEmits(['progress', 'ended'])

const videoRef = ref(null)
const currentSeconds = ref(0)
const durationSeconds = ref(0)
const lastPosition = ref(0)
const restored = ref(false)
let lastReported = -1
let seekApplied = false
let timer = null

function formatTime(sec) {
  const s = Math.max(0, Math.floor(sec || 0))
  const m = Math.floor(s / 60)
  return `${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

function onLoadedMetadata() {
  const el = videoRef.value
  if (!el) return
  durationSeconds.value = Math.floor(el.duration || 0)
  if (!seekApplied && props.initialPosition > 0 && props.initialPosition < (el.duration || 0)) {
    el.currentTime = props.initialPosition
    lastPosition.value = props.initialPosition
    restored.value = true
  }
  seekApplied = true
}

function onTimeUpdate() {
  const el = videoRef.value
  if (!el) return
  currentSeconds.value = el.currentTime || 0
}

function onEnded() {
  const el = videoRef.value
  const sec = Math.floor(el?.duration || currentSeconds.value || 0)
  emit('progress', { videoPositionSec: sec, videoDurationSec: Math.floor(el?.duration || 0), completed: true })
  emit('ended')
}

function onError() {
  // 播放失败不谎报成功：交由父级提示
  emit('progress', { error: true })
}

/** 定时上报真实播放位置，服务端据此累计有效学习时长 */
function position() {
  const el = videoRef.value
  return Math.floor(el?.currentTime || currentSeconds.value || 0)
}

function seek(seconds) {
  const el = videoRef.value
  const value = Number(seconds)
  if (!el || !Number.isFinite(value) || value < 0) return false
  const duration = Number(el.duration)
  const next = Number.isFinite(duration) && duration > 0 ? Math.min(value, duration) : value
  el.currentTime = next
  currentSeconds.value = next
  return true
}

function flush(force = false) {
  const el = videoRef.value
  if (!el || el.paused) return
  const pos = Math.floor(el.currentTime || 0)
  if (!force && Math.abs(pos - lastReported) < props.reportInterval) return
  if (pos === lastReported && !force) return
  lastReported = pos
  emit('progress', {
    videoPositionSec: pos,
    videoDurationSec: Math.floor(el.duration || 0)
  })
}

watch(
  () => props.src,
  () => {
    seekApplied = false
    restored.value = false
    lastReported = -1
    currentSeconds.value = 0
    durationSeconds.value = 0
  }
)

timer = setInterval(() => flush(false), 5000)

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
  flush(true)
})

defineExpose({ flush, seek, position })
</script>

<style scoped>
.kz-video__el {
  width: 100%;
  max-height: 52vh;
  background: #000;
  border-radius: 6px;
}
.kz-video__meta {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
}
.kz-video__pos {
  flex: none;
}
</style>
