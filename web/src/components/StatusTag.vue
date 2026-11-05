<script setup lang="ts">
// components/StatusTag.vue —— 状态标签（§2.4：status 列中文标签，文案口径见 STATUS_LABEL / D7）
import { STATUS_LABEL } from '../api/types'
import type { FeedbackStatus } from '../api/types'

defineProps<{ status: FeedbackStatus }>()

const STATUS_TAG_TYPE: Record<FeedbackStatus, 'warning' | 'success'> = { 0: 'warning', 1: 'success' }
</script>

<template>
  <el-tag :type="STATUS_TAG_TYPE[status]">{{ STATUS_LABEL[status] }}</el-tag>
</template>
