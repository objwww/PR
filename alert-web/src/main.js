import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/tokens.css'

// ECharts 按需注册（echarts/core 模块化，不全量 import）：
// 覆盖监控大盘所需——时序折线/柱状/饼图/热力图 + 网格/提示/图例/缩放/标题
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, HeatmapChart, LineChart, PieChart } from 'echarts/charts'
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  ToolboxComponent,
  TooltipComponent,
} from 'echarts/components'
import VueECharts from 'vue-echarts'

use([
  CanvasRenderer,
  BarChart, HeatmapChart, LineChart, PieChart,
  DataZoomComponent, GridComponent, LegendComponent,
  TitleComponent, ToolboxComponent, TooltipComponent,
])

createApp(App)
  .use(createPinia())
  .use(router)
  .component('VChart', VueECharts)
  .mount('#app')
