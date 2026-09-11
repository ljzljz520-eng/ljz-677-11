<template>
  <div class="space-y-6">
    <!-- 页面标题 -->
    <div class="card">
      <h1 class="text-2xl font-bold text-gray-800 mb-2">医保清单导入</h1>
      <p class="text-gray-500">上传 Excel 后后台分批解析，可凭任务编号随时查看进度，支持 5 万行大文件流式处理</p>
    </div>

    <!-- 上传区域 -->
    <div class="card">
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-lg font-semibold text-gray-700">上传文件</h2>
        <el-button type="primary" link @click="downloadTemplate">
          <svg class="w-4 h-4 mr-1" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
              d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
          </svg>
          下载导入模板
        </el-button>
      </div>

      <el-upload
        ref="uploadRef"
        class="upload-area"
        drag
        :auto-upload="false"
        :limit="1"
        :on-change="handleFileChange"
        :on-exceed="handleExceed"
        accept=".xlsx,.xls"
      >
        <div class="upload-content py-8">
          <div class="upload-icon mb-4">
            <svg class="w-16 h-16 mx-auto text-gray-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
                d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
            </svg>
          </div>
          <p class="text-gray-600 mb-2">将Excel文件拖到此处，或<em class="text-blue-500 not-italic">点击上传</em></p>
          <p class="text-gray-400 text-sm">支持 .xlsx、.xls 格式，单个文件最大100MB，最多5万行</p>
        </div>
      </el-upload>

      <!-- 已选文件 -->
      <div v-if="selectedFile" class="mt-4 p-4 bg-gray-50 rounded-lg flex items-center justify-between">
        <div class="flex items-center">
          <div class="w-10 h-10 bg-green-100 rounded-lg flex items-center justify-center mr-3">
            <svg class="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
            </svg>
          </div>
          <div>
            <p class="text-gray-700 font-medium">{{ selectedFile.name }}</p>
            <p class="text-gray-400 text-sm">{{ formatFileSize(selectedFile.size) }}</p>
          </div>
        </div>
        <el-button type="danger" link @click="removeFile" :disabled="uploading">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </el-button>
      </div>

      <!-- 操作按钮 -->
      <div class="mt-6 flex justify-between items-center">
        <!-- 凭任务编号继续查看 -->
        <div class="flex items-center gap-2">
          <el-input
            v-model="resumeTaskNo"
            placeholder="输入任务编号继续查看"
            clearable
            class="!w-72"
            @keyup.enter="resumeTask"
          />
          <el-button @click="resumeTask" :disabled="!resumeTaskNo.trim()">查询任务</el-button>
        </div>

        <el-button
          type="primary"
          size="large"
          :loading="creating"
          :disabled="!selectedFile || running"
          @click="handleCreateTask"
        >
          {{ creating ? '上传中...' : '开始导入' }}
        </el-button>
      </div>
    </div>

    <!-- 任务进度 -->
    <div v-if="progress" class="card">
      <div class="flex items-center justify-between mb-4">
        <div class="flex items-center">
          <div :class="['w-10 h-10 rounded-full flex items-center justify-center mr-3', statusIconBg]">
            <svg v-if="running" class="w-6 h-6 animate-spin text-blue-600" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
              <path class="opacity-75" fill="currentColor"
                d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
            </svg>
            <svg v-else-if="progress.status === 3" class="w-6 h-6 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
            </svg>
            <svg v-else class="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
            </svg>
          </div>
          <div>
            <h3 class="text-lg font-semibold text-gray-800">{{ statusTitle }}</h3>
            <p class="text-gray-500 text-sm">
              任务编号：<span class="font-mono">{{ progress.taskNo }}</span>
              <el-button type="primary" link size="small" @click="copyTaskNo">复制</el-button>
            </p>
          </div>
        </div>
        <el-tag :type="statusTagType" size="large">{{ statusText }}</el-tag>
      </div>

      <!-- 进度条：xlsx 已知总行数显示百分比；xls 显示阶段动画 -->
      <el-progress
        :percentage="displayPercent"
        :status="progress.status === 3 ? 'exception' : (progress.status === 1 ? 'success' : undefined)"
        :indeterminate="running && progress.totalCount === 0"
        :duration="3"
      />

      <!-- 实时指标 -->
      <div class="grid grid-cols-2 md:grid-cols-5 gap-4 mt-6">
        <div class="bg-gray-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-gray-700">{{ progress.readCount }}</p>
          <p class="text-gray-500 text-sm mt-1">已读取（行）</p>
        </div>
        <div class="bg-blue-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-blue-600">{{ progress.validatedCount }}</p>
          <p class="text-gray-500 text-sm mt-1">已校验（行）</p>
        </div>
        <div class="bg-green-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-green-600">{{ progress.successCount }}</p>
          <p class="text-gray-500 text-sm mt-1">成功（行）</p>
        </div>
        <div class="bg-red-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-red-600">{{ progress.failCount }}</p>
          <p class="text-gray-500 text-sm mt-1">失败（行）</p>
        </div>
        <div class="bg-purple-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-purple-600">{{ etaText }}</p>
          <p class="text-gray-500 text-sm mt-1">预计完成时间</p>
        </div>
      </div>

      <!-- 辅助信息 -->
      <div class="mt-4 flex flex-wrap gap-x-6 gap-y-1 text-sm text-gray-500">
        <span v-if="progress.totalCount > 0">总行数（预估）：{{ progress.totalCount }}</span>
        <span v-else-if="running">总行数统计中（.xls 无法预估，将按实际读取展示）…</span>
        <span v-if="progress.rowsPerSecond">速率：{{ progress.rowsPerSecond.toFixed(0) }} 行/秒</span>
        <span>阶段：{{ stageText }}</span>
      </div>

      <!-- 任务级错误 -->
      <el-alert
        v-if="progress.status === 3"
        class="mt-4"
        type="error"
        :closable="false"
        :title="progress.message || '任务执行失败'"
        show-icon
      />

      <!-- 完成后的操作 -->
      <div v-if="!running" class="mt-6 flex justify-end space-x-3">
        <el-button v-if="progress.failCount > 0" type="warning" plain @click="exportErrors">
          导出失败行 ({{ progress.failCount }})
        </el-button>
        <el-button v-if="progress.status !== 3" @click="goToDetail">查看数据明细</el-button>
        <el-button type="primary" @click="resetView">继续导入新文件</el-button>
      </div>
    </div>

    <!-- 失败行明细 -->
    <div v-if="progress && progress.failCount > 0 && !running" class="card">
      <div class="flex items-center justify-between mb-4">
        <h2 class="text-lg font-semibold text-gray-700">失败行明细</h2>
        <el-button type="primary" link @click="exportErrors">导出为Excel</el-button>
      </div>
      <el-table v-loading="errorLoading" :data="errorRows" stripe max-height="400">
        <el-table-column prop="rowIndex" label="行号" width="90" />
        <el-table-column prop="dataCode" label="数据编号" width="140" />
        <el-table-column prop="name" label="姓名" width="110" />
        <el-table-column prop="amount" label="金额" width="110" />
        <el-table-column prop="errorMsg" label="错误原因" min-width="220" show-overflow-tooltip />
      </el-table>
      <div class="mt-4 flex justify-end">
        <el-pagination
          v-model:current-page="errorPage.pageNum"
          v-model:page-size="errorPage.pageSize"
          :total="progress.failCount"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @size-change="fetchErrors"
          @current-change="fetchErrors"
        />
      </div>
    </div>

    <!-- 使用说明 -->
    <div class="card">
      <h2 class="text-lg font-semibold text-gray-700 mb-4">使用说明</h2>
      <div class="space-y-3 text-gray-600">
        <div class="flex items-start">
          <span class="flex-shrink-0 w-6 h-6 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center text-sm font-medium mr-3">1</span>
          <p>上传文件后立即得到任务编号，后台流式分批解析（每1000行入库一批），页面可关闭</p>
        </div>
        <div class="flex items-start">
          <span class="flex-shrink-0 w-6 h-6 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center text-sm font-medium mr-3">2</span>
          <p>重新打开页面后，粘贴任务编号即可继续查看进度与结果；处理中任务在服务重启后会自动恢复</p>
        </div>
        <div class="flex items-start">
          <span class="flex-shrink-0 w-6 h-6 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center text-sm font-medium mr-3">3</span>
          <p>实时展示已读取、已校验、成功、失败行数及预计完成时间；失败行可分页查看并导出修正</p>
        </div>
        <div class="flex items-start">
          <span class="flex-shrink-0 w-6 h-6 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center text-sm font-medium mr-3">4</span>
          <p>解析采用 SAX 流式读取，内存中始终只有一批数据，5万行不会一次性载入内存</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { excelApi } from '@/api'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const TASK_STORAGE_KEY = 'excel_import_task_no'
const POLL_INTERVAL = 1500

const uploadRef = ref(null)
const selectedFile = ref(null)
const creating = ref(false)
const uploading = ref(false)

const progress = ref(null)
const resumeTaskNo = ref('')

const errorRows = ref([])
const errorLoading = ref(false)
const errorPage = reactive({ pageNum: 1, pageSize: 20 })

let pollTimer = null

const running = computed(() => progress.value && progress.value.status === 0)

const displayPercent = computed(() => {
  if (!progress.value) return 0
  if (progress.value.status === 1) return 100
  return progress.value.percent || 0
})

const statusTagType = computed(() => {
  const s = progress.value?.status
  if (s === 0) return 'primary'
  if (s === 1) return 'success'
  if (s === 2) return 'warning'
  return 'danger'
})

const statusText = computed(() => ({ 0: '处理中', 1: '已完成', 2: '完成（有失败行）', 3: '失败/中断' }[progress.value?.status]))
const statusTitle = computed(() => ({ 0: '正在导入', 1: '导入完成', 2: '导入完成（部分行失败）', 3: '导入失败' }[progress.value?.status]))
const statusIconBg = computed(() => {
  const s = progress.value?.status
  if (s === 0) return 'bg-blue-100'
  if (s === 3) return 'bg-red-100'
  return s === 2 ? 'bg-yellow-100' : 'bg-green-100'
})
const stageText = computed(() => ({
  PENDING: '排队中',
  COUNTING: '统计总行数',
  PARSING: '读取并校验',
  SAVING: '入库中',
  DONE: '已完成',
  FAILED: '已失败'
}[progress.value?.stage] || progress.value?.stage || '-'))

const etaText = computed(() => {
  const p = progress.value
  if (!p) return '-'
  if (p.status === 1) return '已完成'
  if (p.status === 3) return '-'
  if (p.etaSeconds == null) return '估算中'
  if (p.etaSeconds <= 0) return '即将完成'
  if (p.etaSeconds < 60) return `约 ${p.etaSeconds} 秒`
  const m = Math.floor(p.etaSeconds / 60)
  const s = p.etaSeconds % 60
  return s > 0 ? `约 ${m}分${s}秒` : `约 ${m} 分钟`
})

const formatFileSize = (bytes) => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

const handleFileChange = (file) => {
  selectedFile.value = file.raw
}

const handleExceed = () => {
  ElMessage.warning('只能上传一个文件')
}

const removeFile = () => {
  selectedFile.value = null
  uploadRef.value?.clearFiles()
}

// ---------------- 任务创建 + 轮询 ----------------

const handleCreateTask = async () => {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  creating.value = true
  uploading.value = true
  try {
    const res = await excelApi.createTask(selectedFile.value)
    const taskNo = res.data.taskNo
    localStorage.setItem(TASK_STORAGE_KEY, taskNo)
    ElMessage.success('任务已创建，开始后台解析')
    await attachTask(taskNo)
  } catch (e) {
    // 拦截器已提示
  } finally {
    creating.value = false
    uploading.value = false
  }
}

/** 绑定任务并立即查询 + 启动轮询 */
const attachTask = async (taskNo) => {
  resumeTaskNo.value = taskNo
  stopPolling()
  await fetchProgress(taskNo)
  if (progress.value && progress.value.status === 0) {
    startPolling(taskNo)
  } else if (progress.value && progress.value.failCount > 0) {
    fetchErrors()
  }
}

const resumeTask = async () => {
  const taskNo = resumeTaskNo.value.trim()
  if (!taskNo) return
  localStorage.setItem(TASK_STORAGE_KEY, taskNo)
  await attachTask(taskNo)
}

const fetchProgress = async (taskNo) => {
  try {
    const res = await excelApi.getTaskProgress(taskNo)
    progress.value = res.data
    if (res.data.status !== 0) {
      stopPolling()
      if (res.data.failCount > 0) {
        errorPage.pageNum = 1
        fetchErrors()
      }
    }
  } catch (e) {
    stopPolling()
  }
}

const startPolling = (taskNo) => {
  stopPolling()
  pollTimer = setInterval(() => fetchProgress(taskNo), POLL_INTERVAL)
}

const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// ---------------- 失败行 ----------------

const fetchErrors = async () => {
  if (!progress.value) return
  errorLoading.value = true
  try {
    const res = await excelApi.getTaskErrors(progress.value.taskNo, {
      pageNum: errorPage.pageNum,
      pageSize: errorPage.pageSize
    })
    errorRows.value = res.data.records || []
  } catch (e) {
    // ignore
  } finally {
    errorLoading.value = false
  }
}

const exportErrors = () => {
  const token = userStore.token
  const url = excelApi.exportTaskErrorsUrl(progress.value.taskNo)
  window.open(`${url}?token=${token}`, '_blank')
}

const copyTaskNo = async () => {
  try {
    await navigator.clipboard.writeText(progress.value.taskNo)
    ElMessage.success('任务编号已复制')
  } catch (e) {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

const goToDetail = () => {
  if (progress.value?.taskNo) {
    router.push(`/data/${progress.value.taskNo}`)
  }
}

const resetView = () => {
  stopPolling()
  progress.value = null
  errorRows.value = []
  selectedFile.value = null
  resumeTaskNo.value = ''
  uploadRef.value?.clearFiles()
  localStorage.removeItem(TASK_STORAGE_KEY)
}

const downloadTemplate = () => {
  const token = userStore.token
  window.open(`${excelApi.downloadTemplate()}?token=${token}`, '_blank')
}

onMounted(async () => {
  // URL 带任务号优先，其次 localStorage —— 页面重开自动恢复
  const taskNo = route.query.task || localStorage.getItem(TASK_STORAGE_KEY)
  if (taskNo) {
    resumeTaskNo.value = taskNo
    await attachTask(taskNo)
  }
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style scoped>
.upload-area :deep(.el-upload-dragger) {
  @apply border-2 border-dashed border-gray-200 rounded-xl transition-all duration-200;
}

.upload-area :deep(.el-upload-dragger:hover) {
  @apply border-blue-400 bg-blue-50;
}

.upload-area :deep(.el-upload-dragger.is-dragover) {
  @apply border-blue-500 bg-blue-100;
}
</style>
