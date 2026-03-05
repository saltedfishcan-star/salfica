export interface TierConfig {
  id: string
  label: string
  color: string
}

export type ResolveStatus = 'ok' | 'failed'

export interface ImageItem {
  id: string
  src: string
  originalUrl?: string
  resolveStatus?: ResolveStatus
  resolveReason?: string
}

export interface BoardConfig {
  title: string
  tiers: TierConfig[]
  images: ImageItem[]
}

export interface BoardState {
  rankings: Record<string, ImageItem[]>
  pool: ImageItem[]
}

// localStorage 键名统一管理，避免散落魔法字符串。
export const BOARD_CONFIG_KEY = 'atd_board_config_v1'
export const BOARD_STATE_KEY = 'atd_board_state_v1'
export const MIN_TIERS = 2
export const MAX_TIERS = 10
export const MAX_UPLOAD_COUNT = 50
export const MAX_UPLOAD_SIZE_BYTES = 10 * 1024 * 1024
export const PRESET_LEVEL_COLORS: string[] = ['#ef4444', '#f97316', '#facc15', '#22c55e', '#06b6d4', '#3b82f6', '#8b5cf6']

function createId(prefix: string): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return `${prefix}-${crypto.randomUUID()}`
  }
  return `${prefix}-${Date.now()}-${Math.floor(Math.random() * 100000)}`
}

export function isHexColor(value: string): boolean {
  return /^#[\da-fA-F]{6}$/.test(value)
}

export function getReadableTextColor(hexColor: string): '#111827' | '#ffffff' {
  if (!isHexColor(hexColor)) {
    return '#ffffff'
  }

  const rgb = hexColor.slice(1)
  const r = Number.parseInt(rgb.slice(0, 2), 16)
  const g = Number.parseInt(rgb.slice(2, 4), 16)
  const b = Number.parseInt(rgb.slice(4, 6), 16)
  const brightness = r * 0.299 + g * 0.587 + b * 0.114
  return brightness > 165 ? '#111827' : '#ffffff'
}

export function getPresetColor(index: number): string {
  return PRESET_LEVEL_COLORS[index % PRESET_LEVEL_COLORS.length] ?? '#9ca3af'
}

export function createDefaultTiers(): TierConfig[] {
  return [
    { id: createId('tier'), label: 'A', color: getPresetColor(0) },
    { id: createId('tier'), label: 'B', color: getPresetColor(1) },
    { id: createId('tier'), label: 'C', color: getPresetColor(2) },
    { id: createId('tier'), label: 'D', color: getPresetColor(3) },
  ]
}

// 为标题和等级生成一套可直接使用的默认配置。
export function createDefaultConfig(): BoardConfig {
  return {
    title: '图片榜单',
    tiers: createDefaultTiers(),
    images: [],
  }
}

function isImageItem(value: unknown): value is ImageItem {
  if (!value || typeof value !== 'object') {
    return false
  }

  const image = value as ImageItem
  const isResolveStatusValid =
    typeof image.resolveStatus === 'undefined' || image.resolveStatus === 'ok' || image.resolveStatus === 'failed'

  return (
    typeof image.id === 'string' &&
    typeof image.src === 'string' &&
    image.src.length > 0 &&
    (typeof image.originalUrl === 'undefined' || typeof image.originalUrl === 'string') &&
    (typeof image.resolveReason === 'undefined' || typeof image.resolveReason === 'string') &&
    isResolveStatusValid
  )
}

function isTierConfig(value: unknown): value is TierConfig {
  return (
    !!value &&
    typeof value === 'object' &&
    typeof (value as TierConfig).id === 'string' &&
    typeof (value as TierConfig).label === 'string' &&
    typeof (value as TierConfig).color === 'string'
  )
}

function normalizeConfig(input: BoardConfig): BoardConfig | null {
  if (!Array.isArray(input.tiers) || !Array.isArray(input.images)) {
    return null
  }

  if (input.tiers.length < MIN_TIERS || input.tiers.length > MAX_TIERS) {
    return null
  }

  const seenTierIds = new Set<string>()
  const tiers: TierConfig[] = []
  for (let index = 0; index < input.tiers.length; index += 1) {
    const tier = input.tiers[index]
    if (!isTierConfig(tier)) {
      return null
    }
    if (seenTierIds.has(tier.id)) {
      return null
    }
    seenTierIds.add(tier.id)
    const label = tier.label.trim() || `Tier ${index + 1}`
    const color = isHexColor(tier.color) ? tier.color.toLowerCase() : getPresetColor(index)
    tiers.push({
      id: tier.id,
      label,
      color,
    })
  }

  const seenImageIds = new Set<string>()
  const images: ImageItem[] = []
  for (const image of input.images) {
    if (!isImageItem(image)) {
      return null
    }
    if (seenImageIds.has(image.id)) {
      return null
    }
    seenImageIds.add(image.id)
    const originalUrl =
      typeof image.originalUrl === 'string' && image.originalUrl.trim().length > 0 ? image.originalUrl.trim() : undefined
    const resolveStatus = image.resolveStatus === 'ok' || image.resolveStatus === 'failed' ? image.resolveStatus : undefined
    const resolveReason =
      typeof image.resolveReason === 'string' && image.resolveReason.trim().length > 0 ? image.resolveReason.trim() : undefined

    images.push({
      id: image.id,
      src: image.src,
      originalUrl,
      resolveStatus,
      resolveReason,
    })
  }

  return {
    title: typeof input.title === 'string' ? input.title.trim() || '图片榜单' : '图片榜单',
    tiers,
    images,
  }
}

export function loadBoardConfig(): BoardConfig | null {
  const raw = localStorage.getItem(BOARD_CONFIG_KEY)
  if (!raw) {
    return null
  }

  try {
    const parsed = JSON.parse(raw) as BoardConfig
    return normalizeConfig(parsed)
  } catch {
    return null
  }
}

export function loadBoardConfigOrDefault(): BoardConfig {
  return loadBoardConfig() ?? createDefaultConfig()
}

export function saveBoardConfig(config: BoardConfig): void {
  localStorage.setItem(BOARD_CONFIG_KEY, JSON.stringify(config))
}

// 从配置生成初始状态：所有图片进入未排序池。
export function createBoardStateFromConfig(config: BoardConfig): BoardState {
  const rankings: Record<string, ImageItem[]> = {}
  for (const tier of config.tiers) {
    rankings[tier.id] = []
  }
  return {
    rankings,
    pool: [...config.images],
  }
}

type StateCandidate = {
  rankings?: Record<string, unknown>
  pool?: unknown
}

function toCompatibleState(config: BoardConfig, candidate: StateCandidate): BoardState | null {
  if (!candidate || typeof candidate !== 'object') {
    return null
  }

  const imageById = new Map(config.images.map((image) => [image.id, image]))
  const seenIds = new Set<string>()
  const rankings: Record<string, ImageItem[]> = {}

  const ensureList = (value: unknown): ImageItem[] | null => {
    if (!Array.isArray(value)) {
      return null
    }
    const list: ImageItem[] = []
    for (const item of value) {
      if (!isImageItem(item)) {
        return null
      }
      const expected = imageById.get(item.id)
      if (!expected || expected.src !== item.src || seenIds.has(item.id)) {
        return null
      }
      seenIds.add(item.id)
      list.push(expected)
    }
    return list
  }

  for (const tier of config.tiers) {
    const lane = ensureList(candidate.rankings?.[tier.id])
    if (!lane) {
      return null
    }
    rankings[tier.id] = lane
  }

  const pool = ensureList(candidate.pool)
  if (!pool) {
    return null
  }

  if (seenIds.size !== config.images.length) {
    return null
  }

  return {
    rankings,
    pool,
  }
}

export function loadBoardState(config: BoardConfig): BoardState {
  const raw = localStorage.getItem(BOARD_STATE_KEY)
  if (!raw) {
    return createBoardStateFromConfig(config)
  }

  try {
    const parsed = JSON.parse(raw) as StateCandidate
    return toCompatibleState(config, parsed) ?? createBoardStateFromConfig(config)
  } catch {
    return createBoardStateFromConfig(config)
  }
}

export function saveBoardState(state: BoardState): void {
  localStorage.setItem(BOARD_STATE_KEY, JSON.stringify(state))
}

export function resetBoardState(config: BoardConfig): BoardState {
  const next = createBoardStateFromConfig(config)
  saveBoardState(next)
  return next
}

export function createTier(label: string, color: string): TierConfig {
  return {
    id: createId('tier'),
    label,
    color,
  }
}

export interface CreateImageOptions {
  originalUrl?: string
  resolveStatus?: ResolveStatus
  resolveReason?: string
}

export function createImage(src: string, options?: CreateImageOptions): ImageItem {
  const next: ImageItem = {
    id: createId('img'),
    src,
  }

  if (options?.originalUrl) {
    next.originalUrl = options.originalUrl
  }
  if (options?.resolveStatus) {
    next.resolveStatus = options.resolveStatus
  }
  if (options?.resolveReason) {
    next.resolveReason = options.resolveReason
  }

  return next
}
