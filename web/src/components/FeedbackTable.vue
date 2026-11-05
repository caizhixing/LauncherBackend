<script setup lang="ts">
// components/FeedbackTable.vue —— 列表表格（§2.4 列规则 / D8 六列 + 操作列 / D9 表格自身横向滚动）
// 行为 3.7：createdAt 直接渲染后端字符串，禁止 new Date() 重排时区
import { CATEGORY_LABEL } from '../api/types'
import type { FeedbackCategory, FeedbackListItem } from '../api/types'
import StatusTag from './StatusTag.vue'

defineProps<{
  list: FeedbackListItem[]
  loading: boolean
  error: string | null
  emptyText: string | null
  showClearEmpty: boolean
}>()

const emit = defineEmits<{ retry: []; clear: [] }>()

const CATEGORY_TAG_TYPE: Record<FeedbackCategory, 'danger' | 'warning' | 'primary' | 'info'> = {
  BUG: 'danger',
  EXPERIENCE: 'warning',
  SUGGESTION: 'primary',
  OTHER: 'info',
}
</script>

<template>
  <!-- 固定列宽合计 662 + 标题 min-width 200 → 1024px 内不横向滚动（表格自身滚动除外，D9） -->
  <el-table :data="error !== null ? [] : list" v-loading="loading">
    <el-table-column label="#" width="72">
      <template #default="{ row }">#{{ row.id }}</template>
    </el-table-column>
    <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
    <el-table-column label="分类" width="96">
      <template #default="{ row }">
        <el-tag :type="CATEGORY_TAG_TYPE[row.category as FeedbackCategory]" effect="plain">
          {{ CATEGORY_LABEL[row.category as FeedbackCategory] }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="应用" width="170">
      <template #default="{ row }">{{ row.appName }} {{ row.appVersion }}</template>
    </el-table-column>
    <el-table-column label="状态" width="88">
      <template #default="{ row }"><StatusTag :status="row.status" /></template>
    </el-table-column>
    <!-- 原样渲染后端字符串（yyyy-MM-dd HH:mm:ss，Asia/Shanghai），不做任何 Date 转换 -->
    <el-table-column label="创建时间" width="160">
      <template #default="{ row }">{{ row.createdAt }}</template>
    </el-table-column>
    <!-- issue #4 挂点：本单只放「详情」占位（禁用态），写路径动作由下一单接入 -->
    <el-table-column label="操作" width="76" fixed="right">
      <template #default>
        <el-button type="primary" link disabled>详情</el-button>
      </template>
    </el-table-column>
    <template #empty>
      <div v-if="error !== null" class="feedback-table__state">
        <p class="feedback-table__state-text">{{ error }}</p>
        <el-button type="primary" @click="emit('retry')">重试</el-button>
      </div>
      <div v-else-if="emptyText !== null" class="feedback-table__state">
        <p class="feedback-table__state-text">{{ emptyText }}</p>
        <el-button v-if="showClearEmpty" link type="primary" @click="emit('clear')">清空筛选</el-button>
      </div>
      <!-- page 超界：list 空但 total>0，非空态文案管辖范围（§2.5 不自动跳页） -->
      <span v-else>暂无数据</span>
    </template>
  </el-table>
</template>

<style scoped>
.feedback-table__state {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  padding: 16px 0;
}

.feedback-table__state-text {
  margin: 0;
  color: var(--el-text-color-secondary);
}
</style>
