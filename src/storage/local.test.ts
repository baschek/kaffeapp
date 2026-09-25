import { describe, expect, it } from 'vitest'
import { createEmptyDocument } from '../domain/defaults'
import { createEvent } from '../domain/events'
import { validateDocument } from './local'

describe('JSON-Validierung', () => {
  it('akzeptiert einen gültigen vollständigen Export', () => {
    const document = createEmptyDocument()
    document.events.push(createEvent('RULE_OVERRIDE', { kind: 'test' }, ['lot_1'], { reason: 'Test' }))
    expect(validateDocument(JSON.parse(JSON.stringify(document))).events).toHaveLength(1)
  })

  it('weist doppelte Ereignis-IDs zurück', () => {
    const document = createEmptyDocument()
    const event = createEvent('RULE_OVERRIDE', { kind: 'test' }, ['lot_1'], { reason: 'Test' })
    document.events = [event, event]
    expect(() => validateDocument(document)).toThrow(/Doppelte/)
  })
})
