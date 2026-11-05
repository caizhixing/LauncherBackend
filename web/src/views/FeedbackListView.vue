<script setup lang="ts">
// views/FeedbackListView.vue —— 列表页读路径（issue #15 / PRD §2.4/§2.5 任务 3）
// URL 是筛选状态唯一事实源（§2.3）：挂载/后退时 URL → 状态；筛选/页码变化时状态 → URL（replace，不堆历史）
import { computed, onBeforeUnmount, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { createFeedbackApi } from '../api/feedback'
import FeedbackFilters from '../components/FeedbackFilters.vue'
import FeedbackTable from '../components/FeedbackTable.vue'
// 共享 HttpClient：router.ts 的 guard 装配处已注册 onUnauthorized（§2.3），此处复用不重复装配。
// router ↔ 视图存在循环引用，但 http 仅在 setup 运行期取值（ESM live binding），求值时模块已初始化完毕
import { http } from '../router'
import { useFeedbackList } from '../stores/useFeedbackList'
import { PAGE_SIZE_OPTIONS, decodeQuery, encodeQuery } from '../url/feedbackQuery'

const route = useRoute()
const router = useRouter()
const store = useFeedbackList(createFeedbackApi(http))
const state = store.state

const pageSizeOptions: number[] = [...PAGE_SIZE_OPTIONS]

// vue-router LocationQuery 值可能是 string | null | 数组：取首个非空串（与后端「同名参数取首个」语义一致）
function routeParams(query: typeof route.query): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [key, value] of Object.entries(query)) {
    const first = Array.isArray(value) ? value[0] : value
    if (typeof first === 'string' && first !== '') out[key] = first
  }
  return out
}

function sameParams(a: Record<string, string>, b: Record<string, string>): boolean {
  const keys = new Set([...Object.keys(a), ...Object.keys(b)])
  for (const key of keys) {
    if (a[key] !== b[key]) return false
  }
  return true
}

// 挂载：URL → 状态（刷新 / 登录 returnUrl 回跳恢复筛选，§2.5），立即拉取
onMounted(() => {
  store.applyQuery(decodeQuery(routeParams(route.query)))
})

// 状态 → URL：筛选与页码同步进 URL；已是当前 URL 则跳过，避免与下行 watcher 互相触发
watch(
  () => encodeQuery(state.query),
  (params) => {
    if (!sameParams(params, routeParams(route.query))) {
      void router.replace({ query: params })
    }
  },
)

// URL → 状态：浏览器后退/前进等非本页发起的 URL 变化 → 恢复筛选并立即拉取
watch(
  () => route.fullPath,
  () => {
    const params = routeParams(route.query)
    if (!sameParams(params, encodeQuery(state.query))) {
      store.applyQuery(decodeQuery(params))
    }
  },
)

onBeforeUnmount(() => {
  store.dispose()
})

// 首屏骨架 vs 表格 loading 分界（§2.5：翻页/筛选不闪白）
const firstLoading = computed(() => state.loading && !state.loaded)
</script>

<template>
  <main class="feedback-list-view">
    <h1 class="feedback-list-view__title">反馈列表</h1>
    <FeedbackFilters :query="state.query" @change="store.setFilter" @clear="store.clearFilters" />
    <el-skeleton v-if="firstLoading" class="feedback-list-view__skeleton" :rows="6" animated />
    <template v-else>
      <FeedbackTable
        :list="state.list"
        :loading="state.loading"
        :error="state.error"
        :empty-text="store.emptyStateText()"
        :show-clear-empty="store.hasActiveFilters()"
        @retry="store.retry"
        @clear="store.clearFilters"
      />
      <el-pagination
        v-if="state.loaded && state.error === null"
        class="feedback-list-view__pagination"
        :total="state.total"
        :current-page="state.query.page"
        :page-size="state.query.pageSize"
        :page-sizes="pageSizeOptions"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="store.setPage"
        @size-change="store.setPageSize"
      />
    </template>
  </main>
</template>

<style scoped>
.feedback-list-view {
  padding: 16px;
}

.feedback-list-view__title {
  margin: 0 0 12px;
  font-size: 20px;
}

.feedback-list-view__pagination {
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
