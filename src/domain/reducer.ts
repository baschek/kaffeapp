import { createJars } from './defaults'
import type { CoffeeLot, CoffeePackage, InventoryDocument, InventoryEvent, InventoryState, JarState } from '../types'

interface FillData { lotId: string; fills: Array<{ jarId: string; grams: number; frozenAt: string }>; unallocatedGrams: number }
interface TransferData { sourceJarId: string; targets: Array<{ jarId: string; grams: number; frozen: boolean }>; consumedGrams?: number }

// recordedAt preserves causal booking order even when a user backdates effectiveAt.
// effectiveAt remains the date shown in the historical timeline.
const sortEvents = (events: InventoryEvent[]) => [...events].sort((a, b) =>
  a.recordedAt.localeCompare(b.recordedAt))

export function reduceInventory(document: InventoryDocument): InventoryState {
  const state: InventoryState = {
    packages: {},
    lots: {},
    jars: createJars(),
    correctedEventIds: new Set<string>(),
    overrides: [],
    warnings: [],
  }

  const unique = new Map<string, InventoryEvent>()
  document.events.forEach(event => unique.set(event.id, event))
  const events = sortEvents([...unique.values()])
  events.filter(event => event.type === 'CORRECTION').forEach(event => {
    const targetEventId = (event.data as { targetEventId: string }).targetEventId
    state.correctedEventIds.add(targetEventId)
  })

  for (const event of events) {
    if (event.type !== 'CORRECTION' && state.correctedEventIds.has(event.id)) continue
    try {
      applyEvent(state, event)
    } catch (error) {
      state.warnings.push(`${event.id}: ${error instanceof Error ? error.message : 'Ungültiges Ereignis'}`)
    }
  }
  return state
}

function applyEvent(state: InventoryState, event: InventoryEvent) {
  switch (event.type) {
    case 'PACKAGE_CREATED': {
      const pkg = structuredClone((event.data as { package: CoffeePackage }).package)
      state.packages[pkg.id] = pkg
      return
    }
    case 'PACKAGE_FROZEN': {
      const { packageId, frozenAt } = event.data as { packageId: string; frozenAt: string }
      const pkg = state.packages[packageId]
      if (!pkg) throw new Error('Packung nicht gefunden')
      pkg.location = 'frozen'; pkg.frozenAt = frozenAt
      return
    }
    case 'PACKAGE_OPENED': {
      const { packageId, actualGrams, openedAt } = event.data as { packageId: string; actualGrams: number; openedAt: string }
      const pkg = state.packages[packageId]
      if (!pkg) throw new Error('Packung nicht gefunden')
      pkg.actualGrams = actualGrams; pkg.availableGrams = actualGrams; pkg.openedAt = openedAt
      return
    }
    case 'LOT_CREATED': {
      const lot = structuredClone((event.data as { lot: CoffeeLot }).lot)
      const pkg = state.packages[lot.packageId]
      if (!pkg) throw new Error('Ursprungspackung nicht gefunden')
      if (pkg.availableGrams + 0.001 < lot.initialGrams) throw new Error('Teilcharge übersteigt verfügbares Packungsgewicht')
      pkg.availableGrams = round(pkg.availableGrams - lot.initialGrams)
      state.lots[lot.id] = lot
      return
    }
    case 'LOT_GROUND': {
      const { lotId } = event.data as { lotId: string }
      if (!state.lots[lotId]) throw new Error('Charge nicht gefunden')
      state.lots[lotId].beanState = 'ground'
      return
    }
    case 'ALLOCATION_COMMITTED': {
      const { lotId, fills, unallocatedGrams } = event.data as unknown as FillData
      const lot = state.lots[lotId]
      if (!lot) throw new Error('Charge nicht gefunden')
      for (const fill of fills) {
        const jar = state.jars[fill.jarId]
        if (!jar || jar.status !== 'free') throw new Error(`${fill.jarId} ist nicht frei`)
        Object.assign(jar, { status: 'frozen', lotId, grams: round(fill.grams), filledAt: fill.frozenAt, openedAt: undefined })
        jar.thawCount = lot.thawCount
        jar.refreezeCount = lot.refreezeCount
      }
      lot.unallocatedGrams = round(unallocatedGrams)
      return
    }
    case 'JAR_OPENED': {
      const { jarId, openedAt } = event.data as { jarId: string; openedAt: string }
      const jar = state.jars[jarId]
      if (!jar || jar.status !== 'frozen') throw new Error(`${jarId} ist nicht tiefgekühlt belegt`)
      jar.status = 'active'; jar.openedAt = openedAt; jar.thawCount += 1
      return
    }
    case 'TRANSFER_COMMITTED': {
      const { sourceJarId, targets, consumedGrams = 0 } = event.data as unknown as TransferData
      const source = state.jars[sourceJarId]
      if (!source || !source.lotId) throw new Error('Ausgangsglas ist leer')
      const transferred = round(targets.reduce((sum, target) => sum + target.grams, 0))
      if (transferred + consumedGrams > source.grams + 0.001) throw new Error('Umfüllmenge übersteigt Glasinhalt')
      for (const target of targets) {
        const jar = state.jars[target.jarId]
        if (!jar || jar.status !== 'free') throw new Error(`${target.jarId} ist nicht frei`)
        Object.assign(jar, {
          status: target.frozen ? 'frozen' : 'active', lotId: source.lotId, grams: round(target.grams),
          filledAt: event.effectiveAt, openedAt: target.frozen ? undefined : event.effectiveAt,
          thawCount: source.thawCount, refreezeCount: source.refreezeCount + (target.frozen ? 1 : 0),
        })
      }
      source.grams = round(source.grams - transferred - consumedGrams)
      if (source.grams <= 0.001) clearToCleaning(source)
      else source.status = 'active'
      return
    }
    case 'CONSUMED': {
      const { jarId, grams } = event.data as { jarId: string; grams: number }
      const jar = state.jars[jarId]
      if (!jar || !jar.lotId || !['active', 'frozen'].includes(jar.status)) throw new Error(`${jarId} enthält keinen verbuchbaren Kaffee`)
      if (grams <= 0 || grams > jar.grams + 0.001) throw new Error('Ungültige Verbrauchsmenge')
      if (jar.status === 'frozen') { jar.status = 'active'; jar.openedAt = event.effectiveAt; jar.thawCount += 1 }
      jar.grams = round(jar.grams - grams)
      if (jar.grams <= 0.001) clearToCleaning(jar)
      return
    }
    case 'JAR_CLEANED': {
      const { jarId } = event.data as { jarId: string }
      const jar = state.jars[jarId]
      if (!jar || jar.status !== 'cleaning') throw new Error(`${jarId} wartet nicht auf Reinigung`)
      Object.assign(jar, { status: 'free', lotId: undefined, grams: 0, filledAt: undefined, openedAt: undefined, lastFreedAt: event.effectiveAt, thawCount: 0, refreezeCount: 0 })
      return
    }
    case 'CATEGORY_CHANGED': {
      const { lotId, categoryId, ruleProfile } = event.data as { lotId: string; categoryId: string; ruleProfile: CoffeeLot['ruleProfile'] }
      if (!state.lots[lotId]) throw new Error('Charge nicht gefunden')
      state.lots[lotId].categoryId = categoryId; state.lots[lotId].ruleProfile = ruleProfile
      return
    }
    case 'CATEGORY_DEFINED': return
    case 'SETTINGS_CHANGED': return
    case 'ARCHIVED': {
      const { entityType, entityId } = event.data as { entityType: 'package' | 'lot'; entityId: string }
      if (entityType === 'package' && state.packages[entityId]) state.packages[entityId].archived = true
      if (entityType === 'lot' && state.lots[entityId]) state.lots[entityId].archived = true
      return
    }
    case 'RULE_OVERRIDE': state.overrides.push(event); return
    case 'MEASUREMENT_ADJUSTMENT': {
      const { lotId, deltaGrams } = event.data as { lotId: string; deltaGrams: number }
      if (!state.lots[lotId]) throw new Error('Charge nicht gefunden')
      state.lots[lotId].unallocatedGrams = round(state.lots[lotId].unallocatedGrams + deltaGrams)
      return
    }
    case 'CORRECTION': return
  }
}

function clearToCleaning(jar: JarState) {
  jar.status = 'cleaning'; jar.grams = 0; jar.openedAt = undefined
}

export const round = (value: number) => Math.round((value + Number.EPSILON) * 100) / 100
