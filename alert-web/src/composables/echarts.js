// RV11：ECharts 按需注册 + VueECharts 局部组件——只在图表页面（Monitor/Overview）
// 引入本模块，首包不再携带 echarts 全家桶（原 main.js 全局注册把大依赖拖进首包）
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

let registered = false

/** 幂等注册（多页面共用一次）；返回局部使用的图表组件 */
export function useChart() {
  if (!registered) {
    use([
      CanvasRenderer,
      BarChart, HeatmapChart, LineChart, PieChart,
      DataZoomComponent, GridComponent, LegendComponent,
      TitleComponent, ToolboxComponent, TooltipComponent,
    ])
    registered = true
  }
  return VueECharts
}
