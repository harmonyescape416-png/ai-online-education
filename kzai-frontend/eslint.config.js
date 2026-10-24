import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'

const browserGlobals = {
  TextDecoder: 'readonly',
  TextEncoder: 'readonly',
  navigator: 'readonly',
  Blob: 'readonly',
  File: 'readonly',
  FileReader: 'readonly',
  MediaRecorder: 'readonly',
  Audio: 'readonly',
  AbortController: 'readonly',
  Promise: 'readonly',
  alert: 'readonly',
  confirm: 'readonly',
  HTMLElement: 'readonly',
  HTMLInputElement: 'readonly',
  HTMLTextAreaElement: 'readonly',
  CustomEvent: 'readonly',
  window: 'readonly',
  document: 'readonly',
  localStorage: 'readonly',
  sessionStorage: 'readonly',
  fetch: 'readonly',
  FormData: 'readonly',
  AudioContext: 'readonly',
  webkitAudioContext: 'readonly',
  HTMLMediaElement: 'readonly',
  URL: 'readonly',
  URLSearchParams: 'readonly',
  location: 'readonly',
  history: 'readonly',
  setTimeout: 'readonly',
  setInterval: 'readonly',
  clearInterval: 'readonly',
  clearTimeout: 'readonly',
  requestAnimationFrame: 'readonly',
  cancelAnimationFrame: 'readonly',
  performance: 'readonly',
  console: 'readonly',
  btoa: 'readonly',
  atob: 'readonly',
  defineProps: 'readonly',
  defineEmits: 'readonly',
  defineExpose: 'readonly',
  defineOptions: 'readonly',
  defineSlots: 'readonly',
  defineModel: 'readonly',
  withDefaults: 'readonly'
}

export default [
  { ignores: ['node_modules/**', 'dist/**', 'tests/**'] },
  js.configs.recommended,
  ...pluginVue.configs['flat/base'],
  {
    files: ['src/**/*.js'],
    languageOptions: { globals: browserGlobals },
    rules: {
      'no-undef': 'error',
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_', caughtErrorsIgnorePattern: '^_' }]
    }
  },
  {
    files: ['src/**/*.vue'],
    languageOptions: { globals: browserGlobals },
    rules: {
      'no-undef': 'error',
      'no-unused-vars': 'off'
    }
  }
]
