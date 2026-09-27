import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import router from '@/router'

const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'

const request = axios.create({
  baseURL,
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json'
  }
})

request.interceptors.request.use(
  (config) => {
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers.Authorization = `Bearer ${userStore.token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code !== 200) {
      ElMessage.error(res.message || '请求失败')
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => {
    if (error.response) {
      const { status, data } = error.response
      if (status === 401) {
        ElMessage.error('登录已过期，请重新登录')
        const userStore = useUserStore()
        userStore.logout()
        router.push('/login')
      } else {
        ElMessage.error(data?.message || '请求失败')
      }
    } else {
      ElMessage.error('网络错误，请检查网络连接')
    }
    return Promise.reject(error)
  }
)

export const authApi = {
  login: (data) => request.post('/auth/login', data)
}

export const excelApi = {
  // 创建异步导入任务，立即返回任务编号
  import: (file, onProgress) => {
    const formData = new FormData()
    formData.append('file', file)
    return request.post('/excel/import', formData, {
      headers: {
        'Content-Type': 'multipart/form-data'
      },
      timeout: 120000,
      onUploadProgress: onProgress
    })
  },

  // 凭任务编号查询进度
  getProgress: (batchNo) => request.get(`/excel/import/${batchNo}/progress`),

  // 分页查询校验失败行
  getImportErrors: (batchNo, params) =>
    request.get(`/excel/import/${batchNo}/errors`, { params }),

  // 导出校验失败行地址
  exportImportErrorsUrl: (batchNo) =>
    `${baseURL}/excel/import/${batchNo}/errors/export`,

  getRecords: (params) => request.get('/excel/records', { params }),

  getDataByBatch: (batchNo, params) => request.get(`/excel/data/${batchNo}`, { params }),

  reportData: (batchNo) => request.post(`/excel/report/${batchNo}`),

  getFailedData: (batchNo) => request.get(`/excel/report/failed/${batchNo}`),

  retryReport: (batchNo) => request.post(`/excel/report/retry/${batchNo}`),

  downloadTemplate: () => {
    return `${baseURL}/excel/template`
  },

  exportErrors: (batchNo) => {
    return `${baseURL}/excel/export/errors/${batchNo}`
  }
}

export default request
