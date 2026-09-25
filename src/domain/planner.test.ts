import { describe, expect, it } from 'vitest'
import { createEmptyDocument } from './defaults'
import { planAllocation } from './planner'
import { reduceInventory } from './reducer'

const state = () => reduceInventory(createEmptyDocument())

describe('Aufteilungsplaner', () => {
  it('plant hochwertigen Espresso in M und S inklusive Zuschlag', () => {
    const plan = planAllocation({ grams: 250, brewMethod: 'espresso', baseGrams: 18, allowanceGrams: 0.2, ruleProfile: 'A', state: state() })
    expect(plan.fullPortions).toBe(13)
    expect(plan.restGrams).toBe(13.4)
    expect(plan.fills.filter(fill => fill.size === 'M')).toHaveLength(4)
    expect(plan.fills.filter(fill => fill.size === 'S')).toHaveLength(2)
    expect(plan.reserve.S).toBe(2)
    expect(plan.unallocatedGrams).toBe(0)
  })

  it('füllt Daily-Driver-Reste in ein teilweise gefülltes M-Glas', () => {
    const plan = planAllocation({ grams: 250, brewMethod: 'filter', baseGrams: 15, allowanceGrams: 0.1, ruleProfile: 'B', state: state() })
    expect(plan.fills.map(fill => fill.size)).toEqual(['L', 'M', 'M'])
    expect(plan.fills.at(-1)?.suggestedGrams).toBe(8.4)
    expect(plan.unallocatedGrams).toBe(0)
    expect(plan.reserve.M).toBe(3)
  })

  it('legt wichtigen gemahlenen Kaffee ausschließlich direkt in S', () => {
    const plan = planAllocation({ grams: 46, brewMethod: 'filter', baseGrams: 15, allowanceGrams: 0.1, ruleProfile: 'DIRECT_S', state: state() })
    expect(plan.fills.map(fill => fill.size)).toEqual(['S', 'S', 'S', 'S'])
    expect(plan.restGrams).toBe(0.7)
    expect(plan.unallocatedGrams).toBe(0)
    expect(plan.warnings.some(warning => warning.includes('Passform'))).toBe(true)
  })

  it('weist zusammenhängende freie Nummern zu', () => {
    const current = state()
    current.jars.M01.status = 'cleaning'
    current.jars.M03.status = 'cleaning'
    const plan = planAllocation({ grams: 110, brewMethod: 'espresso', baseGrams: 18, allowanceGrams: 0.2, ruleProfile: 'A', state: current })
    expect(plan.fills.filter(fill => fill.size === 'M').map(fill => fill.jarId)).toEqual(['M04'])
    expect(plan.fills.filter(fill => fill.size === 'S').map(fill => fill.jarId)).toEqual(['S01', 'S02', 'S03', 'S04'])
  })

  it('behält bei einer exakt passenden Kategorie-A-Charge mindestens ein S-Glas', () => {
    const plan = planAllocation({ grams: 60.4, brewMethod: 'filter', baseGrams: 15, allowanceGrams: 0.1, ruleProfile: 'A', state: state() })
    expect(plan.fills.map(fill => fill.size)).toEqual(['S', 'S', 'S', 'S'])
    expect(plan.unallocatedGrams).toBe(0)
  })

  it('behält bei einem exakt passenden Daily Driver mindestens ein M-Glas', () => {
    const plan = planAllocation({ grams: 163.8, brewMethod: 'espresso', baseGrams: 18, allowanceGrams: 0.2, ruleProfile: 'B', state: state() })
    expect(plan.fills.map(fill => fill.size)).toEqual(['M', 'M', 'M'])
    expect(plan.unallocatedGrams).toBe(0)
  })

  it('bildet den bestätigten 3.607-g-Einlagerungsplan inklusive Reserven ab', () => {
    const current = state()
    const allocations = [
      [211, 'filter', 'A'], [145, 'espresso', 'A'], [222, 'filter', 'A'], [70, 'filter', 'A'], [184, 'filter', 'A'],
      [250, 'espresso', 'A'], [250, 'espresso', 'A'], [500, 'filter', 'A'],
      [435, 'espresso', 'B'], [500, 'filter', 'B'], [500, 'espresso', 'B'],
      [250, 'filter', 'DIRECT_S'],
      [18, 'espresso', 'DIRECT_S'], [18, 'espresso', 'DIRECT_S'], [18, 'espresso', 'DIRECT_S'], [18, 'espresso', 'DIRECT_S'], [18, 'espresso', 'DIRECT_S'],
    ] as const

    for (const [grams, brewMethod, ruleProfile] of allocations) {
      const plan = planAllocation({ grams, brewMethod, baseGrams: brewMethod === 'espresso' ? 18 : 15, allowanceGrams: brewMethod === 'espresso' ? 0.2 : 0.1, ruleProfile, state: current })
      plan.fills.forEach(fill => { current.jars[fill.jarId].status = 'frozen' })
    }

    const count = (size: 'S' | 'M' | 'L') => Object.values(current.jars).filter(jar => jar.size === size && jar.status === 'frozen').length
    expect(count('S')).toBe(45)
    expect(count('M')).toBe(32)
    expect(count('L')).toBe(7)
    expect(Object.values(current.jars).filter(jar => jar.size === 'S' && jar.status === 'free')).toHaveLength(3)
    expect(Object.values(current.jars).filter(jar => jar.size === 'M' && jar.status === 'free')).toHaveLength(4)
  })
})
