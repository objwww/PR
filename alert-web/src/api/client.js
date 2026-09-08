import axios from 'axios'

// API client：后端配套接口（GET /rca-runs 等）落码前，走 mock 适配层；
// VITE_USE_MOCK=false 且后端就绪后经 vite proxy /api → control-app:8080 联调
const useMock = import.meta.env.VITE_USE_MOCK !== 'false'

const http = axios.create({ baseURL: '/api', timeout: 15000 })

export async function api(path, { params, mock } = {}) {
  if (useMock && mock) return mock()
  const { data } = await http.get(path, { params })
  return data
}

export { http, useMock }
