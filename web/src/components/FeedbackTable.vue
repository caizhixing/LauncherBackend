<script setup lang="ts">
// components/FeedbackTable.vue —— 列表表格（§2.4 列规则 / D8 六列 + 操作列 / D9 表格自身横向滚动）
// issue #16：操作列挂「标记完成/待处理、详情、删除」+ 承载详情抽屉；写动作走
// activeFeedbackListStore()（视图创建的同一实例，登记机制见 stores/useFeedbackList.ts 注释）
// 行为 3.7：createdAt 直接渲染后端字符串，禁止 new Date() 重排时区
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CATEGORY_LABEL } from '../api/types'
import type { FeedbackCategory, FeedbackListItem, FeedbackStatus } from '../api/types'
import { activeFeedbackListStore } from '../stores/useFeedbackList'
import type { WriteOutcome } from '../stores/useFeedbackList'
import FeedbackDetailDialog from './FeedbackDetailDialog.vue'
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

// 行内写动作与视图共用同一 store 实例（视图 setup 先行登记）；独立渲染（无实例）时按钮不动作
const store = activeFeedbackListStore()

const route = useRoute()

// D10 高亮关键词：URL 是筛选状态唯一事实源（§2.3），直接读路由 query（与 encodeQuery 同名参数）
const keyword = computed<string>(() => {
  const raw = route.query.keyword
  const first = Array.isArray(raw) ? raw[0] : raw
  return typeof first === 'string' ? first : ''
})

// —— 详情抽屉 ——
const detailVisible = ref(false)
const detailItem = ref<FeedbackListItem | null>(null)

function onDetail(item: FeedbackListItem): void {
  detailItem.value = item
  detailVisible.value = true
}

// 写成功后重拉会整体替换 state.list；抽屉若开着需按 id 换绑到新行对象（找不到则关闭）
function syncDetailItem(id: number): void {
  if (store === null || detailItem.value === null) return
  const fresh = store.state.list.find((row) => row.id === id)
  if (fresh === undefined) {
    detailVisible.value = false
    detailItem.value = null
  } else {
    detailItem.value = fresh
  }
}

// —— 写动作 → 用户提示（§2.6）——
const TOGGLE_ACTION_COPY: Record<FeedbackStatus, string> = { 0: '标记完成', 1: '标记待处理' }
const TOGGLE_SUCCESS_COPY: Record<FeedbackStatus, string> = { 0: '已标记为已完成', 1: '已标记为待处理' }

function applyWriteOutcome(outcome: WriteOutcome, successCopy: string): void {
  if (outcome.ok) {
    ElMessage.success(successCopy)
    return
  }
  if (outcome.kind === 'cancelled' || outcome.kind === 'unauthorized') return
  if (outcome.kind === 'notFound') {
    ElMessage.warning(outcome.message) // 「这条反馈已被删除」，列表已由 store 刷新（行为 4.4）
    return
  }
  ElMessage.error(outcome.message) // 「操作失败，请稍后重试」，本地状态不变（§2.6）
}

async function onToggle(item: FeedbackListItem): Promise<void> {
  if (store === null) return
  const outcome = await store.toggleStatus(item)
  applyWriteOutcome(outcome, TOGGLE_SUCCESS_COPY[item.status])
  if (outcome.ok) syncDetailItem(item.id)
}

// 行为 4.3 / D6：普通二次确认（标题「删除反馈 #N」、正文含「删除后不可恢复」、危险色确认按钮）；
// 确认交互经 () => Promise<boolean> 注入 store 动作（issue 冻结接缝，测试可 fake）
function confirmDelete(item: FeedbackListItem): () => Promise<boolean> {
  return () =>
    ElMessageBox.confirm(`删除后不可恢复，确定删除反馈 #${item.id} 吗？`, `删除反馈 #${item.id}`, {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
    }).then(
      () => true,
      () => false, // 取消 / 关闭均视为未确认，不发 DELETE
    )
}

async function onDelete(item: FeedbackListItem): Promise<void> {
  if (store === null) return
  const outcome = await store.remove(item, confirmDelete(item))
  applyWriteOutcome(outcome, '已删除')
  if (outcome.ok) syncDetailItem(item.id)
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
    <!-- 操作列（issue #16）：标记完成/待处理（双向，D11 成功后重拉当前页）、详情（抽屉）、删除（二次确认）。
         宽度 210：固定列合计 796 + 标题 min-width 200 → 1024px 内不横向滚动（D8/D9） -->
    <el-table-column label="操作" width="210" fixed="right">
      <template #default="{ row }">
        <el-button type="primary" link @click="onToggle(row)">
          {{ TOGGLE_ACTION_COPY[row.status as FeedbackStatus] }}
        </el-button>
        <el-button type="primary" link @click="onDetail(row)">详情</el-button>
        <el-button type="danger" link @click="onDelete(row)">删除</el-button>
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

  <!-- 详情抽屉（§2.4 F1）：由操作列「详情」打开；v-if 保证 item 非空 -->
  <FeedbackDetailDialog
    v-if="detailItem !== null"
    v-model="detailVisible"
    :item="detailItem"
    :keyword="keyword"
    @toggle="onToggle"
  />
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
