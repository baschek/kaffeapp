import { portionCapacity } from './defaults'
import { round } from './reducer'
import type { AllocationPlan, BrewMethod, InventoryState, JarSize, RuleProfile } from '../types'

export interface PlanInput {
  grams: number
  brewMethod: BrewMethod
  baseGrams: number
  allowanceGrams: number
  ruleProfile: RuleProfile
  premiumDirectS?: boolean
  state: InventoryState
}

export function planAllocation(input: PlanInput): AllocationPlan {
  const portionGrams = round(input.baseGrams + input.allowanceGrams)
  if (input.grams <= 0 || portionGrams <= 0) throw new Error('Gewicht und Portionsgröße müssen positiv sein.')
  const fullPortions = Math.floor((input.grams + 1e-9) / portionGrams)
  const restGrams = round(input.grams - fullPortions * portionGrams)
  const requested: Array<{ size: JarSize; grams: number }> = []
  let unallocatedGrams = restGrams
  const profile = input.premiumDirectS ? 'DIRECT_S' : input.ruleProfile

  if (profile === 'A') {
    const mCapacity = portionCapacity('M', input.brewMethod)
    const fullM = Math.floor(fullPortions / mCapacity)
    const remaining = fullPortions % mCapacity
    for (let i = 0; i < fullM; i++) requested.push({ size: 'M', grams: round(mCapacity * portionGrams) })
    for (let i = 0; i < remaining; i++) requested.push({ size: 'S', grams: portionGrams })
  } else if (profile === 'B') {
    const lGrams = round(portionCapacity('L', input.brewMethod) * portionGrams)
    const mGrams = round(portionCapacity('M', input.brewMethod) * portionGrams)
    let remaining = input.grams
    while (remaining + 1e-9 >= lGrams) { requested.push({ size: 'L', grams: lGrams }); remaining = round(remaining - lGrams) }
    while (remaining > 0.001) {
      const fill = Math.min(mGrams, remaining)
      requested.push({ size: 'M', grams: round(fill) }); remaining = round(remaining - fill)
    }
    unallocatedGrams = 0
  } else if (profile === 'DIRECT_S') {
    for (let i = 0; i < fullPortions; i++) requested.push({ size: 'S', grams: portionGrams })
  } else {
    unallocatedGrams = input.grams
  }

  const selected = new Set<string>()
  const fills = requested.map(item => {
    const jar = chooseJar(input.state, item.size, selected)
    if (!jar) throw new Error(`Nicht genügend freie ${item.size}-Gläser.`)
    selected.add(jar)
    return { jarId: jar, size: item.size, suggestedGrams: item.grams, actualGrams: item.grams }
  })

  const reserve: AllocationPlan['reserve'] = {}
  if (profile === 'A' && fills.some(fill => fill.size === 'M')) reserve.S = input.brewMethod === 'espresso' ? 2 : 3
  if (profile === 'B' && fills.some(fill => fill.size === 'L')) reserve.M = 3
  const warnings: string[] = []
  for (const [size, needed] of Object.entries(reserve) as Array<[JarSize, number]>) {
    const freeAfter = Object.values(input.state.jars).filter(jar => jar.size === size && jar.status === 'free' && !selected.has(jar.id)).length
    if (freeAfter < needed) warnings.push(`Reserve unterschritten: ${needed} freie ${size}-Gläser benötigt, danach nur ${freeAfter} frei.`)
  }
  if (input.brewMethod === 'filter' && fills.some(fill => fill.size === 'S')) warnings.push('Passform voluminöser Filterbohnen im S-Glas praktisch prüfen.')

  return { fullPortions, portionGrams, restGrams, fills, reserve, warnings, unallocatedGrams: round(unallocatedGrams) }
}

export function chooseJar(state: InventoryState, size: JarSize, excluded = new Set<string>()): string | undefined {
  const free = Object.values(state.jars)
    .filter(jar => jar.size === size && jar.status === 'free' && !excluded.has(jar.id))
    .sort((a, b) => a.lastFreedAt.localeCompare(b.lastFreedAt) || a.id.localeCompare(b.id))
  if (!free.length) return undefined

  const byNumber = [...free].sort((a, b) => Number(a.id.slice(1)) - Number(b.id.slice(1)))
  const runs: typeof byNumber[] = []
  let run: typeof byNumber = []
  for (const jar of byNumber) {
    const previous = run.at(-1)
    if (!previous || Number(jar.id.slice(1)) === Number(previous.id.slice(1)) + 1) run.push(jar)
    else { runs.push(run); run = [jar] }
  }
  if (run.length) runs.push(run)
  runs.sort((a, b) => b.length - a.length || a[0].lastFreedAt.localeCompare(b[0].lastFreedAt) || a[0].id.localeCompare(b[0].id))
  return runs[0]?.[0]?.id || free[0].id
}

export const formatPortions = (grams: number, portionGrams: number) => {
  const full = Math.floor((grams + 1e-9) / portionGrams)
  const rest = round(grams - full * portionGrams)
  return `${full} volle Portion${full === 1 ? '' : 'en'}${rest > 0 ? ` + ${rest.toLocaleString('de-DE')} g Rest` : ''}`
}
