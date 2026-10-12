<template>
  <el-card shadow="never">
    <template #header>
      <div class="panel-header">
        <span>{{ t('datasource.registered') }}</span>
        <el-button size="small" :loading="loading" @click="load">{{ t('datasource.refresh') }}</el-button>
      </div>
    </template>

    <el-empty v-if="!loading && rows.length === 0" :description="t('datasource.empty')" />

    <!-- 按数据源类型分组折叠。分组依据是后端下发的类型元数据，前端不识别任何具体类型 -->
    <el-collapse v-else v-model="expandedGroups">
      <el-collapse-item
        v-for="group in groups"
        :key="group.type"
        :name="group.type"
      >
        <template #title>
          <span class="group-title">
            <span>{{ group.label }}</span>
            <el-tag size="small" type="info" round>{{ group.items.length }}</el-tag>
          </span>
        </template>

        <el-table :data="group.items" size="small" border>
          <el-table-column prop="name" :label="t('datasource.column.name')" width="150" />
          <el-table-column :label="t('datasource.column.config')">
            <template #default="{ row }">
              <!-- 敏感字段的值已由服务端替换为掩码 -->
              <span v-for="(value, key) in row.properties" :key="key" class="kv">
                {{ key }}={{ value }}
              </span>
            </template>
          </el-table-column>
          <!-- 操作列宽 170 不是随手定的（下面的数字都是真浏览器实测的）：
               单元格可用宽度 = 170 − 1（边框）− 2×8（.cell 内边距）= 153px，
               而英文下「Refresh + 12px 间距 + Delete」需要 137px。
               原来写 150（可用 133px）差 4px 放不下，两个按钮就折成两行，
               行高从 33px 变成 57px —— 中文只要 108px 所以看不出问题，
               是英文模式独有的错版。留 16px 余量是为了将来文案再长一点也不会折。
               改小前请先量。 -->
          <el-table-column :label="t('datasource.column.action')" width="170">
            <template #default="{ row }">
              <!-- 重新加载：数据文件或数据库内容变化后同步内存快照 -->
              <el-button
                size="small"
                :loading="refreshingName === row.name"
                @click="handleRefresh(row.name)"
              >
                {{ t('common.refresh') }}
              </el-button>
              <el-button type="danger" size="small" @click="handleRemove(row.name)">{{ t('common.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import {
  listDataSources,
  refreshDataSource,
  removeDataSource,
} from '../api/datasource'

/**
 * 数据源类型元数据（type -> label），由 App.vue 下发。
 *
 * 这里刻意不自己请求 /datasource/types：App.vue 启动时已经取过一次
 * 并传给了 DataSourceForm，面板再取一遍就是同一个接口请求两次、拿到两份
 * 一模一样的数据。两份状态不一致时（比如后端新增了类型、App 重新取过而面板没有），
 * 列表里新类型会显示成原始标识符（xlsx 而不是「Excel 文件」）。
 * 统一由 App 持有，两边共用同一份。
 */
const props = defineProps({
  types: {
    type: Array,
    default: () => [],
  },
})

const { t } = useI18n()

const rows = ref([])
const loading = ref(false)

// 正在刷新的数据源名。用于只给那一个按钮显示 loading，不影响其他行
const refreshingName = ref('')

// 展开的分组。后端返回数据后按类型填上，默认全部展开
const expandedGroups = ref([])

// 按类型分组。顺序沿用后端 list() 的排序（先类型后名称），前端不改动顺序
const groups = computed(() => {
  const map = new Map()

  for (const item of rows.value) {
    if (!map.has(item.type)) {
      map.set(item.type, {
        type: item.type,
        label: labelOf(item.type),
        items: [],
      })
    }
    map.get(item.type).items.push(item)
  }

  return Array.from(map.values())
})

// 类型标识转展示名称。取不到时退回类型标识本身，
// 保证后端新增了类型但元数据没跟上时仍能正常显示，不会空白
const labelOf = (type) => {
  const matched = props.types.find((item) => item.type === type)
  return matched ? matched.label : type
}

const load = async () => {
  loading.value = true
  try {
    // 只取数据源本身；类型元数据由 App.vue 通过 props 下发，
    // 不再在这里重复请求 /datasource/types
    const dataSources = await listDataSources()

    rows.value = dataSources

    // 默认全部展开，便于直接看到内容
    expandedGroups.value = Array.from(new Set(dataSources.map((item) => item.type)))
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}

const handleRefresh = async (name) => {
  refreshingName.value = name
  try {
    await refreshDataSource(name)
    ElMessage.success(t('datasource.reloaded', { name }))
    await load()
  } catch (error) {
    // 加载失败时后端不会改动原有数据，这里只提示
    ElMessage.error(error.message)
  } finally {
    refreshingName.value = ''
  }
}

const handleRemove = async (name) => {
  try {
    await ElMessageBox.confirm(
      t('datasource.deleteConfirm', { name }),
      t('datasource.prompt'),
      { type: 'warning' },
    )
  } catch {
    // 用户取消，不视为错误
    return
  }

  try {
    await removeDataSource(name)
    ElMessage.success(t('datasource.deleted', { name }))
    await load()
  } catch (error) {
    ElMessage.error(error.message)
  }
}

onMounted(load)

// 暴露给父组件，注册成功后刷新列表
defineExpose({ load })
</script>

<style scoped>
.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 折叠面板标题：名称 + 数量标签 */
.group-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}

.kv {
  display: inline-block;
  margin-right: 12px;
  font-family: monospace;
  font-size: 12px;
  color: var(--ui-text);
  word-break: break-all;
}
</style>
