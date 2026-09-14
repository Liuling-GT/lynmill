<template>
  <el-form
    ref="formRef"
    :model="form"
    :rules="rules"
    label-width="110px"
    @submit.prevent
  >
    <!-- 数据源类型：选项由后端 /datasource/types 下发 -->
    <el-form-item label="数据源类型" prop="type">
      <el-select v-model="form.type" placeholder="请选择" @change="handleTypeChange">
        <el-option v-for="item in types" :key="item.type" :label="item.label" :value="item.type" />
      </el-select>
    </el-form-item>

    <el-form-item label="数据源名称" prop="name">
      <el-input v-model="form.name" placeholder="供 SQL 中 FROM 引用，如 users" />
    </el-form-item>

    <!--
      通用渲染器：只按 valueType（值类型）和 sensitive（敏感性）两个正交维度选控件，
      不识别任何具体数据源，新增数据源时本文件无需改动。
      说明文字与格式示例来自后端下发的 description / placeholder。
      校验规则由 required / valueType / sensitive 元数据推导，同样不识别具体数据源。
    -->
    <el-form-item
      v-for="field in currentFields"
      :key="field.name"
      :prop="'properties.' + field.name"
      :rules="rulesFor(field)"
    >
      <!-- 自定义 label 插槽：长说明改为图标悬停，避免 label 列被撑宽。
           星号由 el-form-item 的 is-required 类经 CSS 伪元素渲染，不受插槽影响 -->
      <template #label>
        <span class="field-label">
          <span>{{ field.label }}</span>
          <!-- 只有后端给了说明才渲染图标，否则保持原有紧凑布局 -->
          <el-tooltip
            v-if="field.description"
            :content="field.description"
            placement="top"
            :show-after="200"
          >
            <el-icon class="help-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </span>
      </template>

      <el-select
        v-if="field.valueType === 'ENUM'"
        v-model="form.properties[field.name]"
        :placeholder="field.placeholder || '请选择'"
      >
        <el-option v-for="opt in field.options" :key="opt" :label="opt" :value="opt" />
      </el-select>

      <el-switch v-else-if="field.valueType === 'BOOLEAN'" v-model="form.properties[field.name]" />

      <el-input-number
        v-else-if="field.valueType === 'NUMBER'"
        v-model="form.properties[field.name]"
        :controls="false"
        :placeholder="field.placeholder"
        style="width: 100%"
      />

      <!-- STRING：sensitive 只决定是否掩码，与值类型无关 -->
      <el-input
        v-else
        v-model="form.properties[field.name]"
        :type="field.sensitive ? 'password' : 'text'"
        :show-password="field.sensitive"
        :placeholder="field.placeholder"
      />
    </el-form-item>

    <el-form-item>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">注册</el-button>
      <el-button @click="handleReset">重置</el-button>
    </el-form-item>
  </el-form>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { listDataSources, registerDataSource } from '../api/datasource'

const props = defineProps({
  // 后端下发的类型描述列表
  types: {
    type: Array,
    default: () => [],
  },
})

const emit = defineEmits(['registered'])

const formRef = ref(null)
const form = ref({ name: '', type: '', properties: {} })
const submitting = ref(false)

// 外层字段的校验规则。
// 名称不允许含 '.'：后端 resolveTableName 靠后缀区分表名与数据源，含 '.' 会产生歧义
const rules = {
  type: [{ required: true, message: '请选择数据源类型', trigger: 'change' }],
  name: [
    { required: true, message: '请填写数据源名称', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (!value || !value.includes('.')) {
          callback()
          return
        }
        callback(new Error('名称不能包含英文句点'))
      },
      trigger: 'blur',
    },
    {
      // 名称重复后端也能发现，但那时数据已经加载进内存，这里提前拦下
      validator: (rule, value, callback) => {
        listDataSources()
          .then((list) => {
            const exists = list.some((item) => item.name === value)
            exists ? callback(new Error('该名称已被占用')) : callback()
          })
          .catch(() => callback())
      },
      trigger: 'blur',
    },
  ],
}

// 当前类型需要哪些字段，完全由后端描述驱动
const currentFields = computed(() => {
  const matched = props.types.find((item) => item.type === form.value.type)
  return matched ? matched.fields : []
})

// 按字段元数据推导校验规则，不识别任何具体数据源。
// 后端仍会做同样的校验，这里只是为了减少一次无效往返
const rulesFor = (field) => {
  const rules = []

  if (field.required) {
    rules.push({
      required: true,
      message: `请填写${field.label}`,
      trigger: field.valueType === 'NUMBER' ? 'change' : 'blur',
    })
  }

  // 数值字段：el-input-number 清空时值为 null，只有填了才需要判类型
  if (field.valueType === 'NUMBER') {
    rules.push({
      validator: (rule, value, callback) => {
        if (value === null || value === undefined || value === '') {
          callback()
          return
        }
        Number.isFinite(Number(value)) ? callback() : callback(new Error('请输入数字'))
      },
      trigger: 'change',
    })
  }

  return rules
}

// 切换类型时清空配置项与残留校验状态，避免把上一个数据源的字段带过去
const handleTypeChange = () => {
  form.value.properties = {}
  if (formRef.value) {
    formRef.value.clearValidate('properties')
  }
}

const handleReset = () => {
  form.value = { name: '', type: '', properties: {} }
  if (formRef.value) {
    formRef.value.clearValidate()
  }
}

const handleSubmit = async () => {
  if (!formRef.value) {
    return
  }

  // 所有字段一次性校验，缺失项会同时标红
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    const registered = await registerDataSource({
      name: form.value.name,
      type: form.value.type,
      properties: form.value.properties,
    })
    ElMessage.success(`数据源注册成功：${registered}`)
    handleReset()
    emit('registered')
  } catch (error) {
    // 后端 message 经 axios 拦截器透传至此
    ElMessage.error(error.message)
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
/* 自定义 label：文字左对齐，图标紧随其后 */
.field-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.help-icon {
  color: #909399;
  font-size: 14px;
  cursor: help;
}

.help-icon:hover {
  color: #409eff;
}
</style>
