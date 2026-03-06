<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import draggable from 'vuedraggable'
import type { BoardConfig, BoardState, ImageItem } from '../lib/board'
import { loadBoardConfig, loadBoardState, saveBoardState } from '../lib/board'

const router = useRouter()
const DRAG_GROUP = { name: 'ranking', pull: true, put: true }

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
const previewImageSrc = ref<string>('')

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

function goCapture(): void {
  router.push('/capture')
}

function isImageFailed(item: ImageItem): boolean {
  return item.resolveStatus === 'failed' || !!failedImages.value[item.id]
}

function imageFailReason(item: ImageItem): string {
  return item.resolveReason ?? '链接无效或被防盗链拦截。'
}

function openImagePreview(item: ImageItem): void {
  if (isImageFailed(item)) {
    return
  }
  previewImageSrc.value = item.src
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
</script>

<template>
  <section v-if="config && config.images.length > 0" class="ranking-page">
    <header class="heading">
      <h1>{{ config.title }}</h1>
    </header>

    <div class="rank-table">
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
                <img
                  v-if="!isImageFailed(element)"
                  :src="element.src"
                  alt="rank item"
                  class="previewable-image"
                  role="button"
                  tabindex="0"
                  @error="markImageFailed(element)"
                  @click.stop="openImagePreview(element)"
                  @keydown.enter.prevent="openImagePreview(element)"
                  @keydown.space.prevent="openImagePreview(element)"
                />
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
            <img
              v-if="!isImageFailed(element)"
              :src="element.src"
              alt="pool item"
              class="previewable-image"
              role="button"
              tabindex="0"
              @error="markImageFailed(element)"
              @click.stop="openImagePreview(element)"
              @keydown.enter.prevent="openImagePreview(element)"
              @keydown.space.prevent="openImagePreview(element)"
            />
            <div v-else class="image-fallback">
              <span>{{ imageFailReason(element) }}</span>
              <span>请返回配置页更换链接</span>
            </div>
          </div>
        </template>
      </draggable>
    </section>

    <div class="floating-actions">
      <button type="button" class="floating-button floating-download" @click="goCapture">
        截图页
      </button>
      <button type="button" class="floating-button floating-edit" @click="goSetup">编辑配置</button>
    </div>

    <div v-if="previewImageSrc" class="image-preview-mask" @click="closeImagePreview">
      <div class="image-preview-dialog" @click.stop>
        <img class="image-preview-full" :src="previewImageSrc" alt="full preview" />
      </div>
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
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--color-border-strong);
  background: var(--color-bg-muted);
  overflow: hidden;
  cursor: grab;
  user-select: none;
}

.image-block img {
  width: auto;
  height: auto;
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
  display: block;
}

.previewable-image {
  cursor: zoom-in;
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
  background: transparent;
  border-radius: 0;
  padding: 0;
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
  max-height: 90vh;
  object-fit: contain;
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

}
</style>
