<script setup lang="ts">
// 登录页（PRD §2.5 登录段 / §4 任务 2，issue #14）：
// 校验与错误文案 = api/auth.ts 纯函数；发请求经 createAuthApi(http)（http 为 router.ts 共享 HttpClient，冻结接缝）
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Lock, User } from '@element-plus/icons-vue'
import { createAuthApi, createLoginFlow, REMEMBERED_USERNAME_KEY } from '../api/auth'
import { session } from '../auth/session'
import { http } from '../router'

const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const rememberUsername = ref(false)

// returnUrl 取路由 query（guard.ts 生成的已编码 fullPath，原样透传）；空/缺失 = null → 默认 /feedback
function returnUrlFromQuery(): string | null {
  const value = route.query.returnUrl
  return typeof value === 'string' && value !== '' ? value : null
}

const {
  submitting,
  error,
  fieldErrors,
  submit: submitLogin,
} = createLoginFlow({
  api: createAuthApi(http),
  setToken: session.set,
  navigate: (to) => {
    void router.push(to)
  },
  returnUrl: returnUrlFromQuery,
})

// D12：记住用户名（不记密码），localStorage 键名冻结为 feedback_admin_username
onMounted(() => {
  const remembered = localStorage.getItem(REMEMBERED_USERNAME_KEY)
  if (remembered !== null && remembered !== '') {
    username.value = remembered
    rememberUsername.value = true
  }
})

async function onSubmit(): Promise<void> {
  const ok = await submitLogin({ username: username.value, password: password.value })
  if (!ok) return
  if (rememberUsername.value) localStorage.setItem(REMEMBERED_USERNAME_KEY, username.value)
  else localStorage.removeItem(REMEMBERED_USERNAME_KEY)
}
</script>

<template>
  <main class="login-view">
    <section class="login-card">
      <h1 class="login-title">反馈管理控制台</h1>
      <p class="login-subtitle">管理员登录</p>
      <el-alert
        v-if="error !== null"
        class="login-error"
        type="error"
        :title="error"
        :closable="false"
        show-icon
      />
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="用户名" :error="fieldErrors.username">
          <el-input
            v-model="username"
            placeholder="请输入用户名"
            :prefix-icon="User"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-form-item label="密码" :error="fieldErrors.password">
          <el-input
            v-model="password"
            type="password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-checkbox v-model="rememberUsername">记住用户名</el-checkbox>
        <el-button
          class="login-submit"
          type="primary"
          native-type="submit"
          :loading="submitting"
          :disabled="submitting"
          @click="onSubmit"
        >
          登 录
        </el-button>
      </el-form>
    </section>
  </main>
</template>

<style scoped>
.login-view {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.login-card {
  width: 100%;
  max-width: 360px;
}

.login-title {
  margin: 0 0 4px;
  font-size: 20px;
  font-weight: 600;
}

.login-subtitle {
  margin: 0 0 20px;
  font-size: 14px;
}

.login-error {
  margin-bottom: 16px;
}

.login-submit {
  width: 100%;
  margin-top: 16px;
}
</style>
