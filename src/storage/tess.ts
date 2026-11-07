// TESS-Modus: Der Pi ist die Quelle der Wahrheit (docs/sync-protocol.md). Die Website hält eine lokale Kopie des
// Journals plus einen Ausgang (outbox) mit eigenen, noch nicht bestätigten Buchungen; synchronisiert wird mit
// push → pull (bzw. snapshot). Anmeldung per Passkey, das Sitzungs-Token liegt nur im sessionStorage.
import { createEmptyDocument } from '../domain/defaults'
import { reduceInventory } from '../domain/reducer'
import type { InventoryDocument, InventoryEvent, LocalEnvelope } from '../types'
import { loadRecord, saveRecord } from './local'
import { createPasskey, usePasskey, type CreationOptionsJSON, type RequestOptionsJSON } from './webauthn'

export const PUSH_LIMIT = 200
const RECORD_KEY = 'tess'
const URL_KEY = 'kaffee-tess-url'
const MODE_KEY = 'kaffee-mode'
const TOKEN_KEY = 'kaffee-tess-session'

export type Mode = 'tess' | 'drive'

export interface RejectedNotice { eventId: string; type: string; reason: string; at: string }

export interface TessEnvelope extends LocalEnvelope {
  /** Ereignis-IDs des Ausgangs in Buchungsreihenfolge (= pendingEventIds). */
  cursor: number | null
  lastSyncAt?: string
  rejected: RejectedNotice[]
  serverSeq?: number
}

export class TessHttpError extends Error {
  constructor(readonly status: number, message: string) { super(message) }
}

/** 401/404 bei einem angemeldeten Aufruf: Sitzung ungültig (der Server antwortet bewusst neutral mit 404). */
export class SessionExpiredError extends Error {}

type Fetch = typeof fetch
interface PushResult { event_id: string; status: 'applied' | 'duplicate' | 'rejected'; reason?: string }
interface EventsPage { events: Array<{ seq: number; origin: string; event: InventoryEvent }>; next_since: number; has_more: boolean }
interface Snapshot { seq: number; document: InventoryDocument; bean_links?: Record<string, string> }

export const emptyTessEnvelope = (): TessEnvelope => ({
  document: createEmptyDocument(), pendingEventIds: [], pendingReplacement: false, lastRemoteEventIds: [],
  cursor: null, rejected: [],
})

export const normalizeServerUrl = (value: string): string => {
  const trimmed = value.trim().replace(/\/+$/, '')
  if (!/^https:\/\/[^/\s]+/.test(trimmed) && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(trimmed)) {
    throw new Error('Bitte die Server-Adresse mit https:// angeben (z. B. https://tess.<name>.ts.net).')
  }
  return trimmed
}

export class TessClient {
  constructor(readonly baseUrl: string, private token: string | null = null, private readonly fetchImpl: Fetch = (...args) => fetch(...args)) {}

  get signedIn() { return Boolean(this.token) }
  setToken(token: string | null) { this.token = token }

  private async call<T>(method: string, path: string, body?: unknown, authed = true): Promise<T> {
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (authed) {
      if (!this.token) throw new SessionExpiredError('Nicht angemeldet.')
      headers.Authorization = `Bearer ${this.token}`
    }
    let response: Response
    try {
      response = await this.fetchImpl(`${this.baseUrl}/kaffee/v1/${path}`, {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'omit', cache: 'no-store',
      })
    } catch {
      throw new TessHttpError(0, 'TESS ist nicht erreichbar.')
    }
    if (authed && (response.status === 401 || response.status === 404)) throw new SessionExpiredError('Sitzung abgelaufen – bitte erneut mit Passkey anmelden.')
    if (!response.ok) {
      let detail = ''
      try { detail = ((await response.json()) as { error?: string }).error || '' } catch { /* kein JSON */ }
      throw new TessHttpError(response.status, detail || `TESS antwortet mit ${response.status}.`)
    }
    return await response.json() as T
  }

  status() { return this.call<{ seq: number; events: number; server_time: string }>('GET', 'status') }
  snapshot() { return this.call<Snapshot>('GET', 'snapshot') }
  events(since: number, limit = 500) { return this.call<EventsPage>('GET', `events?since=${since}&limit=${limit}`) }
  push(events: InventoryEvent[]) { return this.call<{ results: PushResult[]; seq: number }>('POST', 'push', { events }) }

  async registerPasskey(code: string, label: string): Promise<string> {
    let start
    try {
      start = await this.call<{ ceremony_id: string; options: CreationOptionsJSON }>('POST', 'webauthn/register/options', { code: code.trim() }, false)
    } catch (error) {
      if (error instanceof TessHttpError && error.status === 404) throw new Error('Code ungültig oder abgelaufen. Neuen Code mit ./tessctl kaffee-passkey-code erzeugen.')
      throw error
    }
    const credential = await createPasskey(start.options)
    const done = await this.call<{ ok: boolean; passkey_id: string }>('POST', 'webauthn/register/verify', { ceremony_id: start.ceremony_id, credential, label: label.trim() || 'Website' }, false)
    return done.passkey_id
  }

  async login(): Promise<{ token: string; expires_at: string }> {
    const start = await this.call<{ ceremony_id: string; options: RequestOptionsJSON }>('POST', 'webauthn/login/options', {}, false)
    const credential = await usePasskey(start.options)
    try {
      const session = await this.call<{ token: string; expires_at: string }>('POST', 'webauthn/login/verify', { ceremony_id: start.ceremony_id, credential }, false)
      this.token = session.token
      return session
    } catch (error) {
      if (error instanceof TessHttpError && error.status === 404) throw new Error('Anmeldung abgelehnt. Ist dieser Passkey bei TESS registriert?')
      throw error
    }
  }

  async logout() {
    try { if (this.token) await this.call('POST', 'webauthn/logout', {}) } catch { /* Sitzung ist ohnehin beendet */ }
    this.token = null
  }
}

/** Eine Synchronisierung: Ausgang hochladen, dann neue Ereignisse holen (oder Snapshot bei erstem Lauf/410). */
export async function syncWithTess(envelope: TessEnvelope, client: TessClient, now = () => new Date().toISOString()): Promise<{ envelope: TessEnvelope; rejected: RejectedNotice[] }> {
  let document = envelope.document
  let outbox = [...envelope.pendingEventIds]
  const rejected: RejectedNotice[] = []
  let serverSeq = envelope.serverSeq

  // 1. Ausgang in Buchungsreihenfolge, höchstens PUSH_LIMIT je Aufruf. Bricht ein Aufruf ab, bleibt der Rest im Ausgang.
  while (outbox.length) {
    const byId = new Map(document.events.map(event => [event.id, event]))
    const batchIds = outbox.slice(0, PUSH_LIMIT)
    const batch = batchIds.map(id => byId.get(id)).filter((event): event is InventoryEvent => Boolean(event))
    const reply = batch.length ? await client.push(batch) : { results: [], seq: serverSeq ?? 0 }
    serverSeq = reply.seq
    const acknowledged = new Set<string>(batchIds.filter(id => !byId.has(id)))
    const dropped = new Set<string>()
    for (const result of reply.results) {
      acknowledged.add(result.event_id)
      if (result.status === 'rejected') {
        dropped.add(result.event_id)
        rejected.push({ eventId: result.event_id, type: byId.get(result.event_id)?.type || 'UNKNOWN', reason: result.reason || 'Abgelehnt', at: now() })
      }
    }
    // Ohne Antwort zu einem Ereignis bleibt es im Ausgang (nächster Lauf); Abbruch vermeidet eine Endlosschleife.
    const before = outbox.length
    outbox = outbox.filter(id => !acknowledged.has(id))
    if (dropped.size) document = { ...document, events: document.events.filter(event => !dropped.has(event.id)) }
    if (outbox.length === before) break
  }

  // 2. Neue Ereignisse holen. Erster Lauf oder unbekannter Cursor → Snapshot ersetzt das lokale Journal.
  let cursor = envelope.cursor
  const applySnapshot = async () => {
    const snapshot = await client.snapshot()
    const known = new Set(snapshot.document.events.map(event => event.id))
    const pendingEvents = document.events.filter(event => outbox.includes(event.id) && !known.has(event.id))
    document = { ...snapshot.document, events: [...snapshot.document.events, ...pendingEvents] }
    cursor = snapshot.seq
    serverSeq = snapshot.seq
  }
  if (cursor === null) await applySnapshot()
  else {
    let hasMore = true
    while (hasMore) {
      let page: EventsPage
      try {
        page = await client.events(cursor!)
      } catch (error) {
        if (error instanceof TessHttpError && error.status === 410) { await applySnapshot(); break }
        throw error
      }
      const known = new Set(document.events.map(event => event.id))
      const fresh = page.events.map(item => item.event).filter(event => !known.has(event.id))
      if (fresh.length) document = { ...document, events: [...document.events, ...fresh] }
      cursor = page.next_since
      serverSeq = Math.max(serverSeq ?? 0, page.next_since)
      hasMore = page.has_more && page.events.length > 0
    }
  }

  return {
    envelope: {
      ...envelope, document, pendingEventIds: outbox, cursor, serverSeq, lastSyncAt: now(),
      lastRemoteEventIds: [], pendingReplacement: false, rejected: [...rejected, ...envelope.rejected].slice(0, 50),
    },
    rejected,
  }
}

/** Lokale Ablage und Einstellungen des TESS-Modus; Aufrufe werden nacheinander ausgeführt. */
export class TessRepository {
  private queue: Promise<unknown> = Promise.resolve()
  private current?: TessEnvelope
  client: TessClient | null = null

  constructor(private readonly fetchImpl?: Fetch) {
    const url = readStorage(local(), URL_KEY)
    if (url) this.client = new TessClient(url, readStorage(session(), TOKEN_KEY), fetchImpl)
  }

  get serverUrl() { return this.client?.baseUrl || '' }
  get signedIn() { return Boolean(this.client?.signedIn) }

  private run<T>(task: () => Promise<T>): Promise<T> {
    const next = this.queue.then(task, task)
    this.queue = next.catch(() => undefined)
    return next
  }

  setServer(url: string) {
    const normalized = normalizeServerUrl(url)
    if (normalized !== this.client?.baseUrl) {
      writeStorage(session(), TOKEN_KEY, null)
      this.client = new TessClient(normalized, null, this.fetchImpl)
    }
    writeStorage(local(), URL_KEY, normalized)
  }

  async load(): Promise<TessEnvelope> {
    return this.run(async () => {
      this.current = { ...emptyTessEnvelope(), ...(await loadRecord<TessEnvelope>(RECORD_KEY)) }
      return this.current
    })
  }

  async append(events: InventoryEvent[]): Promise<TessEnvelope> {
    return this.run(async () => {
      const base = this.current || emptyTessEnvelope()
      const known = new Set(base.document.events.map(event => event.id))
      const additions = events.filter(event => !known.has(event.id))
      const next: TessEnvelope = {
        ...base, document: { ...base.document, events: [...base.document.events, ...additions] },
        pendingEventIds: [...base.pendingEventIds, ...additions.map(event => event.id)],
      }
      reduceInventory(next.document)
      await saveRecord(RECORD_KEY, next)
      this.current = next
      return next
    })
  }

  async sync(): Promise<{ envelope: TessEnvelope; rejected: RejectedNotice[] }> {
    return this.run(async () => {
      if (!this.client) throw new Error('TESS ist noch nicht eingerichtet.')
      try {
        const result = await syncWithTess(this.current || emptyTessEnvelope(), this.client)
        await saveRecord(RECORD_KEY, result.envelope)
        this.current = result.envelope
        return result
      } catch (error) {
        if (error instanceof SessionExpiredError) { this.client.setToken(null); writeStorage(session(), TOKEN_KEY, null) }
        throw error
      }
    })
  }

  async dismissRejected(): Promise<TessEnvelope> {
    return this.run(async () => {
      const next = { ...(this.current || emptyTessEnvelope()), rejected: [] }
      await saveRecord(RECORD_KEY, next)
      this.current = next
      return next
    })
  }

  async registerPasskey(code: string, label: string) {
    if (!this.client) throw new Error('Bitte zuerst die Server-Adresse eintragen.')
    return this.client.registerPasskey(code, label)
  }

  async login() {
    if (!this.client) throw new Error('Bitte zuerst die Server-Adresse eintragen.')
    const login = await this.client.login()
    writeStorage(session(), TOKEN_KEY, login.token)
    return login
  }

  async logout() {
    await this.client?.logout()
    writeStorage(session(), TOKEN_KEY, null)
  }
}

export function readMode(): Mode | null {
  const value = readStorage(local(), MODE_KEY)
  return value === 'tess' || value === 'drive' ? value : null
}

export function writeMode(mode: Mode) { writeStorage(local(), MODE_KEY, mode) }

const local = (): Storage | undefined => { try { return globalThis.localStorage } catch { return undefined } }
const session = (): Storage | undefined => { try { return globalThis.sessionStorage } catch { return undefined } }

function readStorage(storage: Storage | undefined, key: string): string | null {
  try { return storage?.getItem(key) ?? null } catch { return null }
}

function writeStorage(storage: Storage | undefined, key: string, value: string | null) {
  try { if (value === null) storage?.removeItem(key); else storage?.setItem(key, value) } catch { /* privater Modus */ }
}
