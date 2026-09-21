# -*- coding: utf-8 -*-
import io

# ============ RV01: DutyChatView.vue ============
p = r'alert-web/src/views/DutyChatView.vue'
t = io.open(p, encoding='utf-8').read()

old = """// ---------------- markdown 手写子集（D1/§C：标题/粗体/引用/三色 font/链接/@人）——
// 不引 vditor 等依赖；先转义再渲染，v-html 面安全
function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}"""
new = """// ---------------- markdown 手写子集（D1/§C：标题/粗体/引用/三色 font/链接/@人）——
// 不引 vditor 等依赖；先转义再渲染。
// RV01 安全不变量：本函数是全部插值的唯一来源，引号一并转义后，任何载荷都无法
// 进入标记/属性边界（href="..." 内不可能出现裸 "，事件属性注入结构性不可达）。
// 后续新增 Markdown 规则必须保持该不变量：只能插值本函数输出；URL 只认 https?://。
function escapeHtml(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}"""
assert old in t
t = t.replace(old, new)

old = """  // 企微三色 font（橙红 warning=page 级 / 绿 info=恢复 / 灰 comment=元信息）
  t = t.replace(/&lt;font color="(warning|info|comment)"&gt;(.*?)&lt;\\/font&gt;/g,
    '<span class="fc-$1">$2</span>')
  t = t.replace(/\\[([^\\]]+)\\]\\((https?:\\/\\/[^\\s)]+)\\)/g,
    '<a href="$2" target="_blank" rel="noopener">$1</a>')"""
new = """  // 企微三色 font（橙红 warning=page 级 / 绿 info=恢复 / 灰 comment=元信息）
  // RV01：源文本引号已转义为 &quot;——识别正则同步改匹配转义形
  t = t.replace(/&lt;font color=&quot;(warning|info|comment)&quot;&gt;(.*?)&lt;\\/font&gt;/g,
    '<span class="fc-$1">$2</span>')
  t = t.replace(/\\[([^\\]]+)\\]\\((https?:\\/\\/[^\\s)"]+)\\)/g,
    '<a href="$2" target="_blank" rel="noopener">$1</a>')"""
assert old in t
t = t.replace(old, new)
io.open(p, 'w', encoding='utf-8').write(t)
print('RV01 ok')

# ============ RV08: NotificationsView.vue ============
p = r'alert-web/src/views/NotificationsView.vue'
t = io.open(p, encoding='utf-8').read()

old = """let timer = null

async function load(reset) {
  if (loading.value) return
  loading.value = true
  try {
    const params = { unread: tab.value === 'unread', limit: PAGE_SIZE }
    if (!reset && nextCursor.value) params.cursor = nextCursor.value
    const res = await api('/duty/notifications', { params })
    rows.value = reset ? res.notifications : rows.value.concat(res.notifications)
    unreadCount.value = res.unreadCount ?? 0
    nextCursor.value = res.nextCursor ?? null
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '加载失败，请重试')
  } finally {
    loading.value = false
  }
}"""
new = """let timer = null
// RV08：单调请求序号——切筛选必须发出新请求（loading 锁只防同筛选加载更多重复），
// 旧响应写回前校验序号仍为当前值，不得覆盖新筛选面
let loadSeq = 0

async function load(reset) {
  if (!reset && loading.value) return
  const seq = ++loadSeq
  const unreadAtStart = tab.value === 'unread'
  loading.value = true
  try {
    const params = { unread: unreadAtStart, limit: PAGE_SIZE }
    if (!reset && nextCursor.value) params.cursor = nextCursor.value
    const res = await api('/duty/notifications', { params })
    if (seq !== loadSeq) return
    rows.value = reset ? res.notifications : rows.value.concat(res.notifications)
    unreadCount.value = res.unreadCount ?? 0
    nextCursor.value = res.nextCursor ?? null
  } catch (e) {
    if (seq !== loadSeq) return
    ElMessage.error(e?.response?.data?.error || '加载失败，请重试')
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}"""
assert old in t
t = t.replace(old, new)
io.open(p, 'w', encoding='utf-8').write(t)
print('RV08 ok')

# ============ RV02(部分): PrimaryClaimAdmission locator 数值/形状护栏 ============
p = r'control-app/src/main/java/com/objwww/pr/control/alert/application/agent/PrimaryClaimAdmission.java'
t = io.open(p, encoding='utf-8').read()

old = """    /** locator 解析：点分路径走对象键，数字段走数组下标（载荷面"字段真实存在"校验） */
    private static boolean resolves(JsonNode payload, String locator) {
        JsonNode node = payload;
        for (String segment : locator.split("\\\\.")) {
            if (node == null) {
                return false;
            }
            if (node.isArray() && segment.matches("\\\\d+")) {
                int index = Integer.parseInt(segment);
                node = index < node.size() ? node.get(index) : null;
            } else {
                node = node.path(segment);
            }
        }
        return node != null && !node.isMissingNode() && !node.isNull();
    }"""
new = """    /**
     * locator 解析：点分路径走对象键，数字段走数组下标（载荷面"字段真实存在"校验）。
     * RV02/T07：模型输出错误（下标超 int、超长/过深路径）只判"定位失败"返回 false，
     * 不得抛 NumberFormatException 打断整案准入；错误仅影响当前引用。
     */
    private static boolean resolves(JsonNode payload, String locator) {
        String[] segments = locator.split("\\\\.", -1);
        if (segments.length > 16) {
            return false;
        }
        JsonNode node = payload;
        for (String segment : segments) {
            if (node == null || segment.isEmpty() || segment.length() > 64) {
                return false;
            }
            if (node.isArray() && segment.matches("\\\\d+")) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException outOfIntRange) {
                    return false;
                }
                node = index < node.size() ? node.get(index) : null;
            } else {
                node = node.path(segment);
            }
        }
        return node != null && !node.isMissingNode() && !node.isNull();
    }"""
assert old in t
t = t.replace(old, new)
io.open(p, 'w', encoding='utf-8').write(t)
print('locator guard ok')
