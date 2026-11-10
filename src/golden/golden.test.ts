/// <reference types="node" />
// Erzeugt Golden-Fälle für die Nachbauten der Domänenlogik (TESS/Python, Android/Kotlin).
// Aufruf: GOLDEN_OUT=<datei> npx vitest run src/golden/golden.test.ts
// Die TypeScript-Logik in src/domain ist die Referenz; die Fälle sind synthetisch (keine echten Daten).
import { writeFileSync } from 'node:fs'
import { it } from 'vitest'
import { createEmptyDocument, effectiveSettings, portionCapacity } from '../domain/defaults'
import { planAllocation } from '../domain/planner'
import { reduceInventory, round } from '../domain/reducer'
import type { BrewMethod, CoffeeLot, CoffeePackage, InventoryDocument, InventoryEvent, InventoryState, JarSize, RuleProfile } from '../types'

let seed = 1
const rnd = () => { seed = (seed * 1103515245 + 12345) % 2147483648; return seed / 2147483648 }
const pick = <T,>(items: T[]): T => items[Math.floor(rnd() * items.length)]
let counter = 0
let clock = Date.parse('2026-09-25T08:00:00.000Z')
const tick = () => { clock += 60_000 + Math.floor(rnd() * 3_600_000); return new Date(clock).toISOString() }
const id = (prefix: string) => `${prefix}_${String(++counter).padStart(6, '0')}`
function ev(type: InventoryEvent['type'], data: unknown, resourceIds: string[], options: { effectiveAt?: string; reason?: string; warnings?: string[]; parentEventIds?: string[] } = {}): InventoryEvent {
  const at = tick()
  const event: InventoryEvent = { id: id('evt'), type, recordedAt: at, effectiveAt: options.effectiveAt || at, resourceIds: [...new Set(resourceIds)], data: data as Record<string, unknown> }
  if (options.parentEventIds) event.parentEventIds = options.parentEventIds
  if (options.reason) event.reason = options.reason
  if (options.warnings) event.warnings = options.warnings
  return event
}
const serialState = (state: InventoryState) => ({
  packages: state.packages, lots: state.lots,
  // Gläser im Ausgangszustand (frei, nie benutzt) werden weggelassen, um die Datei klein zu halten.
  jars: Object.fromEntries(Object.entries(state.jars).filter(([, j]) => j.status !== 'free' || j.lastFreedAt !== '1970-01-01T00:00:00.000Z')),
  correctedEventIds: [...state.correctedEventIds].sort(), overrides: state.overrides.map(e => e.id), warnings: state.warnings,
})
const NAMES = ['Alpha Espresso', 'Beta Filter', 'Gamma Blend', 'Delta Washed', 'Epsilon Natural', 'Zeta Decaf']

function addPackage(doc: InventoryDocument) {
  const pid = id('package'); const grams = pick([120, 200, 250, 250, 340, 500, 1000])
  const location = rnd() < 0.2 ? 'frozen' : 'pantry'
  const pkg: CoffeePackage = { id: pid, name: pick(NAMES), nominalGrams: grams, availableGrams: grams, roastDate: { value: '2026-09-01', precision: pick(['exact', 'approximate'] as const) }, purchaseDate: { precision: 'unknown' }, location, archived: false }
  const created = ev('PACKAGE_CREATED', { package: pkg }, [pid])
  if (location === 'frozen') pkg.frozenAt = created.recordedAt
  doc.events.push(created)
  if (location === 'frozen') doc.events.push(ev('PACKAGE_FROZEN', { packageId: pid, frozenAt: created.recordedAt }, [pid]))
}

function allocate(doc: InventoryDocument, out: unknown[]) {
  const state = reduceInventory(doc); const settings = effectiveSettings(doc)
  const pkgs = Object.values(state.packages).filter(p => !p.archived && p.availableGrams > 0)
  if (!pkgs.length) return
  const pkg = pick(pkgs); const brew: BrewMethod = pick(['espresso', 'filter'])
  const category = pick(settings.categories)
  const profile: RuleProfile = category.ruleProfile === 'MANUAL' ? pick(['A', 'B', 'DIRECT_S', 'MANUAL'] as RuleProfile[]) : category.ruleProfile
  const actual = pkg.openedAt ? pkg.availableGrams : round(pkg.nominalGrams - rnd() * 6)
  const amount = rnd() < 0.6 ? actual : round(Math.max(5, actual * rnd()))
  const base = brew === 'espresso' ? settings.espressoBaseGrams : settings.filterBaseGrams
  const allowance = brew === 'espresso' ? settings.espressoAllowanceGrams : settings.filterAllowanceGrams
  const directS = rnd() < 0.1
  const input = { grams: amount, brewMethod: brew, baseGrams: base, allowanceGrams: allowance, ruleProfile: profile, premiumDirectS: directS }
  let plan
  try { plan = planAllocation({ ...input, state }) } catch (error) { out.push({ input, error: (error as Error).message, eventCount: doc.events.length }); return }
  out.push({ input, plan: structuredClone(plan), eventCount: doc.events.length })
  if (rnd() < 0.3 && plan.fills.length) plan.fills[plan.fills.length - 1].actualGrams = round(plan.fills[plan.fills.length - 1].actualGrams - 0.5)
  const sum = round(plan.fills.reduce((t, f) => t + f.actualGrams, 0))
  const lot: CoffeeLot = { id: id('lot'), packageId: pkg.id, name: pkg.name, brewMethod: brew, beanState: pick(['whole', 'ground']), categoryId: category.id, ruleProfile: profile, baseGrams: base, allowanceGrams: allowance, initialGrams: amount, unallocatedGrams: amount, thawCount: pkg.location === 'frozen' ? 1 : 0, refreezeCount: pkg.location === 'frozen' ? 1 : 0, archived: false }
  const openedAt = tick()
  if (!pkg.openedAt) doc.events.push(ev('PACKAGE_OPENED', { packageId: pkg.id, actualGrams: actual, openedAt }, [pkg.id], { effectiveAt: openedAt }))
  doc.events.push(ev('LOT_CREATED', { lot }, [pkg.id, lot.id], { effectiveAt: openedAt }))
  doc.events.push(ev('ALLOCATION_COMMITTED', { lotId: lot.id, fills: plan.fills.map(f => ({ jarId: f.jarId, grams: f.actualGrams, frozenAt: openedAt })), unallocatedGrams: round(amount - sum) }, [lot.id, ...plan.fills.map(f => f.jarId)], plan.warnings.length ? { warnings: plan.warnings } : {}))
  if (pkg.location === 'frozen') doc.events.push(ev('RULE_OVERRIDE', { kind: 'frozen-package-refreeze', packageId: pkg.id, lotId: lot.id }, [pkg.id, lot.id], { reason: 'Zuvor komplett eingefrorene Packung wurde portioniert und wieder eingefroren.' }))
}

// Nachbau von TransferAction (App.tsx): Vorschlag und Buchung.
export function transferDraft(state: InventoryState, jarId: string, consumeFirst: boolean) {
  const jar = state.jars[jarId]; const lot = state.lots[jar.lotId!]
  const portion = round(lot.baseGrams + lot.allowanceGrams)
  const targetSize: JarSize = jar.size === 'L' ? 'M' : 'S'
  const consumed = jar.size === 'M' && consumeFirst ? Math.min(portion, jar.grams) : 0
  let remaining = round(jar.grams - consumed)
  const cap = round(portionCapacity(targetSize, lot.brewMethod) * portion)
  const targets: Array<{ jarId: string; grams: number; frozen: boolean }> = []
  const used = new Set<string>()
  while (remaining > 0.001) {
    const free = Object.values(state.jars).filter(j => j.size === targetSize && j.status === 'free' && !used.has(j.id)).sort((a, b) => a.id.localeCompare(b.id))[0]
    if (!free) break
    const grams = Math.min(cap, remaining); used.add(free.id); targets.push({ jarId: free.id, grams, frozen: !(jar.size === 'L' && targets.length === 0) }); remaining = round(remaining - grams)
    if (jar.size === 'L' && targets.length === 3) break
  }
  const moved = targets.reduce((sum, t) => sum + t.grams, 0)
  const remainder = round(jar.grams - consumed - moved)
  const warnings: string[] = []
  if (remainder > 0.001) warnings.push(`${remainder} g bleiben im aktiven Ausgangsglas, weil Zielgläser fehlen.`)
  if (jar.refreezeCount >= 1 && targets.some(t => t.frozen)) warnings.push('Diese Buchung erzeugt eine weitere Wiedereinfrierung.')
  return { targets, consumed, warnings }
}

function janitor(doc: InventoryDocument, transfers: unknown[]) {
  const state = reduceInventory(doc)
  const jars = Object.values(state.jars)
  const r = rnd()
  const filled = jars.filter(j => j.lotId && ['active', 'frozen'].includes(j.status))
  if (r < 0.15) {
    const j = jars.filter(x => x.status === 'frozen'); if (!j.length) return
    const jar = pick(j); doc.events.push(ev('JAR_OPENED', { jarId: jar.id, openedAt: tick() }, [jar.id, jar.lotId!]))
  } else if (r < 0.55) {
    if (!filled.length) return
    const jar = pick(filled); const lot = state.lots[jar.lotId!]
    const portion = round(lot.baseGrams + lot.allowanceGrams)
    const grams = jar.size === 'S' || rnd() < 0.15 ? jar.grams : Math.min(jar.grams, portion)
    doc.events.push(ev('CONSUMED', { jarId: jar.id, grams }, [jar.id, jar.lotId!], rnd() < 0.2 ? { effectiveAt: '2026-09-20T10:00:00.000Z' } : {}))
  } else if (r < 0.65) {
    const j = filled.filter(x => x.size !== 'S'); if (!j.length) return
    const jar = pick(j); const consumeFirst = rnd() < 0.7
    const draft = transferDraft(state, jar.id, consumeFirst)
    transfers.push({ eventCount: doc.events.length, jarId: jar.id, consumeFirst, draft: structuredClone(draft) })
    if (!draft.targets.length) return
    const reason = draft.warnings.length ? 'Testgrund' : ''
    if (jar.status === 'frozen') doc.events.push(ev('JAR_OPENED', { jarId: jar.id, openedAt: tick() }, [jar.id, jar.lotId!]))
    doc.events.push(ev('TRANSFER_COMMITTED', { sourceJarId: jar.id, targets: draft.targets, consumedGrams: draft.consumed }, [jar.id, jar.lotId!, ...draft.targets.map(t => t.jarId)], { reason: reason || undefined, warnings: draft.warnings }))
    if (draft.warnings.length) doc.events.push(ev('RULE_OVERRIDE', { kind: 'transfer-exception', sourceJarId: jar.id }, [jar.id, jar.lotId!], { reason, warnings: draft.warnings }))
  } else if (r < 0.8) {
    const j = jars.filter(x => x.status === 'cleaning'); if (!j.length) return
    const jar = pick(j); doc.events.push(ev('JAR_CLEANED', { jarId: jar.id }, [jar.id]))
  } else if (r < 0.86) {
    const candidates = doc.events.filter(e => ['CONSUMED', 'JAR_OPENED', 'ALLOCATION_COMMITTED', 'JAR_CLEANED', 'TRANSFER_COMMITTED'].includes(e.type))
    if (!candidates.length) return
    const target = pick(candidates)
    doc.events.push(ev('CORRECTION', { targetEventId: target.id }, target.resourceIds, { reason: 'Fehlbuchung', parentEventIds: [target.id] }))
  } else if (r < 0.9) {
    const lots = Object.values(state.lots); if (!lots.length) return
    const lot = pick(lots); doc.events.push(ev('MEASUREMENT_ADJUSTMENT', { lotId: lot.id, deltaGrams: round(rnd() * 4 - 2) }, [lot.id]))
  } else if (r < 0.93) {
    const lots = Object.values(state.lots); if (!lots.length) return
    const lot = pick(lots); doc.events.push(ev('CATEGORY_CHANGED', { lotId: lot.id, categoryId: 'daily-driver', ruleProfile: 'B' }, [lot.id]))
  } else if (r < 0.96) {
    const pkgs = Object.values(state.packages); if (!pkgs.length) return
    const pkg = pick(pkgs); doc.events.push(ev('ARCHIVED', { entityType: 'package', entityId: pkg.id }, [pkg.id]))
  } else {
    // absichtlich ungültige Buchungen (Ableitungswarnungen)
    const kind = pick(['clean', 'consume', 'open', 'lot'])
    if (kind === 'clean') doc.events.push(ev('JAR_CLEANED', { jarId: pick(jars).id }, []))
    if (kind === 'consume') doc.events.push(ev('CONSUMED', { jarId: pick(jars).id, grams: 999 }, []))
    if (kind === 'open') doc.events.push(ev('JAR_OPENED', { jarId: 'X99', openedAt: tick() }, []))
    if (kind === 'lot') doc.events.push(ev('LOT_GROUND', { lotId: 'lot_missing' }, []))
  }
}

it.skipIf(!process.env.GOLDEN_OUT)('schreibt Golden-Fälle', () => {
  const scenarios = []
  for (let s = 0; s < 16; s++) {
    const doc = createEmptyDocument('2026-09-25T08:00:00.000Z')
    if (s % 7 === 3) doc.events.push(ev('CATEGORY_DEFINED', { category: { id: `category_custom${s}`, name: 'Eigene', ruleProfile: pick(['A', 'B', 'DIRECT_S', 'MANUAL']), builtIn: false } }, [`category_custom${s}`]))
    if (s % 9 === 4) doc.events.push(ev('SETTINGS_CHANGED', { espressoBaseGrams: 17, espressoAllowanceGrams: 0.3 }, []))
    const plans: unknown[] = []; const transfers: unknown[] = []
    const steps = 30 + Math.floor(rnd() * 70)
    for (let i = 0; i < steps; i++) {
      const r = rnd()
      if (r < 0.12) addPackage(doc)
      else if (r < 0.3) allocate(doc, plans)
      else janitor(doc, transfers)
    }
    // Reihenfolge im Journal darf egal sein: einmal mischen (Ableitung sortiert nach recordedAt).
    if (s % 5 === 0) doc.events.sort(() => rnd() - 0.5)
    scenarios.push({ name: `szenario-${s}`, document: doc, settings: effectiveSettings(doc), expected: serialState(reduceInventory(doc)), plans, transfers })
  }
  const planner = []
  const base = reduceInventory(createEmptyDocument())
  for (const grams of [5, 15.1, 18.2, 46, 60.4, 110, 163.8, 211, 250, 435, 500, 1000, 2000])
    for (const brewMethod of ['espresso', 'filter'] as const)
      for (const ruleProfile of ['A', 'B', 'DIRECT_S', 'MANUAL'] as const)
        for (const premiumDirectS of ruleProfile === 'A' ? [false, true] : [false]) {
          const input = { grams, brewMethod, baseGrams: brewMethod === 'espresso' ? 18 : 15, allowanceGrams: brewMethod === 'espresso' ? 0.2 : 0.1, ruleProfile, premiumDirectS }
          try { planner.push({ input, plan: planAllocation({ ...input, state: base }) }) } catch (error) { planner.push({ input, error: (error as Error).message }) }
        }
  const out = process.env.GOLDEN_OUT!
  writeFileSync(out, JSON.stringify({ generator: 'kaffeapp src/golden/golden.test.ts', scenarios, planner }))
})
