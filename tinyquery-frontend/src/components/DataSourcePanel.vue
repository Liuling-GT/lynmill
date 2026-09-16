<template>
  <el-card shadow="never">
    <template #header>
      <div class="panel-header">
        <span>已注册数据源</span>
        <el-button size="small" :loading="loading" @click="load">刷新列表</el-button>
      </div>
    </template>

    <el-empty v-if="!loading && rows.length === 0" description="暂无数据源" />

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
          <el-table-column prop="name" label="名称" width="150" />
          <el-table-column label="配置">
            <template #default="{ row }">
              <!-- 敏感字段的值已由服务端替换为掩码 -->
              <span v-for="(value, key) in row.properties" :key="key" class="kv">
                {{ key }}={{ value }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <!-- 重新加载：数据文件或数据库内容变化后同步内存快照 -->
              <el-button
                size="small"
                :loading="refreshingName === row.name"
                @click="handleRefresh(row.name)"
              >
                刷新
              </el-button>
              <el-button type="danger" size="small" @click="handleRemove(row.name)">删除</el-button>
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
import {
  listDataSources,
  listDataSourceTypes,
  refreshDataSource,
  removeDataSource,
} from '../api/datasource'

const rows = ref([])
// 类型元数据：type -> label 的映射来源
const types = ref([])
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
  const matched = types.value.find((item) => item.type === type)
  return matched ? matched.label : type
}

const load = async () => {
  loading.value = true
  try {
    // 两个接口都已在别处调用过，此处并行取回，不新增请求类型
    const [dataSources, dataTypes] = await Promise.all([
      listDataSources(),
      listDataSourceTypes(),
    ])

    rows.value = dataSources
    types.value = dataTypes

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
    ElMessage.success(`已重新加载：${name}`)
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
    await ElMessageBox.confirm(`确定删除数据源 ${name}？`, '提示', { type: 'warning' })
  } catch {
    // 用户取消，不视为错误
    return
  }

  try {
    await removeDataSource(name)
    ElMessage.success(`已删除：${name}`)
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
  color: #606266;
  word-break: break-all;
}
</style>
