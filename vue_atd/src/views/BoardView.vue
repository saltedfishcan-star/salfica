<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import draggable from 'vuedraggable'
import type { BoardConfig, BoardState, ImageItem } from '../lib/board'
import { loadBoardConfig, loadBoardState, saveBoardState } from '../lib/board'

const router = useRouter()
const DRAG_GROUP = { name: 'ranking', pull: true, put: true }
const EXPORT_SCALE = 3

// 没有配置时回到配置页，避免空页面。
const config = ref<BoardConfig | null>(loadBoardConfig())
if (!config.value || config.value.images.length === 0) {
  router.replace('/setup')
}

const board = ref<BoardState>(
  config.value && config.value.images.length > 0
    ? loadBoardState(config.value)
    : {
        rankings: {},
        pool: [],
      },
)

const failedImages = ref<Record<string, boolean>>({})
const rankTableRef = ref<HTMLElement | null>(null)
const isDownloading = ref(false)
const downloadStatus = ref<{ type: 'success' | 'error' | 'warning'; message: string } | null>(null)
let downloadStatusTimer: ReturnType<typeof setTimeout> | null = null

function markImageFailed(item: ImageItem): void {
  failedImages.value = {
    ...failedImages.value,
    [item.id]: true,
  }
  if (item.resolveStatus !== 'failed') {
    item.resolveStatus = 'failed'
    item.resolveReason = item.resolveReason ?? '浏览器加载图片失败。'
  }
}

watch(
  board,
  (value) => {
    saveBoardState(value)
  },
  { deep: true },
)

function goSetup(): void {
  router.push('/setup')
}

function isImageFailed(item: ImageItem): boolean {
  return item.resolveStatus === 'failed' || !!failedImages.value[item.id]
}

function imageFailReason(item: ImageItem): string {
  return item.resolveReason ?? '链接无效或被防盗链拦截。'
}
function clearDownloadStatus(): void {
  if (downloadStatusTimer !== null) {
    clearTimeout(downloadStatusTimer)
    downloadStatusTimer = null
  }
}

function showDownloadStatus(type: 'success' | 'error' | 'warning', message: string): void {
  downloadStatus.value = { type, message }
  clearDownloadStatus()
  downloadStatusTimer = setTimeout(() => {
    downloadStatus.value = null
  }, 3200)
}

function pad2(value: number): string {
  return value.toString().padStart(2, '0')
}

function getDownloadFilename(): string {
  const now = new Date()
  const date = `${now.getFullYear()}${pad2(now.getMonth() + 1)}${pad2(now.getDate())}`
  const time = `${pad2(now.getHours())}${pad2(now.getMinutes())}${pad2(now.getSeconds())}`
  return `rank-table-${date}-${time}.png`
}

// 导出前等待图片可用，降低 html2canvas 拉伸或空白概率。
async function waitForImagesReady(container: HTMLElement): Promise<void> {
  const images = Array.from(container.querySelectorAll('img'))
  if (images.length === 0) {
    return
  }

  await Promise.all(
    images.map(async (img) => {
      if (img.complete) {
        return
      }
      try {
        await img.decode()
      } catch {
        await new Promise<void>((resolve) => {
          const finish = () => resolve()
          img.addEventListener('load', finish, { once: true })
          img.addEventListener('error', finish, { once: true })
          setTimeout(finish, 1200)
        })
      }
    }),
  )
}

// 仅导出 rank-table 区域为 PNG。
async function downloadRankTable(): Promise<void> {
  if (isDownloading.value) {
    return
  }

  const rankTable = rankTableRef.value
  if (!rankTable) {
    showDownloadStatus('error', 'Rank table not found.')
    return
  }

  isDownloading.value = true

  try {
    await waitForImagesReady(rankTable)
    const { default: html2canvas } = await import('html2canvas')
    const canvas = await html2canvas(rankTable, {
      backgroundColor: '#ffffff',
      scale: Math.max(window.devicePixelRatio || 1, EXPORT_SCALE),
      width: rankTable.scrollWidth,
      height: rankTable.scrollHeight,
      useCORS: true,
      allowTaint: false,
      imageTimeout: 15000,
      logging: false,
      onclone: (clonedDocument) => {
        const clonedImages = clonedDocument.querySelectorAll('.image-block img')
        clonedImages.forEach((node) => {
          const image = node as HTMLImageElement
          const src = image.getAttribute('src')
          if (!src) {
            return
          }

          const replacement = clonedDocument.createElement('div')
          const safeSrc = src.replace(/["\\]/g, '\\$&')
          replacement.style.width = '100%'
          replacement.style.height = '100%'
          replacement.style.display = 'block'
          replacement.style.backgroundImage = `url("${safeSrc}")`
          replacement.style.backgroundSize = 'cover'
          replacement.style.backgroundPosition = 'center center'
          replacement.style.backgroundRepeat = 'no-repeat'
          image.replaceWith(replacement)
        })
      },
    })

    const downloadLink = document.createElement('a')
    downloadLink.href = canvas.toDataURL('image/png')
    downloadLink.download = getDownloadFilename()
    downloadLink.click()

    if (Object.keys(failedImages.value).length > 0) {
      showDownloadStatus('warning', 'Downloaded. Some images may be missing.')
    } else {
      showDownloadStatus('success', 'Downloaded rank-table PNG.')
    }
  } catch (error) {
    console.error('Failed to download rank-table image:', error)
    showDownloadStatus('error', 'Download failed. External image restrictions may apply.')
  } finally {
    isDownloading.value = false
  }
}

onUnmounted(() => {
  clearDownloadStatus()
})
</script>

<template>
  <section v-if="config && config.images.length > 0" class="ranking-page">
    <header class="heading">
      <h1>{{ config.title }}</h1>
    </header>

    <div ref="rankTableRef" class="rank-table">
      <div v-for="tier in config.tiers" :key="tier.id" class="rank-row">
        <div class="rank-level" :style="{ backgroundColor: tier.color, color: '#111827' }">
          <span class="rank-level-label">{{ tier.label }}</span>
        </div>
        <div class="rank-content">
          <draggable
            v-model="board.rankings[tier.id]"
            item-key="id"
            class="block-list rank-list"
            :group="DRAG_GROUP"
            ghost-class="block-ghost"
            chosen-class="block-chosen"
            :animation="180"
          >
            <template #item="{ element }">
              <div class="image-block">
                <img v-if="!isImageFailed(element)" :src="element.src" alt="rank item" @error="markImageFailed(element)" />
                <div v-else class="image-fallback">
                  <span>{{ imageFailReason(element) }}</span>
                  <span>请返回配置页更换链接</span>
                </div>
              </div>
            </template>
          </draggable>
        </div>
      </div>
    </div>

    <section class="pool-panel">
      <div class="pool-head">
        <span>剩余 {{ board.pool.length }} 张</span>
      </div>

      <draggable
        v-model="board.pool"
        item-key="id"
        class="block-list pool-list"
        :group="DRAG_GROUP"
        ghost-class="block-ghost"
        chosen-class="block-chosen"
        :animation="180"
      >
        <template #item="{ element }">
          <div class="image-block">
            <img v-if="!isImageFailed(element)" :src="element.src" alt="pool item" @error="markImageFailed(element)" />
            <div v-else class="image-fallback">
              <span>{{ imageFailReason(element) }}</span>
              <span>请返回配置页更换链接</span>
            </div>
          </div>
        </template>
      </draggable>
    </section>

    <p v-if="downloadStatus" class="download-status" :class="`download-status--${downloadStatus.type}`">
      {{ downloadStatus.message }}
    </p>

    <div class="floating-actions">
      <button type="button" class="floating-button floating-download" :disabled="isDownloading" @click="downloadRankTable">
        {{ isDownloading ? 'Downloading...' : 'Download PNG' }}
      </button>
      <button type="button" class="floating-button floating-edit" @click="goSetup">编辑配置</button>
    </div>
  </section>
</template>

<style scoped>
.ranking-page {
  --page-scale: 1;
  --card-width: 100px;
  --card-height: 60px;
  --card-gap: 0;
  max-width: calc(1200px * var(--page-scale));
  margin: 0 auto;
  padding: calc(24px * var(--page-scale));
}

.heading {
  margin-bottom: calc(20px * var(--page-scale));
}

.heading h1 {
  margin: 0;
  font-size: calc(32px * var(--page-scale));
}

.rank-table {
  border: calc(1px * var(--page-scale)) solid var(--color-border-default);
  background: var(--color-bg-panel);
}

.rank-row {
  display: grid;
  grid-template-columns: calc(140px * var(--page-scale)) 1fr;
  min-height: calc(var(--card-height) + 24px * var(--page-scale));
}

.rank-row + .rank-row {
  border-top: calc(1px * var(--page-scale)) solid var(--color-border-default);
}

.rank-level {
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  border-right: calc(1px * var(--page-scale)) solid var(--color-border-default);
  padding: calc(8px * var(--page-scale));
}

.rank-level-label {
  font-size: calc(24px * var(--page-scale));
  font-weight: 700;
  line-height: 1.2;
  word-break: break-word;
}

.rank-content {
  padding: calc(12px * var(--page-scale));
}

.pool-panel {
  margin-top: calc(18px * var(--page-scale));
  border: calc(1px * var(--page-scale)) solid var(--color-border-default);
  padding: calc(12px * var(--page-scale));
  height: 25vh;
  min-height: 25vh;
  max-height: 25vh;
  display: flex;
  flex-direction: column;
  background: var(--color-bg-panel);
}

.pool-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: calc(12px * var(--page-scale));
  margin-bottom: calc(12px * var(--page-scale));
}

.pool-head span {
  color: var(--color-text-secondary);
  font-size: calc(13px * var(--page-scale));
}

.block-list {
  display: flex;
  flex-wrap: wrap;
  align-content: flex-start;
  align-items: flex-start;
  gap: var(--card-gap);
  min-height: calc(90px * var(--page-scale));
}

.rank-list {
  min-height: var(--card-height);
}

.pool-list {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
}

.image-block {
  width: var(--card-width);
  flex: 0 0 var(--card-width);
  height: var(--card-height);
  border: 1px solid var(--color-border-strong);
  background: var(--color-bg-muted);
  overflow: hidden;
  cursor: grab;
  user-select: none;
}

.image-block img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.image-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  color: var(--color-text-secondary);
  font-size: 11px;
  text-align: center;
  padding: 6px;
}

.block-ghost {
  opacity: 0.35;
}

.block-chosen {
  /* 使用内侧描边，避免拖拽高亮看起来比图片块更大。 */
  box-shadow: inset 0 0 0 2px var(--color-text-primary);
}

.floating-actions {
  position: fixed;
  right: 24px;
  top: 24px;
  display: flex;
  align-items: center;
  gap: 10px;
  z-index: 20;
}

.floating-button {
  border: 1px solid var(--color-text-primary);
  border-radius: 999px;
  height: 42px;
  padding: 0 16px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: 0 6px 14px rgba(0, 0, 0, 0.22);
}

.floating-button:disabled {
  cursor: not-allowed;
  opacity: 0.7;
}

.floating-download {
  background: var(--color-bg-panel);
  color: var(--color-text-primary);
}

.floating-edit {
  background: var(--color-text-primary);
  color: var(--color-bg-panel);
}

.download-status {
  position: fixed;
  right: 24px;
  top: 76px;
  max-width: min(420px, calc(100vw - 32px));
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.3;
  border: 1px solid transparent;
  background: #f3f4f6;
  color: var(--color-text-primary);
  z-index: 20;
}

.download-status--success {
  border-color: #065f46;
  background: #ecfdf5;
  color: #065f46;
}

.download-status--warning {
  border-color: #92400e;
  background: #fffbeb;
  color: #92400e;
}

.download-status--error {
  border-color: #991b1b;
  background: #fef2f2;
  color: #991b1b;
}

@media (max-width: 768px) {
  .ranking-page {
    --page-scale: 0.65;
    --card-width: 84px;
    --card-height: 54px;
    padding: calc(16px * var(--page-scale));
  }

  .rank-row {
    grid-template-columns: calc(110px * var(--page-scale)) 1fr;
  }

  .rank-level-label {
    font-size: calc(18px * var(--page-scale));
  }

  .floating-actions {
    right: 14px;
    top: 14px;
    gap: 8px;
  }

  .floating-button {
    height: 38px;
    padding: 0 14px;
  }

  .download-status {
    right: 14px;
    top: 60px;
    max-width: calc(100vw - 28px);
  }
}
</style>
