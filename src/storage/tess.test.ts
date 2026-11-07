import { describe, expect, it } from 'vitest'
import { createEmptyDocument } from '../domain/defaults'
import { createEvent } from '../domain/events'
import type { InventoryEvent } from '../types'
import { emptyTessEnvelope, normalizeServerUrl, PUSH_LIMIT, SessionExpiredError, syncWithTess, TessClient, type TessEnvelope } from './tess'

const BASE = 'https://tess.example.ts.net'
type Handler = (path: string, init: RequestInit) => { status?: number; body: unknown }

function fakeFetch(handler: Handler) {
  const calls: Array<{ path: string; init: RequestInit }> = []
  const impl = (async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input).replace(`${BASE}/kaffee/v1/`, '')
    calls.push({ path, init })
    const { status = 200, body } = handler(path, init)
    return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
  }) as typeof fetch
  return { impl, calls }
}

const consumed = (jarId: string): InventoryEvent => createEvent('CONSUMED', { jarId, grams: 18.2 }, [jarId])

function withOutbox(...events: InventoryEvent[]): TessEnvelope {
  const envelope = emptyTessEnvelope()
  envelope.cursor = 5
  envelope.document.events.push(...events)
  envelope.pendingEventIds = events.map(event => event.id)
  return envelope
}

const pushed = (init: RequestInit) => (JSON.parse(String(init.body)) as { events: InventoryEvent[] }).events

describe('TESS-Synchronisierung', () => {
  it('lädt den Ausgang hoch und entfernt abgelehnte Buchungen mit Hinweis', async () => {
    const [a, b, c] = [consumed('S01'), consumed('S02'), consumed('S03')]
    const { impl, calls } = fakeFetch((path, init) => path === 'push'
      ? { body: { seq: 9, results: [
        { event_id: a.id, status: 'applied' }, { event_id: b.id, status: 'duplicate' },
        { event_id: c.id, status: 'rejected', reason: 'S03 enthält keinen verbuchbaren Kaffee' }] } }
      : { body: { events: [], next_since: 9, has_more: false }, status: init.method === 'GET' ? 200 : 500 })
    const result = await syncWithTess(withOutbox(a, b, c), new TessClient(BASE, 'token', impl), () => '2026-11-07T12:00:00.000Z')
    expect(pushed(calls[0].init).map(event => event.id)).toEqual([a.id, b.id, c.id])
    expect(calls[0].init.headers).toMatchObject({ Authorization: 'Bearer token' })
    expect(result.envelope.pendingEventIds).toEqual([])
    expect(result.envelope.document.events.map(event => event.id)).toEqual([a.id, b.id])
    expect(result.rejected).toEqual([{ eventId: c.id, type: 'CONSUMED', reason: 'S03 enthält keinen verbuchbaren Kaffee', at: '2026-11-07T12:00:00.000Z' }])
    expect(result.envelope.cursor).toBe(9)
    expect(result.envelope.lastSyncAt).toBe('2026-11-07T12:00:00.000Z')
  })

  it('teilt große Ausgänge in Pakete und behält unbestätigte Buchungen', async () => {
    const events = Array.from({ length: PUSH_LIMIT + 3 }, (_, index) => consumed(`S${index}`))
    let pushes = 0
    const { impl } = fakeFetch((path, init) => {
      if (path !== 'push') return { body: { events: [], next_since: 5, has_more: false } }
      pushes += 1
      const batch = pushed(init)
      if (pushes === 2) return { status: 503, body: { error: 'busy' } }
      return { body: { seq: 6, results: batch.map(event => ({ event_id: event.id, status: 'applied' })) } }
    })
    await expect(syncWithTess(withOutbox(...events), new TessClient(BASE, 'token', impl))).rejects.toThrow('busy')
    expect(pushes).toBe(2)
  })

  it('behält den Ausgang, wenn TESS nicht erreichbar ist', async () => {
    const event = consumed('S01')
    const impl = (async () => { throw new TypeError('offline') }) as typeof fetch
    const envelope = withOutbox(event)
    await expect(syncWithTess(envelope, new TessClient(BASE, 'token', impl))).rejects.toThrow('nicht erreichbar')
    expect(envelope.pendingEventIds).toEqual([event.id])
  })

  it('blättert neue Ereignisse ab dem Cursor und überspringt bekannte', async () => {
    const own = consumed('S01'); const first = consumed('S02'); const second = consumed('S03')
    const { impl, calls } = fakeFetch(path => {
      if (path === 'events?since=5&limit=500') return { body: { events: [{ seq: 6, origin: 'claude', event: first }, { seq: 7, origin: 'web', event: own }], next_since: 7, has_more: true } }
      if (path === 'events?since=7&limit=500') return { body: { events: [{ seq: 8, origin: 'app', event: second }], next_since: 8, has_more: false } }
      throw new Error(`unerwartet: ${path}`)
    })
    const envelope = withOutbox(own); envelope.pendingEventIds = []
    const result = await syncWithTess(envelope, new TessClient(BASE, 'token', impl))
    expect(calls.map(call => call.path)).toEqual(['events?since=5&limit=500', 'events?since=7&limit=500'])
    expect(result.envelope.document.events.map(event => event.id)).toEqual([own.id, first.id, second.id])
    expect(result.envelope.cursor).toBe(8)
  })

  it('holt bei erstem Lauf und bei 410 einen Snapshot und behält offene Buchungen', async () => {
    const server = createEmptyDocument('2026-09-25T00:00:00.000Z')
    const remote = consumed('M01'); server.events.push(remote)
    const local = consumed('S09')
    let pushes = 0
    const { impl, calls } = fakeFetch(path => {
      if (path === 'push') { pushes += 1; return { status: 503, body: { error: 'später' } } }
      if (path.startsWith('events')) return { status: 410, body: { error: 'resync_required' } }
      return { body: { seq: 40, document: server, bean_links: {} } }
    })
    const envelope = withOutbox(); envelope.document.events.push(consumed('X01'))
    const result = await syncWithTess(envelope, new TessClient(BASE, 'token', impl))
    expect(calls.map(call => call.path)).toEqual(['events?since=5&limit=500', 'snapshot'])
    expect(result.envelope.document.events.map(event => event.id)).toEqual([remote.id])
    expect(result.envelope.cursor).toBe(40)

    const first = emptyTessEnvelope(); first.document.events.push(local); first.pendingEventIds = [local.id]
    await expect(syncWithTess(first, new TessClient(BASE, 'token', impl))).rejects.toThrow('später')
    expect(pushes).toBe(1)
  })

  it('meldet eine abgelaufene Sitzung bei 404', async () => {
    const { impl } = fakeFetch(() => ({ status: 404, body: { error: 'not_found' } }))
    await expect(syncWithTess(withOutbox(), new TessClient(BASE, 'token', impl))).rejects.toBeInstanceOf(SessionExpiredError)
    await expect(new TessClient(BASE, null, impl).status()).rejects.toBeInstanceOf(SessionExpiredError)
  })

  it('akzeptiert nur https-Adressen (localhost zur Entwicklung)', () => {
    expect(normalizeServerUrl(' https://tess.example.ts.net/ ')).toBe('https://tess.example.ts.net')
    expect(normalizeServerUrl('http://localhost:8000')).toBe('http://localhost:8000')
    expect(() => normalizeServerUrl('http://tess.example.ts.net')).toThrow(/https/)
  })
})
