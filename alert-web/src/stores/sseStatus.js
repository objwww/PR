import { ref } from 'vue'
import { defineStore } from 'pinia'

// SSE 连接状态 store（UI-0）：由 RunDetailView 的 EventSource 生命周期驱动，
// AppShell 顶栏指示器据此渲染——绿脉冲=connected / 黄=connecting / 灰=disconnected。
export const useSseStore = defineStore('sseStatus', () => {
  const status = ref('disconnected') // connected | connecting | disconnected
  const lastEventAt = ref(null)

  function setConnecting() { status.value = 'connecting' }
  function setConnected() { status.value = 'connected' }
  function setDisconnected() { status.value = 'disconnected' }
  function markEvent() { lastEventAt.value = new Date() }

  return { status, lastEventAt, setConnecting, setConnected, setDisconnected, markEvent }
})
