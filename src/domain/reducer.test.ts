import { describe, expect, it } from 'vitest'
import { createEmptyDocument } from './defaults'
import { createEvent } from './events'
import { reduceInventory } from './reducer'
import type { CoffeeLot, CoffeePackage, InventoryDocument } from '../types'

const pkg: CoffeePackage = { id: 'package_1', name: 'Testkaffee', nominalGrams: 100, availableGrams: 100, roastDate: { precision: 'unknown' }, purchaseDate: { precision: 'unknown' }, location: 'pantry', archived: false }
const lot: CoffeeLot = { id: 'lot_1', packageId: pkg.id, name: pkg.name, brewMethod: 'espresso', beanState: 'whole', categoryId: 'premium-whole', ruleProfile: 'A', baseGrams: 18, allowanceGrams: 0.2, initialGrams: 72.8, unallocatedGrams: 72.8, thawCount: 0, refreezeCount: 0, archived: false }

function documentWithFlow(): InventoryDocument {
  const document = createEmptyDocument()
  document.events = [
    createEvent('PACKAGE_CREATED', { package: pkg }, [pkg.id]),
    createEvent('LOT_CREATED', { lot }, [pkg.id, lot.id]),
    createEvent('ALLOCATION_COMMITTED', { lotId: lot.id, fills: [{ jarId: 'M01', grams: 54.6, frozenAt: '2026-01-01T12:00:00.000Z' }, { jarId: 'S01', grams: 18.2, frozenAt: '2026-01-01T12:00:00.000Z' }], unallocatedGrams: 0 }, [lot.id, 'M01', 'S01']),
  ]
  return document
}

describe('Ereignisreducer', () => {
  it('leitet denselben Bestand deterministisch und idempotent ab', () => {
    const document = documentWithFlow()
    document.events.push(document.events[2])
    const result = reduceInventory(document)
    expect(result.jars.M01.grams).toBe(54.6)
    expect(result.jars.S01.grams).toBe(18.2)
    expect(result.packages[pkg.id].availableGrams).toBe(27.2)
  })

  it('führt Verbrauch grammgenau und setzt ein leeres Glas auf Reinigung', () => {
    const document = documentWithFlow()
    document.events.push(createEvent('CONSUMED', { jarId: 'S01', grams: 18.2 }, ['S01', lot.id]))
    const result = reduceInventory(document)
    expect(result.jars.S01.status).toBe('cleaning')
    expect(result.jars.S01.grams).toBe(0)
  })

  it('behält das fehlerhafte Ereignis, hebt aber seine Wirkung per Gegenbuchung auf', () => {
    const document = documentWithFlow()
    const allocation = document.events[2]
    document.events.push(createEvent('CORRECTION', { targetEventId: allocation.id }, allocation.resourceIds, { reason: 'Falsche Gläser' }))
    const result = reduceInventory(document)
    expect(document.events).toContain(allocation)
    expect(result.correctedEventIds.has(allocation.id)).toBe(true)
    expect(result.jars.M01.status).toBe('free')
  })

  it('erhält die Grammmenge bei einer Umfüllung', () => {
    const document = documentWithFlow()
    document.events.push(createEvent('JAR_OPENED', { jarId: 'M01', openedAt: '2026-01-02T10:00:00.000Z' }, ['M01', lot.id]))
    document.events.push(createEvent('TRANSFER_COMMITTED', { sourceJarId: 'M01', consumedGrams: 18.2, targets: [{ jarId: 'S02', grams: 18.2, frozen: true }, { jarId: 'S03', grams: 18.2, frozen: true }] }, ['M01', 'S02', 'S03', lot.id]))
    const result = reduceInventory(document)
    expect(result.jars.M01.status).toBe('cleaning')
    expect(result.jars.S02.grams + result.jars.S03.grams + 18.2).toBeCloseTo(54.6, 5)
  })
})
