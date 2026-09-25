import type { AppSettings, CategoryDefinition, InventoryDocument, JarSize, JarState } from '../types'

export const BUILT_IN_CATEGORIES: AppSettings['categories'] = [
  { id: 'premium-whole', name: 'Hochwertig / wichtig · ganze Bohne', ruleProfile: 'A', builtIn: true },
  { id: 'daily-driver', name: 'Daily Driver', ruleProfile: 'B', builtIn: true },
  { id: 'important-ground', name: 'Wichtig · gemahlen', ruleProfile: 'DIRECT_S', builtIn: true },
  { id: 'less-important-ground', name: 'Weniger wichtig · gemahlen', ruleProfile: 'MANUAL', builtIn: true },
]

export const DEFAULT_SETTINGS: AppSettings = {
  timezone: 'Europe/Berlin',
  espressoBaseGrams: 18,
  espressoAllowanceGrams: 0.2,
  filterBaseGrams: 15,
  filterAllowanceGrams: 0.1,
  categories: BUILT_IN_CATEGORIES,
}

const jarRange = (size: JarSize, count: number): JarState[] =>
  Array.from({ length: count }, (_, index) => ({
    id: `${size}${String(index + 1).padStart(2, '0')}`,
    size,
    status: 'free',
    grams: 0,
    lastFreedAt: '1970-01-01T00:00:00.000Z',
    thawCount: 0,
    refreezeCount: 0,
  }))

export const createJars = (): Record<string, JarState> =>
  Object.fromEntries([...jarRange('S', 48), ...jarRange('M', 36), ...jarRange('L', 12)].map(jar => [jar.id, jar]))

export const createEmptyDocument = (now = new Date().toISOString()): InventoryDocument => ({
  schemaVersion: 1,
  createdAt: now,
  settings: structuredClone(DEFAULT_SETTINGS),
  events: [],
})

export const effectiveSettings = (document: InventoryDocument): AppSettings => {
  const settings = structuredClone(document.settings)
  for (const event of document.events) {
    if (event.type === 'CATEGORY_DEFINED') {
      const category = (event.data as { category: CategoryDefinition }).category
      settings.categories = [...settings.categories.filter(item => item.id !== category.id), category]
    }
    if (event.type === 'SETTINGS_CHANGED') Object.assign(settings, event.data)
  }
  return settings
}

export const portionCapacity = (size: JarSize, brew: 'espresso' | 'filter'): number => {
  if (size === 'S') return 1
  if (size === 'M') return brew === 'espresso' ? 3 : 4
  return brew === 'espresso' ? 9 : 12
}
