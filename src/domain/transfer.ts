import { portionCapacity } from './defaults'
import { round } from './reducer'
import type { InventoryState, JarSize } from '../types'

export interface TransferTarget { jarId: string; grams: number; frozen: boolean }
export interface TransferDraft { targets: TransferTarget[]; consumed: number; kept: number; warnings: string[] }

const portionOf = (state: InventoryState, jarId: string) => {
  const lot = state.lots[state.jars[jarId].lotId!]
  return round(lot.baseGrams + lot.allowanceGrams)
}

/** Gramm, die nach dem optionalen Sofortverbrauch zum Umfüllen bereitstehen. */
const availableGrams = (state: InventoryState, jarId: string, consumeFirst: boolean) => {
  const jar = state.jars[jarId]
  const consumed = jar.size === 'M' && consumeFirst ? Math.min(portionOf(state, jarId), jar.grams) : 0
  return { consumed, remaining: round(jar.grams - consumed) }
}

/** Höchstzahl ganzer Portionen, die im Ausgangsglas bleiben können; mindestens eine Portion wird immer umgefüllt. */
export function maxKeepPortions(state: InventoryState, jarId: string, consumeFirst: boolean): number {
  const { remaining } = availableGrams(state, jarId, consumeFirst)
  return Math.max(0, Math.floor((remaining + 1e-9) / portionOf(state, jarId)) - 1)
}

/**
 * Umfüllvorschlag (L → M, M → S). Mit keepPortions bleiben so viele ganze Portionen bewusst im Ausgangsglas
 * (Teilumfüllung); das Ausgangsglas bleibt dann aktiv und alle Zielgläser werden eingefroren.
 * Ohne keepPortions entspricht das Ergebnis exakt dem bisherigen Vorschlag (Golden-Fälle, TESS).
 */
export function transferDraft(state: InventoryState, jarId: string, consumeFirst: boolean, keepPortions = 0): TransferDraft {
  const jar = state.jars[jarId]; const lot = state.lots[jar.lotId!]
  const portion = portionOf(state, jarId)
  const targetSize: JarSize = jar.size === 'L' ? 'M' : 'S'
  const { consumed, remaining: available } = availableGrams(state, jarId, consumeFirst)
  const keep = Math.max(0, Math.min(Math.floor(keepPortions), maxKeepPortions(state, jarId, consumeFirst)))
  const kept = round(keep * portion)
  let remaining = round(available - kept)
  const cap = round(portionCapacity(targetSize, lot.brewMethod) * portion)
  const targets: TransferTarget[] = []
  const used = new Set<string>()
  while (remaining > 0.001) {
    const free = Object.values(state.jars).filter(j => j.size === targetSize && j.status === 'free' && !used.has(j.id)).sort((a, b) => a.id.localeCompare(b.id))[0]
    if (!free) break
    const grams = Math.min(cap, remaining); used.add(free.id); targets.push({ jarId: free.id, grams, frozen: !(jar.size === 'L' && targets.length === 0 && keep === 0) }); remaining = round(remaining - grams)
    if (jar.size === 'L' && targets.length === 3) break
  }
  const moved = targets.reduce((sum, t) => sum + t.grams, 0)
  const remainder = round(jar.grams - consumed - moved - kept)
  const warnings: string[] = []
  if (remainder > 0.001) warnings.push(`${remainder} g bleiben im aktiven Ausgangsglas, weil Zielgläser fehlen.`)
  if (jar.refreezeCount >= 1 && targets.some(t => t.frozen)) warnings.push('Diese Buchung erzeugt eine weitere Wiedereinfrierung.')
  return { targets, consumed, kept, warnings }
}
