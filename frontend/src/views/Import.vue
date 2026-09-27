<template>
  <div class="space-y-6">
    <!-- 页面标题 -->
    <div class="card">
      <h1 class="text-2xl font-bold text-gray-800 mb-2">医保清单导入台</h1>
      <p class="text-gray-500">分批流式解析，支持5万行；可凭任务编号随时查看进度，关闭页面不影响后台导入</p>
    </div>

    <!-- 上传区域 -->
    <div class="card">
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-lg font-semibold text-gray-700">上传文件</h2>
        <el-button type="primary" link @click="downloadTemplate">
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
          <p class="text-gray-600 mb-2">将医保清单Excel拖到此处，或<em class="text-blue-500 not-italic">点击上传</em></p>
          <p class="text-gray-400 text-sm">支持 .xlsx、.xls 格式，建议不超过5万行</p>
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

      <div class="mt-6 flex justify-end">
        <el-button
          type="primary"
          size="large"
          :loading="uploading"
          :disabled="!selectedFile || running"
          @click="handleUpload"
        >
          {{ uploading ? '上传中...' : (running ? '后台导入进行中' : '开始导入') }}
        </el-button>
      </div>

      <!-- 上传阶段进度条 -->
      <div v-if="uploading" class="mt-4">
        <el-progress :percentage="uploadProgress" status="success" />
        <p class="text-sm text-gray-500 mt-2 text-center">正在上传文件到服务器...</p>
      </div>
    </div>

    <!-- 凭任务编号继续查看 -->
    <div class="card">
      <h2 class="text-lg font-semibold text-gray-700 mb-3">任务编号查询</h2>
      <p class="text-gray-500 text-sm mb-4">关闭或刷新页面后，输入任务编号即可继续查看导入进度与结果</p>
      <div class="flex gap-3">
        <el-input
          v-model="resumeBatchNo"
          placeholder="请输入32位任务编号"
          clearable
          class="!w-[420px] font-mono"
          @keyup.enter="resumeTask"
        />
        <el-button type="primary" :loading="loadingProgress" @click="resumeTask">继续查看</el-button>
      </div>
    </div>

    <!-- 导入进度 -->
    <div v-if="progress" class="card">
      <div class="flex items-center justify-between mb-4 flex-wrap gap-3">
        <div class="flex items-center">
          <div :class="['w-10 h-10 rounded-full flex items-center justify-center mr-3', statusIconBg]">
            <svg v-if="progress.status === 1" class="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
            </svg>
            <svg v-else-if="progress.status === 2" class="w-6 h-6 text-yellow-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
            </svg>
            <svg v-else-if="progress.status === 3" class="w-6 h-6 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
            <svg v-else class="w-6 h-6 text-blue-600 animate-spin" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
            </svg>
          </div>
          <div>
            <h3 class="text-lg font-semibold text-gray-800">{{ statusTitle }}</h3>
            <p class="text-gray-400 text-sm">
              任务编号：<span class="font-mono">{{ progress.batchNo }}</span>
              <el-button link type="primary" size="small" @click="copyBatchNo">复制</el-button>
            </p>
          </div>
        </div>
        <el-tag :type="statusTagType" size="large">{{ phaseText }}</el-tag>
      </div>

      <!-- 进度条 -->
      <div class="mb-2 flex justify-between text-sm text-gray-500">
        <span>{{ progress.progress >= 0 ? `整体进度 ${progress.progress}%` : '正在统计总行数...' }}</span>
        <span v-if="running && progress.etaSeconds !== null && progress.etaSeconds !== undefined">
          预计剩余 {{ formatEta(progress.etaSeconds) }}
          <span v-if="progress.rowsPerSecond">（约 {{ Math.round(progress.rowsPerSecond) }} 行/秒）</span>
        </span>
      </div>
      <el-progress
        :percentage="running && progress.progress < 0 ? 100 : Math.max(0, progress.progress)"
        :status="progress.status === 1 ? 'success' : progress.status === 3 ? 'exception' : ''"
        :indeterminate="running && progress.progress < 0"
        :duration="2"
        :stroke-width="14"
      />

      <!-- 实时统计 -->
      <div class="grid grid-cols-2 md:grid-cols-4 gap-4 mt-6">
        <div class="bg-blue-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-blue-600">{{ progress.totalCount || '—' }}</p>
          <p class="text-gray-500 text-sm mt-1">总行数</p>
        </div>
        <div class="bg-indigo-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-indigo-600">{{ progress.readCount }}</p>
          <p class="text-gray-500 text-sm mt-1">已读取</p>
        </div>
        <div class="bg-green-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-green-600">{{ progress.processedCount }}</p>
          <p class="text-gray-500 text-sm mt-1">已校验</p>
        </div>
        <div class="bg-red-50 rounded-lg p-4 text-center">
          <p class="text-2xl font-bold text-red-600">{{ progress.failCount }}</p>
          <p class="text-gray-500 text-sm mt-1">失败行数</p>
        </div>
      </div>

      <!-- 任务级失败信息 -->
      <div v-if="progress.status === 3 && progress.errorMessage" class="mt-4 p-3 bg-red-50 rounded-lg text-sm text-red-700">
        {{ progress.errorMessage }}
      </div>

      <!-- 完成后的操作 -->
      <div v-if="!running" class="mt-6 flex justify-end gap-3 flex-wrap">
        <el-button v-if="progress.failCount > 0" type="warning" @click="openErrors">
          查看失败行（{{ progress.failCount }}）
        </el-button>
        <el-button v-if="progress.failCount > 0" @click="exportImportErrors">导出失败行</el-button>
        <el-button type="primary" @click="goToDetail">查看导入数据</el-button>
        <el-button @click="newTask">发起新导入</el-button>
      </div>
    </div>

    <!-- 失败行分页弹窗 -->
    <el-dialog v-model="errorDialogVisible" title="校验失败行" width="760px">
      <el-table :data="errorRows" stripe max-height="420" v-loading="errorLoading">
        <el-table-column prop="rowIndex" label="行号" width="80" />
        <el-table-column prop="dataCode" label="数据编号" width="130" />
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column prop="errorMsg" label="错误原因" min-width="240" show-overflow-tooltip />
      </el-table>
      <div class="mt-4 flex justify-between items-center">
        <el-button type="primary" link @click="exportImportErrors">导出全部失败行</el-button>
        <el-pagination
          v-model:current-page="errorPage.pageNum"
          v-model:page-size="errorPage.pageSize"
          :total="errorPage.total"
          :page-sizes="[20, 50, 100]"
          layout="total, prev, pager, next"
          @current-change="fetchErrors"
        />
      </div>
    </el-dialog>

    <!-- 使用说明 -->
    <div class="card">
      <h2 class="text-lg font-semibold text-gray-700 mb-4">说明</h2>
      <div class="space-y-3 text-gray-600 text-sm">
        <p>1. 后端采用 EasyExcel SAX 分批解析（每批1000行），读取与校验入库之间用有界队列衔接，不会一次性把整表加载进内存。</p>
        <p>2. 文件上传后立即返回任务编号，导入在后台进行；进度每秒刷新，显示已读取、已校验、失败行数与预计剩余时间。</p>
        <p>3. 关闭页面、刷新或换设备登录后，凭任务编号可继续查看；后端重启会自动恢复未完成任务。</p>
        <p>4. 校验失败的行会记录行号与原因，支持分页查看与导出修正后重新导入。</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { excelApi } from '@/api'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const TASK_KEY = 'medical_import_task'
const POLL_INTERVAL = 1000

const uploadRef = ref(null)
const selectedFile = ref(null)
const uploading = ref(false)
const uploadProgress = ref(0)

const progress = ref(null)
const loadingProgress = ref(false)
const resumeBatchNo = ref('')
let pollTimer = null

const errorDialogVisible = ref(false)
const errorLoading = ref(false)
const errorRows = ref([])
const errorPage = reactive({ pageNum: 1, pageSize: 20, total: 0 })

const running = computed(() => progress.value && progress.value.status === 0)

const statusTitle = computed(() => {
  if (!progress.value) return ''
  return { 0: '导入进行中', 1: '导入完成', 2: '导入完成（存在失败行）', 3: '导入失败' }[progress.value.status] || '未知'
})
const statusTagType = computed(() => ({ 0: 'primary', 1: 'success', 2: 'warning', 3: 'danger' })[progress.value?.status] || 'info')
const statusIconBg = computed(() => ({ 0: 'bg-blue-100', 1: 'bg-green-100', 2: 'bg-yellow-100', 3: 'bg-red-100' })[progress.value?.status] || 'bg-gray-100')

const phaseText = computed(() => {
  const map = { COUNTING: '统计行数中', PARSING: '读取解析中', PROCESSING: '校验入库中', DONE: '已完成', FAILED: '失败' }
  return map[progress.value?.phase] || progress.value?.phase || ''
})

const formatFileSize = (bytes) => {
  if (!bytes) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

const formatEta = (sec) => {
  if (sec == null) return ''
  if (sec < 60) return `${sec} 秒`
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return s > 0 ? `${m} 分 ${s} 秒` : `${m} 分钟`
}

const handleFileChange = (file) => {
  selectedFile.value = file.raw
}
const handleExceed = () => ElMessage.warning('只能上传一个文件')
const removeFile = () => {
  selectedFile.value = null
  uploadRef.value?.clearFiles()
}

// ============ 上传并创建任务 ============
const handleUpload = async () => {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  uploadProgress.value = 0
  stopPolling()
  try {
    const res = await excelApi.import(selectedFile.value, (e) => {
      if (e.lengthComputable) {
        uploadProgress.value = Math.round((e.loaded * 100) / e.total)
      }
    })
    const batchNo = res.data.batchNo
    ElMessage.success('任务已创建，后台开始分批导入')
    saveTask(batchNo)
    selectedFile.value = null
    uploadRef.value?.clearFiles()
    await attachTask(batchNo)
  } catch (e) {
    // 拦截器已提示
  } finally {
    uploading.value = false
  }
}

// ============ 任务进度轮询 ============
const attachTask = async (batchNo, silent = false) => {
  if (!silent) loadingProgress.value = true
  try {
    const res = await excelApi.getProgress(batchNo)
    progress.value = res.data
    saveTask(batchNo)
    if (running.value) {
      startPolling()
    } else {
      stopPolling()
      localStorage.removeItem(TASK_KEY)
    }
  } catch (e) {
    progress.value = null
  } finally {
    loadingProgress.value = false
  }
}

const startPolling = () => {
  stopPolling()
  pollTimer = setInterval(async () => {
    if (!progress.value) return
    try {
      const res = await excelApi.getProgress(progress.value.batchNo)
      progress.value = res.data
      if (!running.value) {
        stopPolling()
        localStorage.removeItem(TASK_KEY)
      }
    } catch (e) {
      // 单次失败忽略，下一轮继续
    }
  }, POLL_INTERVAL)
}
const stopPolling = () => {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

// ============ 凭编号恢复 ============
const resumeTask = async () => {
  const no = (resumeBatchNo.value || '').trim()
  if (!no) {
    ElMessage.warning('请输入任务编号')
    return
  }
  localStorage.removeItem(TASK_KEY)
  await attachTask(no)
}

const saveTask = (batchNo) => {
  localStorage.setItem(TASK_KEY, JSON.stringify({ batchNo, savedAt: Date.now() }))
}

const copyBatchNo = async () => {
  try {
    await navigator.clipboard.writeText(progress.value.batchNo)
    ElMessage.success('任务编号已复制')
  } catch (e) {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

const newTask = () => {
  progress.value = null
  resumeBatchNo.value = ''
  stopPolling()
  localStorage.removeItem(TASK_KEY)
}

// ============ 失败行 ============
const openErrors = async () => {
  errorDialogVisible.value = true
  errorPage.pageNum = 1
  await fetchErrors()
}
const fetchErrors = async () => {
  if (!progress.value) return
  errorLoading.value = true
  try {
    const res = await excelApi.getImportErrors(progress.value.batchNo, {
      pageNum: errorPage.pageNum,
      pageSize: errorPage.pageSize
    })
    errorRows.value = res.data.records || []
    errorPage.total = res.data.total || 0
  } finally {
    errorLoading.value = false
  }
}
const exportImportErrors = () => {
  if (!progress.value) return
  window.open(`${excelApi.exportImportErrorsUrl(progress.value.batchNo)}?token=${userStore.token}`, '_blank')
}

const goToDetail = () => {
  if (progress.value?.batchNo) router.push(`/data/${progress.value.batchNo}`)
}

const downloadTemplate = () => {
  window.open(`${excelApi.downloadTemplate()}?token=${userStore.token}`, '_blank')
}

onMounted(async () => {
  // 路由携带任务编号优先（例如从导入记录页跳转）
  const queryNo = route.query?.task
  if (queryNo) {
    resumeBatchNo.value = queryNo
    await attachTask(queryNo, true)
    return
  }
  // 自动恢复上次未完成任务
  const saved = localStorage.getItem(TASK_KEY)
  if (saved) {
    try {
      const { batchNo } = JSON.parse(saved)
      if (batchNo) {
        resumeBatchNo.value = batchNo
        await attachTask(batchNo, true)
      }
    } catch (e) {
      localStorage.removeItem(TASK_KEY)
    }
  }
})

onBeforeUnmount(stopPolling)
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
