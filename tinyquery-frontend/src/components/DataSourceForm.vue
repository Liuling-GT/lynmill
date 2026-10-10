<template>
  <el-form
    ref="formRef"
    class="ds-form"
    :model="form"
    :rules="rules"
    :label-width="labelWidth"
    @submit.prevent
  >
    <!-- 数据源类型：选项由后端 /datasource/types 下发 -->
    <el-form-item :label="t('form.typeLabel')" prop="type">
      <el-select v-model="form.type" :placeholder="t('form.selectPlaceholder')" @change="handleTypeChange">
        <el-option v-for="item in types" :key="item.type" :label="item.label" :value="item.type" />
      </el-select>
    </el-form-item>

    <el-form-item :label="t('form.nameLabel')" prop="name">
      <el-input v-model="form.name" :placeholder="t('form.namePlaceholder')" />
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
        :placeholder="field.placeholder || t('form.selectPlaceholder')"
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

    <!--
      「保存密码」勾选框。
      只在当前类型确实有敏感字段时才显示 —— CSV 之类没有密码的数据源显示它是噪声。
      凭据存储不可用时置灰并说明原因：让用户知道「不是我不给你存，是这台机器不支持」，
      而不是勾了没反应。
    -->
    <el-form-item v-if="hasSensitiveField" :label="t('form.savePassword')">
      <div class="save-password">
        <el-checkbox
          v-model="form.savePassword"
          :disabled="!credentialAvailable"
          :label="t('form.savePasswordHint')"
        />
        <div v-if="!credentialAvailable" class="save-password-hint">
          {{ credentialReason || t('form.error.savePasswordUnsupported') }}
        </div>
        <div v-else class="save-password-hint">
          {{ t('form.savePasswordNote') }}
        </div>
      </div>
    </el-form-item>

    <el-form-item>
      <el-button :loading="testing" @click="handleTest">{{ t('form.testConnection') }}</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">{{ t('form.register') }}</el-button>
      <el-button @click="handleReset">{{ t('form.reset') }}</el-button>
    </el-form-item>

    <!-- 测试结果常驻显示：改参数时用户需要对照着看错误原文，toast 一闪就没了。
         hint-alert 提供紫色配色，与查询页的补充提示同款 -->
    <el-alert
      v-if="testResult"
      :type="testResult.success ? 'success' : 'error'"
      show-icon
      :closable="false"
      :class="testResult.success ? 'hint-alert' : ''"
      :title="testResult.success ? t('form.testPassed') : t('form.testFailed')"
      :description="testResult.message"
    />
  </el-form>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { QuestionFilled } from '@element-plus/icons-vue'
import {
  listDataSources,
  registerDataSource,
  testDataSource,
  getCredentialStatus,
} from '../api/datasource'

const { t, locale } = useI18n()

const props = defineProps({
  // 后端下发的类型描述列表
  types: {
    type: Array,
    default: () => [],
  },
})

const emit = defineEmits(['registered'])

const formRef = ref(null)
const form = ref({ name: '', type: '', properties: {}, savePassword: false })
const submitting = ref(false)
const testing = ref(false)
// 测试结果：null 表示尚未测试
const testResult = ref(null)

// 凭据存储可用性。默认不可用 —— 在拿到后端答复之前把勾选框置灰，
// 比先让用户勾上、提交后才发现存不了要好
const credentialAvailable = ref(false)
const credentialReason = ref('')

onMounted(async () => {
  try {
    const status = await getCredentialStatus()
    credentialAvailable.value = status.available
    credentialReason.value = status.reason || ''
  } catch {
    // 拿不到就按不可用处理。这只是个可选功能，不该打扰用户
    credentialAvailable.value = false
    credentialReason.value = t('form.error.savePasswordUnknown')
  }
})

// 当前类型是否有敏感字段（如密码）。没有就不显示「保存密码」勾选框
const hasSensitiveField = computed(() =>
  currentFields.value.some((field) => field.sensitive),
)

// 外层字段的校验规则。
// 名称不允许含 '.'：后端 resolveTableName 靠后缀区分表名与数据源，含 '.' 会产生歧义
const rules = {
  type: [{ required: true, message: t('form.error.typeRequired'), trigger: 'change' }],
  name: [
    { required: true, message: t('form.error.nameRequired'), trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (!value || !value.includes('.')) {
          callback()
          return
        }
        callback(new Error(t('form.error.nameNoDot')))
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
              callback(new Error(t('form.error.nameTaken')))
            } else {
              callback()
            }
          })
          // 查重请求失败时不能放行：callback() 不带参数等于「校验通过」，
          // 用户会提交一个重名数据源，再撞在后端的唯一约束上拿到更晦涩的错。
          // 这里如实报「校验未完成」，让用户重试
          .catch(() => callback(new Error(t('form.error.checkIncomplete'))))
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

/**
 * label 列宽：按当前语言下最长的 label 实测算出来，而不是写死。
 *
 * <p>原本是固定的 150px，那是按中文量的 —— 中文最长「数据库文件路径」7 个字，
 * 150px 很宽松。切到英文后最长的是 SQLite 的「Database File Path」18 个字符，
 * 连同后面的问号图标一起把 150px 撑爆，label 换行、控件错位。</p>
 *
 * <p>不改成「写死一个够英文的宽度」是因为那样中文下会空出一大截。
 * 这里按实际文本量，两种语言各自拿到合适的宽度，以后加语言也不用再调。</p>
 *
 * <p>量的是渲染后的真实像素宽：挂一个离屏元素，套用与 label 相同的字体，
 * 逐个 measureText 取最大值。canvas 比 offsetWidth 可靠 ——
 * 后者要求元素已插入文档并完成布局，在 computed 里拿不到。</p>
 */
const labelWidth = computed(() => {
  // 依赖 locale，切语言时自动重算
  void locale.value

  // 表单固有 label（类型 / 名称 / 保存密码）也要一起量，它们不在 currentFields 里
  const texts = [
    t('form.typeLabel'),
    t('form.nameLabel'),
    t('form.savePassword'),
    ...currentFields.value.map((f) => f.label),
  ].filter(Boolean)

  if (texts.length === 0) {
    return '150px'
  }

  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    // 拿不到 2d 上下文时退回固定宽度：宁可稍有偏差，也不要整个表单渲染不出来
    return '150px'
  }
  // 与 .field-label 的实际字体保持一致，否则量出来的值没有意义
  const styles = getComputedStyle(document.body)
  ctx.font = `${styles.fontSize} ${styles.fontFamily}`

  let maxText = 0
  for (const s of texts) {
    maxText = Math.max(maxText, ctx.measureText(s).width)
  }

  // 加上：问号图标 14px + 与文字间距 4px + 与控件之间的留白 24px + 安全余量 8px
  const ICON_AND_GAP = 14 + 4 + 24 + 8
  return `${Math.ceil(maxText) + ICON_AND_GAP}px`
})

// 按字段元数据推导校验规则，不识别任何具体数据源。
// 后端仍会做同样的校验，这里只是为了减少一次无效往返
const rulesFor = (field) => {
  const rules = []

  if (field.required) {
    rules.push({
      required: true,
      message: t('form.error.fieldRequired', { field: field.label }),
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
          callback(new Error(t('form.error.numberRequired')))
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
  // 换类型时重置勾选：上一个类型可能没有密码字段，把这个选择带过去没有意义
  form.value.savePassword = false

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
  form.value = { name: '', type: '', properties: {}, savePassword: false }

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
    ElMessage.warning(t('form.error.typeRequired'))
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
      // 存储不可用时一律按 false 提交，不把用户勾不上的选项送上去
      savePassword: credentialAvailable.value && form.value.savePassword,
    })
    ElMessage.success(t('form.registered', { name: registered }))
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
  color: var(--ui-text-secondary);
  font-size: 14px;
  cursor: help;
}

.help-icon:hover {
  color: var(--accent);
}

/* 「保存密码」：勾选框 + 下面一行小字说明 */
.save-password {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.4;
}

.save-password-hint {
  font-size: 12px;
  color: var(--ui-text-secondary);
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
 * 测试通过提示的配色已上移到 tokens.css 的 .hint-alert，
 * 与查询页的补充提示共用一套 —— 两处各写一遍迟早改歪一处。
 * 这里只补 Element 变量的整族覆盖（表单内的 danger 也要是紫的）
 */
:deep(.el-form-item__error) {
  color: var(--accent);
}
</style>
