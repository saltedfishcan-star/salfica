<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import type { BoardConfig, BoardState, ImageItem } from '../lib/board'
import { loadBoardConfig, loadBoardState } from '../lib/board'

const router = useRouter()

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

function isImageFailed(item: ImageItem): boolean {
  return item.resolveStatus === 'failed' || !!failedImages.value[item.id]
}

function imageFailReason(item: ImageItem): string {
  return item.resolveReason ?? '链接无效或被防盗链拦截。'
}

function goBack(): void {
  router.push('/board')
}
</script>

<template>
  <section v-if="config && config.images.length > 0" class="capture-page">
    <div class="capture-content">
      <div ref="rankTableRef" class="rank-table">
        <div v-for="tier in config.tiers" :key="tier.id" class="rank-row">
          <div class="rank-level" :style="{ backgroundColor: tier.color, color: '#111827' }">
            <span class="rank-level-label">{{ tier.label }}</span>
          </div>
          <div class="rank-content">
            <div class="block-list rank-list">
              <div v-for="item in board.rankings[tier.id]" :key="item.id" class="image-block">
                <img v-if="!isImageFailed(item)" :src="item.src" alt="rank item" @error="markImageFailed(item)" />
                <div v-else class="image-fallback">
                  <span>{{ imageFailReason(item) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div class="capture-actions">
        <button type="button" class="back-btn" @click="goBack">返回榜单</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.capture-page {
  --thumb-max-width: 100px;
  --thumb-max-height: 60px;
  min-height: 100vh;
  width: 100%;
  background: #ffffff;
  padding: 20px;
  display: flex;
  align-items: flex-start;
  justify-content: center;
}

.capture-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  width: 100%;
}

.rank-table {
  width: min(1200px, calc(100vw - 40px));
  border: 1px solid var(--color-border-default);
  background: var(--color-bg-panel);
}

.rank-row {
  display: grid;
  grid-template-columns: 140px 1fr;
  min-height: calc(var(--thumb-max-height) + 24px);
}

.rank-row + .rank-row {
  border-top: 1px solid var(--color-border-default);
}

.rank-level {
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  border-right: 1px solid var(--color-border-default);
  padding: 8px;
}

.rank-level-label {
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
  word-break: break-word;
}

.rank-content {
  padding: 12px;
}

.block-list {
  display: flex;
  flex-wrap: wrap;
  align-content: flex-start;
  align-items: flex-start;
  gap: 0;
  min-height: var(--thumb-max-height);
}

.image-block {
  width: fit-content;
  height: fit-content;
  flex: 0 0 auto;
  max-width: var(--thumb-max-width);
  max-height: var(--thumb-max-height);
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.image-block img {
  width: auto;
  height: auto;
  max-width: var(--thumb-max-width);
  max-height: var(--thumb-max-height);
  object-fit: contain;
  display: block;
}

.image-fallback {
  width: var(--thumb-max-width);
  height: var(--thumb-max-height);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--color-text-secondary);
  font-size: 11px;
  text-align: center;
  padding: 6px;
}

.capture-actions {
  width: min(1200px, calc(100vw - 40px));
  display: flex;
  justify-content: center;
}

.back-btn {
  border: 1px solid var(--color-text-primary);
  border-radius: 999px;
  height: 42px;
  padding: 0 20px;
  font-weight: 700;
  cursor: pointer;
  background: var(--color-text-primary);
  color: var(--color-bg-panel);
}

@media (max-width: 768px) {
  .capture-page {
    --thumb-max-width: 84px;
    --thumb-max-height: 54px;
    padding: 8px;
  }

  .rank-table {
    width: calc(100vw - 16px);
  }

  .capture-actions {
    width: calc(100vw - 16px);
  }

  .rank-row {
    grid-template-columns: 90px 1fr;
  }

  .rank-level-label {
    font-size: 16px;
  }
}
</style>
