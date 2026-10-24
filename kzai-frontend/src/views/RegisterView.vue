<template>
  <div class="register-page">
    <el-card class="register-card" shadow="always">
      <h2 class="register-title">注册账号</h2>
      <p class="register-sub">AI 智能在线教育平台 · 仅支持学员注册</p>
      <el-form :model="form" label-width="0" @submit.prevent>
        <el-form-item>
          <el-input v-model="form.phone" placeholder="手机号码" maxlength="11" :disabled="codeSent" clearable>
            <template #prefix><el-icon><Iphone /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <div class="code-row">
            <el-input v-model="form.smsCode" placeholder="短信验证码" maxlength="6" style="flex: 1">
              <template #prefix><el-icon><Key /></el-icon></template>
            </el-input>
            <el-button :disabled="countdown > 0 || !form.phone || codeSent" :loading="sending" style="margin-left: 10px"
                       @click="onSendCode">
              {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" show-password placeholder="登录密码（6-32 位）">
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="确认密码">
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="register-btn" :loading="submitting" @click="onRegister">注 册</el-button>
        </el-form-item>
        <div class="register-links">
          <router-link to="/login" class="register-link">返回登录</router-link>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Iphone, Key, Lock } from '@element-plus/icons-vue'
import request from '../api'

const router = useRouter()
const form = reactive({ phone: '', smsCode: '', password: '', confirmPassword: '' })
const sending = ref(false)
const submitting = ref(false)
const countdown = ref(0)
let timer = null
let codeSent = ref(false)

/** 发送验证码（服务端 60s 间隔权威限制；倒计时仅为界面引导，刷新以服务端为准） */
async function onSendCode() {
  if (sending.value || countdown.value > 0) return
  if (!/^1[3-9]\d{9}$/.test(form.phone)) {
    ElMessage.error('请输入正确的手机号码')
    return
  }
  sending.value = true
  try {
    const d = await request.post('/auth/sms/send', { phone: form.phone, scene: 'REGISTER' })
    codeSent.value = true
    // M15.17：MOCK 模式响应带 devCode——自动填入验证码框（开发/测试便利；REAL 模式无此字段，用户手机收码）
    if (d?.devCode) {
      form.smsCode = String(d.devCode)
      ElMessage.success('验证码已发送并自动填入（开发环境）')
    } else {
      ElMessage.success(d?.message || '验证码已发送，请在有效期内输入')
    }
    countdown.value = 60
    timer = setInterval(() => {
      countdown.value--
      if (countdown.value <= 0 && timer) {
        clearInterval(timer)
        timer = null
      }
    }, 1000)
  } catch (e) {
    ElMessage.error(e?.message || '验证码发送失败')
  } finally {
    sending.value = false
  }
}

async function onRegister() {
  if (submitting.value) return
  if (!codeSent.value) { ElMessage.error('请先获取短信验证码'); return }
  if (!form.smsCode || form.smsCode.length !== 6) { ElMessage.error('请输入 6 位短信验证码'); return }
  if (form.password.length < 6 || form.password.length > 32) { ElMessage.error('密码长度需为 6-32 位'); return }
  if (form.password !== form.confirmPassword) { ElMessage.error('两次输入的密码不一致'); return }
  submitting.value = true
  try {
    const d = await request.post('/auth/register', {
      phone: form.phone, smsCode: form.smsCode,
      password: form.password, confirmPassword: form.confirmPassword
    })
    ElMessage.success(d?.message || '注册成功，请使用手机号和密码登录')
    setTimeout(() => router.push('/login'), 1200)
  } catch (e) {
    ElMessage.error(e?.message || '注册失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {})
onUnmounted(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.register-page { min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #1a3f8f, #4F46E5); }
.register-card { width: 400px; padding: 8px 12px; border-radius: 12px; }
.register-title { text-align: center; margin: 4px 0 2px; font-size: 22px; color: #1a3f8f; }
.register-sub { text-align: center; color: #98a2b3; font-size: 13px; margin-bottom: 18px; }
.code-row { display: flex; width: 100%; }
.register-btn { width: 100%; }
.register-links { text-align: center; margin-top: 4px; }
.register-link { color: #4F46E5; font-size: 13px; text-decoration: none; }
</style>
