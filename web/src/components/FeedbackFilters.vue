<script setup lang="ts">
// components/FeedbackFilters.vue —— 筛选栏（§2.5 筛选 / D2 即时生效 / D3 回第 1 页）
// 控件只 emit 变更补丁，防抖与页码重置由父层 store 承接；日期区间的「结束不早于开始」
// 由 el-date-picker daterange 原生交互保证（一次会话内不可能选出倒挂区间）
import { computed } from 'vue'
import { CATEGORY_LABEL, STATUS_LABEL } from '../api/types'
import type { FeedbackCategory, FeedbackStatus } from '../api/types'
import { hasActiveFilters, isFeedbackCategory } from '../url/feedbackQuery'
import type { FeedbackQueryState } from '../url/feedbackQuery'
import type { FilterPatch } from '../stores/useFeedbackList'

const props = defineProps<{ query: FeedbackQueryState }>()
const emit = defineEmits<{ change: [patch: FilterPatch]; clear: [] }>()

const hasFilters = computed(() => hasActiveFilters(props.query))

const categoryOptions: Array<{ value: FeedbackCategory; label: string }> = (
  Object.keys(CATEGORY_LABEL) as FeedbackCategory[]
).map((value) => ({ value, label: CATEGORY_LABEL[value] }))

const statusOptions: Array<{ value: FeedbackStatus; label: string }> = [
  { value: 0, label: STATUS_LABEL[0] },
  { value: 1, label: STATUS_LABEL[1] },
]

function textPatch(field: 'appName' | 'appVersion' | 'keyword', value: string): FilterPatch {
  const normalized = value === '' ? undefined : value
  if (field === 'appName') return { appName: normalized }
  if (field === 'appVersion') return { appVersion: normalized }
  return { keyword: normalized }
}

function makeTextHandler(field: 'appName' | 'appVersion' | 'keyword') {
  return (value: string): void => {
    emit('change', textPatch(field, value))
  }
}
const onAppNameChange = makeTextHandler('appName')
const onAppVersionChange = makeTextHandler('appVersion')
const onKeywordChange = makeTextHandler('keyword')

// EP 多选清空/未知值 → 归一为 undefined（空值省略键）
function onCategoriesChange(value: unknown): void {
  const selected = Array.isArray(value)
    ? value.filter((item): item is FeedbackCategory => typeof item === 'string' && isFeedbackCategory(item))
    : []
  emit('change', { categories: selected.length > 0 ? selected : undefined })
}

// 仅接受字面量 0/1（与后端 §2.1 status 值域一致）；清空 → undefined
function onStatusChange(value: unknown): void {
  const status: FeedbackStatus | undefined = value === 0 || value === 1 ? value : undefined
  emit('change', { status })
}

const dateRange = computed<[string, string] | null>(() =>
  props.query.startDate !== undefined && props.query.endDate !== undefined
    ? [props.query.startDate, props.query.endDate]
    : null,
)

// value-format 保证载荷是 yyyy-MM-DD 字符串对；清空 → 两键同时回 undefined
function onDateRangeChange(value: unknown): void {
  const range =
    Array.isArray(value) && value.length === 2 && typeof value[0] === 'string' && typeof value[1] === 'string'
      ? ([value[0], value[1]] as [string, string])
      : null
  emit(
    'change',
    range === null ? { startDate: undefined, endDate: undefined } : { startDate: range[0], endDate: range[1] },
  )
}
</script>

<template>
  <div class="feedback-filters">
    <el-input
      class="feedback-filters__control"
      :model-value="query.appName ?? ''"
      placeholder="应用名（精确）"
      clearable
      @update:model-value="onAppNameChange"
    />
    <el-input
      class="feedback-filters__control"
      :model-value="query.appVersion ?? ''"
      placeholder="版本号（精确）"
      clearable
      @update:model-value="onAppVersionChange"
    />
    <el-select
      class="feedback-filters__control feedback-filters__control--categories"
      :model-value="query.categories ?? []"
      placeholder="分类"
      multiple
      clearable
      collapse-tags
      collapse-tags-tooltip
      @update:model-value="onCategoriesChange"
    >
      <el-option v-for="option in categoryOptions" :key="option.value" :label="option.label" :value="option.value" />
    </el-select>
    <el-select
      class="feedback-filters__control feedback-filters__control--status"
      :model-value="query.status"
      placeholder="状态"
      clearable
      @update:model-value="onStatusChange"
    >
      <el-option v-for="option in statusOptions" :key="option.value" :label="option.label" :value="option.value" />
    </el-select>
    <el-date-picker
      class="feedback-filters__control feedback-filters__control--date"
      :model-value="dateRange"
      type="daterange"
      value-format="YYYY-MM-DD"
      range-separator="至"
      start-placeholder="开始日期"
      end-placeholder="结束日期"
      clearable
      @update:model-value="onDateRangeChange"
    />
    <el-input
      class="feedback-filters__control feedback-filters__control--keyword"
      :model-value="query.keyword ?? ''"
      placeholder="关键词（标题/内容/联系方式）"
      clearable
      @update:model-value="onKeywordChange"
    />
    <el-button v-if="hasFilters" link type="primary" @click="emit('clear')">清空筛选</el-button>
  </div>
</template>

<style scoped>
/* D9 窄屏：筛选栏换行折叠 */
.feedback-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.feedback-filters__control {
  width: 180px;
}

.feedback-filters__control--categories,
.feedback-filters__control--keyword {
  width: 240px;
}

.feedback-filters__control--status {
  width: 120px;
}

.feedback-filters__control--date {
  width: 260px;
}
</style>
