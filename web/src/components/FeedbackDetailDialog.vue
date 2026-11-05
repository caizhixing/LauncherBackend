<script setup lang="ts">
// components/FeedbackDetailDialog.vue —— 详情抽屉（§2.4 F1 / D4/D5/D10，issue #16）
// 行为 4.5（静态检查项）：deviceId/contact 全量 + 复制按钮；正文全文保留换行 + keyword 命中高亮；
// images 一期恒空数组（§2.1），不提供任何对应 UI
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { CATEGORY_LABEL } from '../api/types'
import type { FeedbackCategory, FeedbackListItem } from '../api/types'
import StatusTag from './StatusTag.vue'

const props = defineProps<{
  item: FeedbackListItem
  keyword?: string
}>()

const visible = defineModel<boolean>({ required: true })

const emit = defineEmits<{ toggle: [item: FeedbackListItem] }>()

const CATEGORY_TAG_TYPE: Record<FeedbackCategory, 'danger' | 'warning' | 'primary' | 'info'> = {
  BUG: 'danger',
  EXPERIENCE: 'warning',
  SUGGESTION: 'primary',
  OTHER: 'info',
}

// 后端 keyword 匹配前会 trim（§2.1），高亮按 trim 后关键词对齐命中语义（D10）
const trimmedKeyword = computed(() => (props.keyword ?? '').trim())

interface ContentSegment {
  text: string
  hit: boolean
}

// String.split 的字符串分隔符按字面量匹配，关键词含正则元字符也安全
const contentSegments = computed<ContentSegment[]>(() => {
  const keyword = trimmedKeyword.value
  if (keyword === '') return [{ text: props.item.content, hit: false }]
  const segments: ContentSegment[] = []
  const parts = props.item.content.split(keyword)
  parts.forEach((part, index) => {
    if (part !== '') segments.push({ text: part, hit: false })
    if (index < parts.length - 1) segments.push({ text: keyword, hit: true })
  })
  return segments
})

// deviceModel/osVersion 为可选键（null 时后端省略，§2.1）；详情合并一行展示，全缺 → —
const deviceLine = computed(() => {
  const parts = [props.item.deviceModel, props.item.osVersion].filter(
    (value): value is string => value !== undefined && value !== '',
  )
  return parts.length > 0 ? parts.join(' · ') : '—'
})

// contact 可选键：缺失或空 → —（且无复制按钮）
const contact = computed<string | null>(() => {
  const raw = props.item.contact
  return raw !== undefined && raw !== '' ? raw : null
})

// D4/D5：复制用 navigator.clipboard + 成功 toast（DECISIONS #Web 管理端技术栈选型）
async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

function copyDeviceId(): void {
  void copyText(props.item.deviceId)
}

function copyContact(): void {
  const value = contact.value
  if (value !== null) void copyText(value)
}
</script>

<template>
  <el-drawer v-model="visible" :title="`反馈详情 #${item.id}`" size="560px">
    <el-descriptions :column="1" border class="feedback-detail__descriptions">
      <el-descriptions-item label="反馈 ID">#{{ item.id }}</el-descriptions-item>
      <el-descriptions-item label="标题">{{ item.title }}</el-descriptions-item>
      <el-descriptions-item label="分类">
        <el-tag :type="CATEGORY_TAG_TYPE[item.category]" effect="plain">
          {{ CATEGORY_LABEL[item.category] }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="应用">
        <!-- §2.4：详情页 appName / appVersion 分两行 -->
        <div class="feedback-detail__two-lines">
          <div>{{ item.appName }}</div>
          <div>{{ item.appVersion }}</div>
        </div>
      </el-descriptions-item>
      <el-descriptions-item label="设备">{{ deviceLine }}</el-descriptions-item>
      <el-descriptions-item label="设备 ID">
        <span class="feedback-detail__copyable">{{ item.deviceId }}</span>
        <el-button link type="primary" @click="copyDeviceId">复制</el-button>
      </el-descriptions-item>
      <el-descriptions-item label="联系方式">
        <template v-if="contact !== null">
          <span class="feedback-detail__copyable">{{ contact }}</span>
          <el-button link type="primary" @click="copyContact">复制</el-button>
        </template>
        <template v-else>—</template>
      </el-descriptions-item>
      <el-descriptions-item label="状态">
        <!-- §2.4/§2.5：标签 + 快捷操作；与行内开关同一动作（D11 成功后重拉并换绑新行对象） -->
        <StatusTag :status="item.status" />
        <el-button link type="primary" @click="emit('toggle', item)">
          {{ item.status === 0 ? '标记完成' : '标记待处理' }}
        </el-button>
      </el-descriptions-item>
      <el-descriptions-item label="创建时间">{{ item.createdAt }}</el-descriptions-item>
      <el-descriptions-item label="更新时间">{{ item.updatedAt }}</el-descriptions-item>
      <el-descriptions-item label="正文">
        <!-- §2.4：全文保留换行（pre-line）+ keyword 命中高亮（D10）；images 无任何 UI（§2.1） -->
        <p class="feedback-detail__content">
          <template v-for="(segment, index) in contentSegments" :key="index">
            <mark v-if="segment.hit">{{ segment.text }}</mark>
            <template v-else>{{ segment.text }}</template>
          </template>
        </p>
      </el-descriptions-item>
    </el-descriptions>
  </el-drawer>
</template>

<style scoped>
.feedback-detail__descriptions {
  width: 100%;
}

.feedback-detail__two-lines {
  display: flex;
  flex-direction: column;
}

.feedback-detail__copyable {
  margin-right: 8px;
  word-break: break-all;
}

.feedback-detail__content {
  margin: 0;
  white-space: pre-line;
  word-break: break-word;
}
</style>
