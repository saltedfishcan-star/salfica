<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import draggable from 'vuedraggable'
import type { BoardConfig, ImageItem, QualityHint, TierConfig } from '../lib/board'
import {
  MAX_TIERS,
  MAX_UPLOAD_COUNT,
  MAX_UPLOAD_SIZE_BYTES,
  MIN_TIERS,
  PRESET_LEVEL_COLORS,
  createImage,
  createTier,
  getPresetColor,
  isHexColor,
  loadBoardConfigOrDefault,
  resetBoardState,
  saveBoardConfig,
} from '../lib/board'

const router = useRouter()
const config = ref<BoardConfig>(loadBoardConfigOrDefault())
const imageUrlInput = ref<string>('')
const filePickerRef = ref<HTMLInputElement | null>(null)
const isResolvingInput = ref(false)
const isSearchDropActive = ref(false)
const message = ref<string>('')
const failedImages = ref<Record<string, boolean>>({})
const previewImageSrc = ref<string>('')

const tierCount = computed(() => config.value.tiers.length)
const imageCount = computed(() => config.value.images.length)

watch(
  config,
  (value) => {
    saveBoardConfig(value)
  },
  { deep: true },
)

function showMessage(next: string): void {
  message.value = next
}

function clearMessage(): void {
  message.value = ''
}

function appendMessagePart(parts: string[], condition: boolean, content: string): void {
  if (condition) {
    parts.push(content)
  }
}

function joinSummary(parts: string[], fallback: string): string {
  return parts.length > 0 ? parts.join(' ') : fallback
}

async function runWithResolvingLock(task: () => Promise<void>): Promise<void> {
  if (isResolvingInput.value) {
    return
  }
  isResolvingInput.value = true
  try {
    await task()
  } finally {
    isResolvingInput.value = false
  }
}

function markImageAsFailed(image: ImageItem): void {
  failedImages.value = {
    ...failedImages.value,
    [image.id]: true,
  }
  if (image.resolveStatus !== 'failed') {
    image.resolveStatus = 'failed'
    image.resolveReason = image.resolveReason ?? '浏览器加载图片失败。'
  }
}

function setTierColor(tier: TierConfig, color: string): void {
  if (!isHexColor(color)) {
    return
  }
  tier.color = color.toLowerCase()
  clearMessage()
}

function onTierColorInput(tier: TierConfig, event: Event): void {
  const target = event.target
  if (target instanceof HTMLInputElement) {
    setTierColor(tier, target.value)
  }
}

function addTier(): void {
  if (config.value.tiers.length >= MAX_TIERS) {
    showMessage(`最多只能创建 ${MAX_TIERS} 个等级。`)
    return
  }

  const index = config.value.tiers.length
  config.value.tiers.push(createTier(`Tier ${index + 1}`, getPresetColor(index)))
  clearMessage()
}

function removeTier(index: number): void {
  if (config.value.tiers.length <= MIN_TIERS) {
    showMessage(`至少需要保留 ${MIN_TIERS} 个等级。`)
    return
  }

  config.value.tiers.splice(index, 1)
  clearMessage()
}

function isHttpImageUrl(value: string): boolean {
  try {
    const url = new URL(value)
    return url.protocol === 'http:' || url.protocol === 'https:'
  } catch {
    return false
  }
}

const IMAGE_SUFFIXES = ['.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp', '.svg', '.avif']

function hasImageLikeSuffix(value: string): boolean {
  if (!isHttpImageUrl(value)) {
    return false
  }

  try {
    const url = new URL(value)
    const pathname = url.pathname.toLowerCase()
    return IMAGE_SUFFIXES.some((suffix) => pathname.endsWith(suffix))
  } catch {
    return false
  }
}

async function resolveDirectImage(url: string): Promise<ImageItem> {
  if (!isHttpImageUrl(url)) {
    return createImage(url, {
      originalUrl: url,
      resolveStatus: 'failed',
      resolveReason: '链接格式无效，仅支持 http(s) 图片地址。',
    })
  }

  const dimensions = await getImageDimensions(url)
  if (!dimensions || dimensions.width <= 0 || dimensions.height <= 0) {
    const fallbackReason = hasImageLikeSuffix(url)
      ? '图片链接不可访问，或被来源站点限制加载。'
      : '仅支持图片直链 URL，网页链接暂不支持。'

    return createImage(url, {
      originalUrl: url,
      resolveStatus: 'failed',
      resolveReason: fallbackReason,
    })
  }

  const qualityHint = detectQualityHint(dimensions.width, dimensions.height)
  return createImage(url, {
    originalUrl: url,
    resolveStatus: 'ok',
    width: dimensions.width,
    height: dimensions.height,
    qualityHint,
  })
}

// 本地文件读取为 dataURL，便于在前端立即预览。
function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      if (typeof reader.result === 'string') {
        resolve(reader.result)
        return
      }
      reject(new Error('read_failed'))
    }
    reader.onerror = () => reject(new Error('read_failed'))
    reader.readAsDataURL(file)
  })
}

// 解析图片原始尺寸，用于提示“解析信息”。
function getImageDimensions(src: string): Promise<{ width: number; height: number } | null> {
  return new Promise((resolve) => {
    const image = new Image()
    image.onload = () => {
      resolve({ width: image.naturalWidth, height: image.naturalHeight })
    }
    image.onerror = () => resolve(null)
    image.src = src
  })
}

// 从拖拽的 HTML 片段中提取 src/href 里的 URL。
function normalizeHttpUrls(candidates: string[]): string[] {
  const normalized = candidates
    .map((item) => item.trim().replace(/[),.;!?]+$/g, ''))
    .filter((item) => isHttpImageUrl(item))
  return Array.from(new Set(normalized))
}

// 从拖拽的 HTML 片段中提取 src/href 里的 URL。
function extractUrlsFromHtml(html: string): string[] {
  if (!html.trim()) {
    return []
  }
  const fromAttrs = Array.from(html.matchAll(/(?:src|href)\s*=\s*["']([^"']+)["']/gi), (item) => item[1] ?? '')
  const directMatches = html.match(/https?:\/\/[^\s"'<>]+/gi) ?? []
  return normalizeHttpUrls([...fromAttrs, ...directMatches])
}

// 从任意文本中提取 http/https URL 并去重。
function extractHttpUrls(text: string): string[] {
  return normalizeHttpUrls(text.match(/https?:\/\/[^\s"'<>]+/gi) ?? [])
}

function formatSize(fileSize: number): string {
  if (fileSize < 1024 * 1024) {
    return `${Math.max(1, Math.round(fileSize / 1024))}KB`
  }
  return `${(fileSize / (1024 * 1024)).toFixed(1)}MB`
}

function detectQualityHint(width?: number, height?: number): QualityHint | undefined {
  if (typeof width !== 'number' || typeof height !== 'number' || width <= 0 || height <= 0) {
    return undefined
  }
  const area = width * height
  if ((width >= 1000 || height >= 1000) && area >= 500000) {
    return 'high'
  }
  if (width < 320 || height < 320 || area < 120000) {
    return 'low'
  }
  return 'normal'
}

function imageDimensionsText(image: ImageItem): string {
  if (typeof image.width === 'number' && typeof image.height === 'number') {
    return `${image.width}x${image.height}`
  }
  return '尺寸未知'
}

function isLowQualityImage(image: ImageItem): boolean {
  if (image.qualityHint === 'low') {
    return true
  }
  if (typeof image.width === 'number' && typeof image.height === 'number') {
    const area = image.width * image.height
    return image.width < 320 || image.height < 320 || area < 120000
  }
  return false
}

async function addImagesFromFiles(files: File[]): Promise<void> {
  const imageFiles = files.filter((file) => file.type.startsWith('image/'))
  if (imageFiles.length === 0) {
    showMessage('拖拽内容不包含可解析的图片文件。')
    return
  }

  const limitedFiles = imageFiles.slice(0, MAX_UPLOAD_COUNT)
  const skippedByCount = imageFiles.length - limitedFiles.length
  const errors: string[] = []
  const details: string[] = []
  let addedCount = 0
  let duplicateCount = 0
  let lowQualityCount = 0

  await runWithResolvingLock(async () => {
    for (const file of limitedFiles) {
      if (file.size > MAX_UPLOAD_SIZE_BYTES) {
        errors.push(`${file.name} 超过 10MB 限制。`)
        continue
      }

      try {
        const dataUrl = await readFileAsDataUrl(file)
        const exists = config.value.images.some((item) => item.src === dataUrl)
        if (exists) {
          duplicateCount += 1
          continue
        }

        const dimensions = await getImageDimensions(dataUrl)
        const qualityHint = detectQualityHint(dimensions?.width, dimensions?.height)
        const image = createImage(dataUrl, {
          resolveStatus: 'ok',
          width: dimensions?.width,
          height: dimensions?.height,
          qualityHint,
        })
        config.value.images.push(image)
        addedCount += 1
        if (qualityHint === 'low') {
          lowQualityCount += 1
        }

        const sizeText = formatSize(file.size)
        if (dimensions) {
          details.push(`${file.name} (${dimensions.width}x${dimensions.height}, ${sizeText})`)
        } else {
          details.push(`${file.name} (${sizeText})`)
        }
      } catch {
        errors.push(`${file.name} 读取失败。`)
      }
    }
  })

  imageUrlInput.value = ''
  const messageParts: string[] = []

  appendMessagePart(messageParts, addedCount > 0, `已添加 ${addedCount} 张拖拽图片。`)
  if (details.length > 0) {
    const preview = details.slice(0, 2).join('；')
    messageParts.push(`解析信息：${preview}${details.length > 2 ? '；...' : ''}`)
  }
  appendMessagePart(messageParts, duplicateCount > 0, `已跳过 ${duplicateCount} 张重复图片。`)
  appendMessagePart(messageParts, lowQualityCount > 0, `${lowQualityCount} 张图片分辨率偏低，导出可能模糊。`)
  appendMessagePart(messageParts, skippedByCount > 0, `单次最多解析 ${MAX_UPLOAD_COUNT} 张，已忽略 ${skippedByCount} 张。`)
  appendMessagePart(messageParts, errors.length > 0, errors.join(' '))

  showMessage(joinSummary(messageParts, '未添加新图片。'))
}

// 解析文本中的链接并在浏览器内验证图片可访问性。
async function addImagesFromText(rawText: string): Promise<void> {
  const urls = extractHttpUrls(rawText)
  if (urls.length === 0) {
    showMessage('请输入或拖拽包含 http(s) 的图片直链。')
    return
  }

  const limitedUrls = urls.slice(0, MAX_UPLOAD_COUNT)
  const skippedByCount = urls.length - limitedUrls.length
  const existingUrls = new Set(config.value.images.map((item) => item.originalUrl ?? item.src))

  let addedCount = 0
  let failedCount = 0
  let skippedDuplicateCount = 0
  let lowQualityCount = 0
  const parsedDetails: string[] = []

  await runWithResolvingLock(async () => {
    for (const url of limitedUrls) {
      if (existingUrls.has(url)) {
        skippedDuplicateCount += 1
        continue
      }

      const image = await resolveDirectImage(url)
      config.value.images.push(image)
      existingUrls.add(url)
      addedCount += 1

      if (image.resolveStatus === 'failed') {
        failedCount += 1
        markImageAsFailed(image)
      } else {
        if (typeof image.width !== 'number' || typeof image.height !== 'number') {
          const measured = await getImageDimensions(image.src)
          if (measured) {
            image.width = measured.width
            image.height = measured.height
          }
        }

        const hint = image.qualityHint ?? detectQualityHint(image.width, image.height)
        if (hint) {
          image.qualityHint = hint
        }
        if (hint === 'low') {
          lowQualityCount += 1
        }

        if (typeof image.width === 'number' && typeof image.height === 'number') {
          parsedDetails.push(`${image.width}x${image.height}`)
        }
      }
    }
  })

  imageUrlInput.value = ''

  const messageParts: string[] = []
  appendMessagePart(messageParts, addedCount > 0, `已添加 ${addedCount} 条链接。`)
  appendMessagePart(messageParts, failedCount > 0, `${failedCount} 张图片添加失败，仅支持可访问的图片直链。`)
  appendMessagePart(messageParts, skippedDuplicateCount > 0, `已跳过 ${skippedDuplicateCount} 条重复链接。`)
  appendMessagePart(messageParts, lowQualityCount > 0, `${lowQualityCount} 张图片分辨率偏低，导出可能模糊。`)
  appendMessagePart(messageParts, skippedByCount > 0, `单次最多解析 ${MAX_UPLOAD_COUNT} 条，已忽略 ${skippedByCount} 条。`)
  if (parsedDetails.length > 0) {
    const preview = parsedDetails.slice(0, 3).join('，')
    messageParts.push(`图片信息：${preview}${parsedDetails.length > 3 ? '，...' : ''}`)
  }

  showMessage(joinSummary(messageParts, '未添加新图片，请检查链接格式或是否重复。'))
}

async function addImagesFromInput(): Promise<void> {
  await addImagesFromText(imageUrlInput.value)
}

function triggerFilePicker(): void {
  if (isResolvingInput.value) {
    return
  }
  filePickerRef.value?.click()
}

async function onFilePickerChange(event: Event): Promise<void> {
  const target = event.target
  if (!(target instanceof HTMLInputElement) || !target.files) {
    return
  }

  const files = Array.from(target.files)
  target.value = ''
  await addImagesFromFiles(files)
}

function onSearchDragOver(event: DragEvent): void {
  event.preventDefault()
  isSearchDropActive.value = true
}

function onSearchDragLeave(): void {
  isSearchDropActive.value = false
}

async function onSearchDrop(event: DragEvent): Promise<void> {
  event.preventDefault()
  isSearchDropActive.value = false
  if (isResolvingInput.value || !event.dataTransfer) {
    return
  }

  const droppedFiles = Array.from(event.dataTransfer.files ?? [])
  if (droppedFiles.length > 0) {
    await addImagesFromFiles(droppedFiles)
    return
  }

  const droppedHtml = event.dataTransfer.getData('text/html')
  const droppedText =
    event.dataTransfer.getData('text/uri-list') ||
    event.dataTransfer.getData('text/plain') ||
    droppedHtml

  const urlsFromHtml = extractUrlsFromHtml(droppedHtml)
  const mergedText = urlsFromHtml.length > 0 ? urlsFromHtml.join('\n') : droppedText

  if (!mergedText.trim()) {
    showMessage('未检测到可用链接，请拖拽图片直链地址。')
    return
  }

  imageUrlInput.value = mergedText.trim()
  await addImagesFromText(mergedText)
}

function removeImage(id: string): void {
  config.value.images = config.value.images.filter((item) => item.id !== id)
  if (failedImages.value[id]) {
    const next = { ...failedImages.value }
    delete next[id]
    failedImages.value = next
  }
  clearMessage()
}

function markImageFailed(image: ImageItem): void {
  markImageAsFailed(image)
}

function isImageFailed(image: ImageItem): boolean {
  return image.resolveStatus === 'failed' || !!failedImages.value[image.id]
}

function openImagePreview(image: ImageItem): void {
  if (isImageFailed(image)) {
    return
  }
  previewImageSrc.value = image.src
}

function closeImagePreview(): void {
  previewImageSrc.value = ''
}

function onWindowKeyDown(event: KeyboardEvent): void {
  if (event.key === 'Escape' && previewImageSrc.value) {
    closeImagePreview()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onWindowKeyDown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onWindowKeyDown)
})

function startBoard(): void {
  const normalizedTiers = config.value.tiers.map((tier, index) => ({
    ...tier,
    label: tier.label.trim() || `Tier ${index + 1}`,
    color: isHexColor(tier.color) ? tier.color.toLowerCase() : getPresetColor(index),
  }))

  if (normalizedTiers.length < MIN_TIERS || normalizedTiers.length > MAX_TIERS) {
    showMessage(`等级数量必须在 ${MIN_TIERS} 到 ${MAX_TIERS} 之间。`)
    return
  }

  if (config.value.images.length === 0) {
    showMessage('请至少添加一张图片后再继续。')
    return
  }

  config.value = {
    title: config.value.title.trim() || '图片榜单',
    tiers: normalizedTiers,
    images: [...config.value.images],
  }

  saveBoardConfig(config.value)
  resetBoardState(config.value)
  clearMessage()
  router.push('/board')
}
</script>

<template>
  <section class="setup-page">
    <header class="setup-header">
      <h1>榜单配置</h1>
      <p>先设置标题、等级文案与颜色，并添加图片后再进入榜单。</p>
    </header>

    <section class="panel">
      <label class="field-label" for="board-title">榜单标题</label>
      <input id="board-title" v-model="config.title" class="text-input" maxlength="60" placeholder="请输入榜单标题" />
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>等级设置</h2>
        <span>{{ tierCount }} / {{ MAX_TIERS }}</span>
      </div>

      <draggable
        v-model="config.tiers"
        item-key="id"
        class="tier-list"
        handle=".tier-drag-handle"
        ghost-class="tier-ghost"
        chosen-class="tier-chosen"
        :animation="180"
      >
        <template #item="{ element: tier, index }">
          <div class="tier-item">
            <button type="button" class="tier-drag-handle" title="拖拽排序" aria-label="拖拽排序">拖拽</button>
            <input v-model="tier.label" class="text-input tier-label" maxlength="20" placeholder="等级文案" />

            <div class="tier-color-tools">
              <button
                v-for="preset in PRESET_LEVEL_COLORS"
                :key="`${tier.id}-${preset}`"
                type="button"
                class="color-swatch"
                :class="{ 'color-swatch--active': tier.color === preset }"
                :style="{ backgroundColor: preset }"
                :title="preset"
                @click="setTierColor(tier, preset)"
              />
              <input class="color-picker" type="color" :value="tier.color" @input="onTierColorInput(tier, $event)" />
            </div>

            <button type="button" class="danger-btn" :disabled="tierCount <= MIN_TIERS" @click="removeTier(index)">删除</button>
          </div>
        </template>
      </draggable>

      <button type="button" class="primary-btn" :disabled="tierCount >= MAX_TIERS" @click="addTier">新增等级</button>
    </section>

    <section class="panel">
      <div class="panel-head">
        <h2>图片列表</h2>
        <span>{{ imageCount }} 张</span>
      </div>

      <div class="image-actions">
        <div
          class="search-drop-zone"
          :class="{ 'search-drop-zone--active': isSearchDropActive }"
          @dragover="onSearchDragOver"
          @dragleave="onSearchDragLeave"
          @drop="onSearchDrop"
        >
          <input ref="filePickerRef" class="file-picker-hidden" type="file" accept="image/*" multiple @change="onFilePickerChange" />
          <div class="url-row">
            <button type="button" class="primary-btn" :disabled="isResolvingInput" @click="triggerFilePicker">
              选择文件
            </button>
            <input
              v-model="imageUrlInput"
              class="text-input"
              placeholder="拖拽图片到这里，或输入图片直链 URL，按 Enter 添加。"
              @keydown.enter.prevent="addImagesFromInput"
            />
          </div>
        </div>
      </div>

      <p class="hint">
        可点击“选择文件”添加本地图片，或粘贴/拖拽图片直链 URL（单次最多 {{ MAX_UPLOAD_COUNT }} 条/张，不支持网页链接解析）。
      </p>

      <div v-if="config.images.length > 0" class="image-grid">
        <div v-for="image in config.images" :key="image.id" class="image-card">
          <img
            v-if="!isImageFailed(image)"
            :src="image.src"
            alt="preview"
            class="previewable-image"
            role="button"
            tabindex="0"
            @error="markImageFailed(image)"
            @click="openImagePreview(image)"
            @keydown.enter.prevent="openImagePreview(image)"
            @keydown.space.prevent="openImagePreview(image)"
          />
          <div v-else class="image-fallback">
            <span>图片不可用</span>
            <span>{{ image.resolveReason ?? '请更换为可访问的图片地址。' }}</span>
          </div>
          <div class="image-meta">
            <span class="image-dimensions">{{ imageDimensionsText(image) }}</span>
            <span v-if="isLowQualityImage(image)" class="image-quality-low">低清风险</span>
          </div>
          <button type="button" class="remove-image" @click="removeImage(image.id)">删除</button>
        </div>
      </div>
      <div v-else class="empty-state">尚未添加图片。</div>
    </section>

    <p v-if="message" class="message">{{ message }}</p>

    <footer class="setup-footer">
      <button type="button" class="primary-btn start-btn" @click="startBoard">进入榜单</button>
    </footer>

    <div v-if="previewImageSrc" class="image-preview-mask" @click="closeImagePreview">
      <div class="image-preview-dialog" @click.stop>
        <button type="button" class="image-preview-close" aria-label="关闭预览" @click="closeImagePreview">关闭</button>
        <img class="image-preview-full" :src="previewImageSrc" alt="full preview" />
      </div>
    </div>
  </section>
</template>

<style scoped>
.setup-page {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px;
  color: var(--color-text-primary);
}

.setup-header h1 {
  margin: 0;
  font-size: 30px;
}

.setup-header p {
  margin: 8px 0 0;
  color: #374151;
}

.panel {
  margin-top: 18px;
  border: 1px solid var(--color-border-default);
  background: var(--color-bg-panel);
  border-radius: 10px;
  padding: 16px;
}

.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 12px;
}

.panel-head h2 {
  margin: 0;
  font-size: 18px;
}

.panel-head span {
  color: var(--color-text-secondary);
  font-size: 13px;
}

.field-label {
  display: block;
  margin-bottom: 8px;
  font-weight: 600;
}

.text-input {
  width: 100%;
  height: 36px;
  border: 1px solid var(--color-border-muted);
  border-radius: 8px;
  padding: 0 10px;
  font-size: 14px;
}

.tier-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 12px;
}

.tier-item {
  display: grid;
  grid-template-columns: auto minmax(140px, 1fr) auto auto;
  gap: 10px;
  align-items: center;
}

.tier-drag-handle {
  border: 1px solid var(--color-border-muted);
  border-radius: 8px;
  background: #f9fafb;
  height: 34px;
  padding: 0 10px;
  cursor: grab;
  color: #374151;
  font-size: 12px;
}

.tier-ghost {
  opacity: 0.45;
}

.tier-chosen {
  outline: 2px solid var(--color-text-primary);
  outline-offset: 2px;
}

.tier-color-tools {
  display: flex;
  align-items: center;
  gap: 6px;
}

.color-swatch {
  width: 18px;
  height: 18px;
  border: 1px solid var(--color-text-primary);
  border-radius: 3px;
  padding: 0;
  cursor: pointer;
}

.color-swatch--active {
  outline: 2px solid var(--color-text-primary);
  outline-offset: 1px;
}

.color-picker {
  width: 34px;
  height: 22px;
  border: 1px solid var(--color-text-primary);
  border-radius: 4px;
  background: transparent;
  padding: 0;
  cursor: pointer;
}

.primary-btn,
.danger-btn {
  border: 1px solid var(--color-text-primary);
  border-radius: 8px;
  background: var(--color-bg-panel);
  height: 34px;
  padding: 0 12px;
  cursor: pointer;
  font-weight: 600;
}

.primary-btn {
  background: var(--color-text-primary);
  color: var(--color-bg-panel);
}

.danger-btn {
  border-color: #991b1b;
  color: #991b1b;
}

.danger-btn:disabled,
.primary-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.image-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.search-drop-zone {
  border: 1px dashed var(--color-border-muted);
  border-radius: 10px;
  padding: 10px;
  background: var(--color-bg-panel);
  transition: border-color 120ms ease, background-color 120ms ease;
}

.search-drop-zone--active {
  border-color: #065f46;
  background: #ecfdf5;
}

.url-row {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 8px;
}

.file-picker-hidden {
  display: none;
}

.hint {
  margin: 10px 0 0;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.image-grid {
  margin-top: 12px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 10px;
}

.image-card {
  border: 1px solid var(--color-border-muted);
  border-radius: 8px;
  overflow: hidden;
  background: #f3f4f6;
}

.image-card img {
  width: 100%;
  aspect-ratio: 16 / 9;
  object-fit: cover;
  display: block;
}

.previewable-image {
  cursor: zoom-in;
}

.image-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-top: 1px solid var(--color-border-muted);
  background: var(--color-bg-panel);
  font-size: 12px;
  color: var(--color-text-secondary);
}

.image-dimensions {
  white-space: nowrap;
}

.image-quality-low {
  color: #b91c1c;
  font-weight: 600;
}

.image-fallback {
  width: 100%;
  aspect-ratio: 16 / 9;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: var(--color-text-secondary);
  font-size: 12px;
  background: var(--color-bg-muted);
  text-align: center;
  padding: 8px;
}

.remove-image {
  width: 100%;
  border: 0;
  background: var(--color-bg-panel);
  height: 30px;
  cursor: pointer;
}

.empty-state {
  margin-top: 12px;
  border: 1px dashed var(--color-border-muted);
  border-radius: 8px;
  padding: 20px;
  text-align: center;
  color: var(--color-text-secondary);
}

.message {
  margin-top: 14px;
  color: #b91c1c;
  font-size: 14px;
}

.image-preview-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.72);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 40;
  padding: 20px;
}

.image-preview-dialog {
  position: relative;
  max-width: min(92vw, 1400px);
  max-height: 90vh;
  background: #111827;
  border-radius: 10px;
  padding: 12px;
}

.image-preview-close {
  position: absolute;
  top: 8px;
  right: 8px;
  border: 0;
  border-radius: 6px;
  padding: 4px 8px;
  background: rgba(255, 255, 255, 0.92);
  cursor: pointer;
}

.image-preview-full {
  display: block;
  max-width: min(90vw, 1360px);
  max-height: calc(90vh - 24px);
  object-fit: contain;
}

.setup-footer {
  margin: 18px 0 8px;
}

.start-btn {
  height: 40px;
  padding: 0 18px;
}

@media (max-width: 768px) {
  .setup-page {
    padding: 14px;
  }

  .tier-item {
    grid-template-columns: 1fr;
    align-items: stretch;
  }

  .tier-color-tools {
    flex-wrap: wrap;
  }
}
</style>
