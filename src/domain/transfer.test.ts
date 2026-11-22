import { describe, expect, it } from 'vitest'
import { createEmptyDocument } from './defaults'
import { createEvent } from './events'
import { reduceInventory } from './reducer'
import { maxKeepPortions, transferDraft } from './transfer'
import type { CoffeeLot, CoffeePackage, InventoryDocument } from '../types'

const pkg: CoffeePackage = { id: 'package_1', name: 'Testkaffee', nominalGrams: 250, availableGrams: 250, roastDate: { precision: 'unknown' }, purchaseDate: { precision: 'unknown' }, location: 'pantry', archived: false }
const lot: CoffeeLot = { id: 'lot_1', packageId: pkg.id, name: pkg.name, brewMethod: 'espresso', beanState: 'whole', categoryId: 'premium-whole', ruleProfile: 'A', baseGrams: 18, allowanceGrams: 0.2, initialGrams: 145.6, unallocatedGrams: 145.6, thawCount: 0, refreezeCount: 0, archived: false }

function doc(): InventoryDocument {
  const document = createEmptyDocument()
  document.events = [
    createEvent('PACKAGE_CREATED', { package: pkg }, [pkg.id]),
    createEvent('LOT_CREATED', { lot }, [pkg.id, lot.id]),
    createEvent('ALLOCATION_COMMITTED', { lotId: lot.id, fills: [{ jarId: 'M01', grams: 72.8, frozenAt: '2026-01-01T12:00:00.000Z' }, { jarId: 'L01', grams: 72.8, frozenAt: '2026-01-01T12:00:00.000Z' }], unallocatedGrams: 0 }, [lot.id, 'M01', 'L01']),
  ]
  return document
}

describe('Teilumfüllung', () => {
  it('lässt gewählte Portionen im M-Glas und füllt nur den Rest in S um', () => {
    const document = doc()
    const state = reduceInventory(document)
    expect(maxKeepPortions(state, 'M01', false)).toBe(3)
    const draft = transferDraft(state, 'M01', false, 1)
    expect(draft.kept).toBe(18.2)
    expect(draft.targets.reduce((s, t) => s + t.grams, 0)).toBeCloseTo(54.6)
    expect(draft.targets.every(t => t.frozen)).toBe(true)
    expect(draft.warnings).toEqual([])
    document.events.push(createEvent('JAR_OPENED', { jarId: 'M01', openedAt: '2026-01-02T08:00:00.000Z' }, ['M01', lot.id]))
    document.events.push(createEvent('TRANSFER_COMMITTED', { sourceJarId: 'M01', targets: draft.targets, consumedGrams: draft.consumed }, ['M01', lot.id, ...draft.targets.map(t => t.jarId)]))
    const after = reduceInventory(document)
    expect(after.jars.M01.status).toBe('active')
    expect(after.jars.M01.grams).toBe(18.2)
    expect(after.warnings).toEqual([])
  })

  it('kombiniert Sofortverbrauch und Behalten', () => {
    const state = reduceInventory(doc())
    expect(maxKeepPortions(state, 'M01', true)).toBe(2)
    const draft = transferDraft(state, 'M01', true, 5)
    expect(draft.consumed).toBe(18.2)
    expect(draft.kept).toBe(36.4)
    expect(draft.targets.reduce((s, t) => s + t.grams, 0)).toBeCloseTo(18.2)
  })

  it('friert beim Behalten im L-Glas alle M-Zielgläser ein', () => {
    const state = reduceInventory(doc())
    expect(transferDraft(state, 'L01', true).targets[0].frozen).toBe(false)
    const partial = transferDraft(state, 'L01', true, 1)
    expect(partial.kept).toBe(18.2)
    expect(partial.targets.every(t => t.frozen)).toBe(true)
  })

  it('ohne Behalten bleibt der bisherige Vorschlag unverändert', () => {
    const state = reduceInventory(doc())
    expect(transferDraft(state, 'M01', true, 0).kept).toBe(0)
    expect(transferDraft(state, 'M01', true, 0).targets).toEqual(transferDraft(state, 'M01', true).targets)
  })
})
