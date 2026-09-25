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
    expect(plan.fills.filter(fill => fill.size === 'S')).toHaveLength(1)
    expect(plan.reserve.S).toBe(2)
    expect(plan.unallocatedGrams).toBe(13.4)
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
    expect(plan.fills.map(fill => fill.size)).toEqual(['S', 'S', 'S'])
    expect(plan.restGrams).toBe(0.7)
    expect(plan.warnings.some(warning => warning.includes('Passform'))).toBe(true)
  })

  it('weist zusammenhängende freie Nummern zu', () => {
    const current = state()
    current.jars.M01.status = 'cleaning'
    current.jars.M03.status = 'cleaning'
    const plan = planAllocation({ grams: 110, brewMethod: 'espresso', baseGrams: 18, allowanceGrams: 0.2, ruleProfile: 'A', state: current })
    expect(plan.fills.map(fill => fill.jarId)).toEqual(['M04', 'M05'])
  })
})
