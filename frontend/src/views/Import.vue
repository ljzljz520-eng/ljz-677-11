<template>
  <div class="space-y-6">
    <!-- 页面标题 -->
    <div class="card">
      <h1 class="text-2xl font-bold text-gray-800 mb-2">数据导入</h1>
      <p class="text-gray-500">上传Excel文件，后台分批解析导入，可随时凭任务编号查看进度</p>
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
        :before-upload="beforeUpload"
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
        <el-button type="danger" link @click="removeFile">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </el-button>
      </div>

      <!-- 上传按钮 -->
      <div class="mt-6 flex justify-end">
        <el-button
          type="primary"
          size="large"
          :loading="uploading"
          :disabled="!selectedFile"
          @click="handleUpload"
        >
          <svg v-if="!uploading" class="w-5 h-5 mr-2" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
              d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
          </svg>
          {{ uploading ? '上传中...' : '开始导入' }}
        </el-button>
      </div>

      <!-- 文件上传进度（HTTP上传阶段） -->
      <div v-if="uploading" class="mt-4">
        <el-progress :percentage="uploadProgress" :status="uploadProgress === 100 ? 'success' : ''" />
        <p class="text-sm text-gray-500 mt-2 text-center">正在上传文件...</p>
      </div>
    </div>

    <!-- 导入任务进度 -->
    <div v-if="taskInfo" class="card">
      <div class="flex items-center justify-between mb-4">
        <div class="flex items-center">
          <div :class="[
            'w-10 h-10 rounded-full flex items-center justify-center mr-3',
            statusIconBg
          ]">
            <!-- 完成 -->
            <svg v-if="taskInfo.status === 'COMPLETED'" class="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
            </svg>
            <!-- 部分失败 -->
            <svg v-else-if="taskInfo.status === 'COMPLETED_WITH_ERRORS'" class="w-6 h-6 text-yellow-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2"
                d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
            </svg>
            <!-- 失败 -->
            <svg v-else-if="taskInfo.status === 'FAILED'" class="w-6 h-6 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
            <!-- 进行中 -->
            <svg v-else class="w-6 h-6 text-blue-600 animate-spin" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4" />
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
            </svg>
          </div>
          <div>
            <div class="flex items-center space-x-2">
              <h3 class="text-lg font-semibold text-gray-800">导入任务</h3>
              <el-tag :type="statusTagType" size="small">{{ statusText }}</el-tag>
            </div>
            <div class="flex items-center text-gray-500 text-sm mt-0.5">
              <span>任务编号：</span>
              <span class="font-mono">{{ taskInfo.taskId }}</span>
              <el-button type="primary" link size="small" class="ml-1" @click="copyTaskId">复制</el-button>
            </div>
          </div>
        </div>
        <div class="text-right text-sm text-gray-400">
          <p>{{ taskInfo.fileName }}</p>
          <p v-if="taskInfo.totalEstimate">预估总行数：{{ taskInfo.totalEstimate }}</p>
        </div>
      </div>

      <!-- 解析进度条 -->
      <el-progress
        :percentage="displayPercent"
        :status="progressBarStatus"
        :striped="isRunning"
        :striped-flow="isRunning"
        :format="progressFormat"
        class="mb-4"
      />

      <!-- 实时统计 -->
      <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
        <div class="bg-blue-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-blue-600">{{ taskInfo.readCount ?? 0 }}</p>
          <p class="text-gray-500 text-sm">已读取行数</p>
        </div>
        <div class="bg-indigo-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-indigo-600">{{ taskInfo.validatedCount ?? 0 }}</p>
          <p class="text-gray-500 text-sm">已校验行数</p>
        </div>
        <div class="bg-red-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-red-600">{{ taskInfo.failCount ?? 0 }}</p>
          <p class="text-gray-500 text-sm">失败行数</p>
        </div>
        <div class="bg-amber-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-amber-600">{{ isRunning ? formatEta(taskInfo.etaSeconds) : '已结束' }}</p>
          <p class="text-gray-500 text-sm">预计剩余时间</p>
        </div>
      </div>

      <div class="flex items-center justify-between text-sm text-gray-400 mb-2">
        <span>{{ taskInfo.message }}</span>
        <span>已耗时 {{ formatDuration(taskInfo.elapsedSeconds) }}<template v-if="taskInfo.successCount != null"> · 已入库 {{ taskInfo.successCount }} 行</template></span>
      </div>

      <!-- 错误预览 -->
      <div v-if="isFinished && taskInfo.errorPreview && taskInfo.errorPreview.length > 0" class="mt-4">
        <h4 class="font-medium text-gray-700 mb-3">
          错误数据预览
          <span v-if="taskInfo.failCount > taskInfo.errorPreview.length" class="text-sm text-gray-400 font-normal">
            （仅显示前 {{ taskInfo.errorPreview.length }} 条，共 {{ taskInfo.failCount }} 条）
          </span>
        </h4>
        <el-table :data="taskInfo.errorPreview" stripe max-height="300" size="small">
          <el-table-column prop="rowIndex" label="行号" width="80" />
          <el-table-column prop="dataCode" label="数据编号" width="120" />
          <el-table-column prop="name" label="姓名" width="100" />
          <el-table-column prop="errorMsg" label="错误原因" />
        </el-table>
      </div>

      <!-- 完成后的操作 -->
      <div v-if="isFinished" class="mt-6 flex justify-end space-x-3">
        <el-button @click="resetImport">继续导入</el-button>
        <el-button v-if="taskInfo.status !== 'FAILED'" type="primary" @click="goToDetail">查看详情</el-button>
      </div>
    </div>

    <!-- 凭任务编号查询 -->
    <div class="card">
      <h2 class="text-lg font-semibold text-gray-700 mb-2">凭任务编号查看进度</h2>
      <p class="text-gray-400 text-sm mb-4">页面关闭或刷新后，输入任务编号即可继续查看导入进度</p>
      <div class="flex space-x-3">
        <el-input
          v-model="queryTaskId"
          placeholder="请输入任务编号，如 3f8a2b1c4d5e..."
          clearable
          class="max-w-md font-mono"
          @keyup.enter="handleQueryTask"
        />
        <el-button type="primary" :loading="querying" @click="handleQueryTask">查询</el-button>
      </div>
    </div>

    <!-- 使用说明 -->
    <div class="card">
      <h2 class="text-lg font-semibold text-gray-700 mb-4">使用说明</h2>
      <div class="space-y-3 text-gray-600">
        <div class="flex items-start">
          <span class="flex-shrink-0 w-6 h-6 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center text-sm font-medium mr-3">1</span>
          <p>下载导入模板，按照模板格式填写数据</p>
        </div>
        <div class="flex items-start">
          <span class="flex-shrink-0 w-6 h-6 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center text-sm font-medium mr-3">2</span>
          <p>上传文件后系统立即返回任务编号，后台分批解析导入，页面实时展示已读取、已校验、失败行数和预计完成时间</p>
        </div>
        <div class="flex items-start">
          <span class="flex-shrink-0 w-6 h-6 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center text-sm font-medium mr-3">3</span>
          <p>导入过程中可以关闭页面，重新打开后凭任务编号继续查看进度</p>
        </div>
        <div class="flex items-start">
          <span class="flex-shrink-0 w-6 h-6 bg-blue-100 text-blue-600 rounded-full flex items-center justify-center text-sm font-medium mr-3">4</span>
          <p>支持最多5万条数据导入，系统采用流式解析，不会将整表加载到内存</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { excelApi } from '@/api'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

/** 未完成任务编号的本地存储key，用于页面重开后恢复进度查看 */
const TASK_STORAGE_KEY = 'excel_import_current_task'
const POLL_INTERVAL = 1000

const uploadRef = ref(null)
const selectedFile = ref(null)
const uploading = ref(false)
const uploadProgress = ref(0)

const taskId = ref('')
const taskInfo = ref(null)
const queryTaskId = ref('')
const querying = ref(false)
let pollTimer = null

const FINISHED_STATUSES = ['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED']

const isFinishedStatus = (status) => FINISHED_STATUSES.includes(status)

const isRunning = computed(() =>
  taskInfo.value && (taskInfo.value.status === 'PENDING' || taskInfo.value.status === 'PROCESSING')
)
const isFinished = computed(() => taskInfo.value && isFinishedStatus(taskInfo.value.status))

const statusText = computed(() => {
  const map = {
    PENDING: '排队中',
    PROCESSING: '导入中',
    COMPLETED: '已完成',
    COMPLETED_WITH_ERRORS: '部分失败',
    FAILED: '失败'
  }
  return map[taskInfo.value?.status] || '未知'
})

const statusTagType = computed(() => {
  const map = {
    PENDING: 'info',
    PROCESSING: 'primary',
    COMPLETED: 'success',
    COMPLETED_WITH_ERRORS: 'warning',
    FAILED: 'danger'
  }
  return map[taskInfo.value?.status] || 'info'
})

const statusIconBg = computed(() => {
  const map = {
    PENDING: 'bg-blue-100',
    PROCESSING: 'bg-blue-100',
    COMPLETED: 'bg-green-100',
    COMPLETED_WITH_ERRORS: 'bg-yellow-100',
    FAILED: 'bg-red-100'
  }
  return map[taskInfo.value?.status] || 'bg-blue-100'
})

/** 总行数未知时进度条显示满格流动动画 */
const displayPercent = computed(() => {
  if (!taskInfo.value) return 0
  if (taskInfo.value.percent != null) return taskInfo.value.percent
  return isRunning.value ? 100 : 0
})

const progressBarStatus = computed(() => {
  const map = {
    COMPLETED: 'success',
    COMPLETED_WITH_ERRORS: 'warning',
    FAILED: 'exception'
  }
  return map[taskInfo.value?.status] || ''
})

const progressFormat = () => {
  if (!taskInfo.value) return ''
  if (taskInfo.value.percent != null) return `${taskInfo.value.percent}%`
  return isRunning.value ? '解析中…' : ''
}

const formatFileSize = (bytes) => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

const formatDuration = (seconds) => {
  if (seconds == null) return '--'
  const s = Math.floor(seconds)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (h > 0) return `${h}小时${m}分`
  if (m > 0) return `${m}分${sec}秒`
  return `${sec}秒`
}

const formatEta = (seconds) => {
  if (seconds == null) return '估算中…'
  if (seconds <= 0) return '即将完成'
  return '约 ' + formatDuration(seconds)
}

const handleFileChange = (file) => {
  selectedFile.value = file.raw
}

const handleExceed = () => {
  ElMessage.warning('只能上传一个文件')
}

const beforeUpload = (file) => {
  const isExcel = file.name.endsWith('.xlsx') || file.name.endsWith('.xls')
  if (!isExcel) {
    ElMessage.error('只能上传Excel文件')
    return false
  }

  const isLt100M = file.size / 1024 / 1024 < 100
  if (!isLt100M) {
    ElMessage.error('文件大小不能超过100MB')
    return false
  }

  return true
}

const removeFile = () => {
  selectedFile.value = null
  uploadRef.value?.clearFiles()
}

const handleUpload = async () => {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }

  uploading.value = true
  uploadProgress.value = 0

  try {
    const res = await excelApi.importAsync(selectedFile.value, (progressEvent) => {
      if (progressEvent.lengthComputable) {
        uploadProgress.value = Math.round((progressEvent.loaded * 100) / progressEvent.total)
      }
    })

    uploadProgress.value = 100
    taskId.value = res.data.taskId
    // 保存任务编号，页面重开后自动恢复进度查看
    localStorage.setItem(TASK_STORAGE_KEY, taskId.value)
    ElMessage.success('文件上传完成，后台开始解析导入')
    startPolling()
  } catch (error) {
    // 错误已在拦截器中处理
  } finally {
    uploading.value = false
  }
}

const fetchProgress = async (silent) => {
  const res = await excelApi.getImportProgress(taskId.value, silent)
  taskInfo.value = res.data
  return res.data
}

const startPolling = () => {
  stopPolling()
  const tick = async () => {
    try {
      const info = await fetchProgress(true)
      if (isFinishedStatus(info.status)) {
        stopPolling()
        localStorage.removeItem(TASK_STORAGE_KEY)
        notifyFinished(info)
        return
      }
    } catch (error) {
      // 任务不存在或查询异常：停止轮询并清理
      stopPolling()
      localStorage.removeItem(TASK_STORAGE_KEY)
      taskInfo.value = null
      ElMessage.warning('任务进度查询失败，可能任务不存在或已被清理')
      return
    }
    pollTimer = setTimeout(tick, POLL_INTERVAL)
  }
  tick()
}

const stopPolling = () => {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

const notifyFinished = (info) => {
  if (info.status === 'COMPLETED') {
    ElMessage.success(`导入完成，共 ${info.successCount} 条`)
  } else if (info.status === 'COMPLETED_WITH_ERRORS') {
    ElMessage.warning(`导入完成，${info.failCount} 行失败`)
  } else {
    ElMessage.error(info.message || '导入失败')
  }
}

const handleQueryTask = async () => {
  const id = queryTaskId.value.trim()
  if (!id) {
    ElMessage.warning('请输入任务编号')
    return
  }
  querying.value = true
  try {
    taskId.value = id
    const info = await fetchProgress(false)
    if (isFinishedStatus(info.status)) {
      // 已结束的任务无需轮询，也不占用恢复位
      localStorage.removeItem(TASK_STORAGE_KEY)
    } else {
      localStorage.setItem(TASK_STORAGE_KEY, id)
      startPolling()
    }
  } catch (error) {
    taskInfo.value = null
  } finally {
    querying.value = false
  }
}

const copyTaskId = async () => {
  try {
    await navigator.clipboard.writeText(taskId.value)
    ElMessage.success('任务编号已复制')
  } catch {
    ElMessage.info(`任务编号：${taskId.value}`)
  }
}

const downloadTemplate = () => {
  const token = userStore.token
  const url = excelApi.downloadTemplate()
  window.open(`${url}?token=${token}`, '_blank')
}

const resetImport = () => {
  selectedFile.value = null
  uploadRef.value?.clearFiles()
  uploadProgress.value = 0
  taskInfo.value = null
  taskId.value = ''
  queryTaskId.value = ''
}

const goToDetail = () => {
  if (taskInfo.value?.taskId) {
    router.push(`/data/${taskInfo.value.taskId}`)
  }
}

onMounted(() => {
  // 页面重新打开时，恢复未完成任务的进度查看
  const savedTaskId = localStorage.getItem(TASK_STORAGE_KEY)
  if (savedTaskId) {
    taskId.value = savedTaskId
    startPolling()
  }
})

onBeforeUnmount(() => {
  // 仅停止前端轮询，后台任务继续执行，重开页面可凭任务编号恢复
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
