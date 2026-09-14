<template>
  <div class="app">
    <h1>TinyQuery Platform</h1>

    <!-- 数据源管理区 -->
    <el-card shadow="never" class="section">
      <template #header>
        <span>数据源管理</span>
      </template>

      <DataSourceForm :types="types" @registered="handleRegistered" />
    </el-card>

    <el-card shadow="never" class="section">
      <DataSourcePanel ref="panelRef" />
    </el-card>

    <!-- SQL 查询区 -->
    <el-card shadow="never" class="section">
      <template #header>
        <span>SQL 查询</span>
      </template>

      <!-- SQL input -->
      <textarea v-model="sql" placeholder="Enter SQL, e.g. select * from sample.csv"></textarea>

      <!-- Execute button -->
      <button @click="executeQuery">Execute</button>

      <!-- Result display -->
      <pre>{{ result }}</pre>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import axios from 'axios'
import DataSourceForm from './components/DataSourceForm.vue'
import DataSourcePanel from './components/DataSourcePanel.vue'
import { listDataSourceTypes } from './api/datasource'

const sql = ref('select count(*) from huge.csv')
const result = ref('')

// 后端下发的数据源类型描述，驱动配置表单动态渲染
const types = ref([])
const panelRef = ref(null)

onMounted(async () => {
  try {
    types.value = await listDataSourceTypes()
  } catch (error) {
    result.value = 'Error: ' + error.message
  }
})

// 注册成功后刷新数据源列表
const handleRegistered = () => {
  if (panelRef.value) {
    panelRef.value.load()
  }
}

const executeQuery = async () => {
  try {
    const response = await axios.post('/api/query', { sql: sql.value })
    result.value = JSON.stringify(response.data, null, 2)
  } catch (error) {
    result.value = 'Error: ' + error.message
  }
}
</script>

<style>
.app {
  max-width: 900px;
  margin: 0 auto;
  padding: 20px;
  font-family: sans-serif;
}

.section {
  margin-bottom: 16px;
}

textarea {
  width: 100%;
  height: 80px;
  font-family: monospace;
  padding: 10px;
  box-sizing: border-box;
}

button {
  margin-top: 10px;
  padding: 10px 20px;
  cursor: pointer;
}

pre {
  background: #f5f5f5;
  padding: 15px;
  border-radius: 5px;
  overflow: auto;
}
</style>
