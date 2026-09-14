<template>
  <el-card shadow="never">
    <template #header>
      <div class="panel-header">
        <span>已注册数据源</span>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="rows" empty-text="暂无数据源">
      <el-table-column prop="name" label="名称" width="150" />
      <el-table-column prop="type" label="类型" width="100" />
      <el-table-column label="配置">
        <template #default="{ row }">
          <!-- 敏感字段的值已由服务端替换为掩码 -->
          <span v-for="(value, key) in row.properties" :key="key" class="kv">
            {{ key }}={{ value }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button type="danger" size="small" @click="handleRemove(row.name)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listDataSources, removeDataSource } from '../api/datasource'

const rows = ref([])
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    rows.value = await listDataSources()
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
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

.kv {
  display: inline-block;
  margin-right: 12px;
  font-family: monospace;
  font-size: 12px;
  color: #606266;
}
</style>
