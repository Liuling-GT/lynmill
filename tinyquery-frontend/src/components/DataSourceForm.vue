<template>
  <el-form
    ref="formRef"
    class="ds-form"
    :model="form"
    :rules="rules"
    label-width="150px"
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
      :key="`${form.type}-${field.name}`"
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
      <el-button :loading="testing" @click="handleTest">连接测试</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">注册</el-button>
      <el-button @click="handleReset">重置</el-button>
    </el-form-item>

    <!-- 测试结果常驻显示：改参数时用户需要对照着看错误原文，toast 一闪就没了 -->
    <el-alert
      v-if="testResult"
      :type="testResult.success ? 'success' : 'error'"
      show-icon
      :closable="false"
      :title="testResult.success ? '测试通过' : '测试未通过'"
      :description="testResult.message"
    />
  </el-form>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { listDataSources, registerDataSource, testDataSource } from '../api/datasource'

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
const testing = ref(false)
// 测试结果：null 表示尚未测试
const testResult = ref(null)

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
            if (exists) {
              callback(new Error('该名称已被占用'))
            } else {
              callback()
            }
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
        if (Number.isFinite(Number(value))) {
          callback()
        } else {
          callback(new Error('请输入数字'))
        }
      },
      trigger: 'change',
    })
  }

  return rules
}

// 切换类型时清空配置项与残留状态，避免把上一个数据源的字段带过去。
// 同时给枚举字段填上第一个选项作为默认值 —— 用户不点也应有一个合法值，
// 否则必填字段会因为 undefined 被拦下，而报错信息用户看不懂
const handleTypeChange = () => {
  form.value.properties = {}

  for (const field of currentFields.value) {
    if (field.valueType === 'ENUM' && field.options.length > 0) {
      form.value.properties[field.name] = field.options[0]
    }
  }

  if (formRef.value) {
    formRef.value.clearValidate('properties')
  }
}

const handleReset = () => {
  form.value = { name: '', type: '', properties: {} }

  // 重置后同样补上枚举默认值，保持与刚选中类型时一致的状态
  for (const field of currentFields.value) {
    if (field.valueType === 'ENUM' && field.options.length > 0) {
      form.value.properties[field.name] = field.options[0]
    }
  }

  if (formRef.value) {
    formRef.value.clearValidate()
  }
}

// 校验配置项本身是否填写完整（不含名称，测试不需要名字）
const validateProperties = async () => {
  if (!formRef.value) {
    return false
  }
  // 只校验配置项字段，名称留到注册时再校验
  const props_ = currentFields.value.map((field) => `properties.${field.name}`)
  if (props_.length === 0) {
    return true
  }
  try {
    await formRef.value.validateField(props_)
    return true
  } catch {
    return false
  }
}

const handleTest = async () => {
  if (!form.value.type) {
    ElMessage.warning('请先选择数据源类型')
    return
  }
  if (!(await validateProperties())) {
    return
  }

  testing.value = true
  try {
    testResult.value = await testDataSource({
      name: form.value.name || 'connection-test',
      type: form.value.type,
      properties: form.value.properties,
    })
  } catch (error) {
    // 接口层面的失败（如类型不支持）也呈现为一条测试结果
    testResult.value = { success: false, message: error.message }
  } finally {
    testing.value = false
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
  color: var(--accent);
}

/* ==================== 主题化：把 Element 的红绿收进紫色体系 ==================== */

/*
 * 必填星号 / 校验红框 / 错误文字 / 错误提示框，全都取自 Element 的「危险色」变量族。
 *
 * 这里覆盖变量而不是逐个写选择器，是因为选择器要跟 Element 内部规则比优先级，
 * 而 :deep() 只是给目标加了个祖先属性选择器（优先级 +1），
 * 往往正好跟 Element 打平 —— 打平就由加载顺序决定胜负，
 * 而 element-plus 的样式在 main.js 里是后加载的，于是覆盖会静默失效。
 * 变量不存在优先级之争：定义在这个元素上，整棵子树都跟着走。
 */
.ds-form {
  --el-color-danger: var(--accent);
  --el-color-danger-light-3: #a78bfa;
  --el-color-danger-light-5: #c4b5fd;
  --el-color-danger-light-7: #ddd6fe;
  --el-color-danger-light-8: #ede9fe;
  --el-color-danger-light-9: #f5f3ff;
  --el-color-danger-dark-2: var(--accent-deep);
}

/*
 * 测试通过提示：浅紫字、不填底色，与右侧面板融为一体。
 *
 * 这里的选择器比上面那组多带 .is-light 和 .el-alert，把权重顶到 (0,4,0)/(0,5,0)，
 * 稳压 Element 的 (0,2,0)/(0,3,0)。用变量做不到 ——
 * 变量只能整族换色，没法只改底色不改字色
 */
:deep(.el-alert.el-alert--success.is-light) {
  background-color: var(--accent-soft);
  border: 1px solid #ddd6fe;
}

:deep(.el-alert.el-alert--success.is-light .el-alert__title),
:deep(.el-alert.el-alert--success.is-light .el-alert__description) {
  color: var(--accent);
}

:deep(.el-alert.el-alert--success.is-light .el-alert__icon) {
  color: #a78bfa;
}
</style>
