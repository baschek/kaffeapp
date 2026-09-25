import { createEmptyDocument } from '../domain/defaults'
import type { InventoryDocument, LocalEnvelope } from '../types'

const DB_NAME = 'kaffee-bestand'
const STORE = 'state'
const KEY = 'inventory'

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1)
    request.onupgradeneeded = () => request.result.createObjectStore(STORE)
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

export async function loadLocal(): Promise<LocalEnvelope> {
  const db = await openDb()
  const result = await new Promise<LocalEnvelope | undefined>((resolve, reject) => {
    const tx = db.transaction(STORE, 'readonly')
    const request = tx.objectStore(STORE).get(KEY)
    request.onsuccess = () => resolve(request.result as LocalEnvelope | undefined)
    request.onerror = () => reject(request.error)
  })
  db.close()
  return result || { document: createEmptyDocument(), pendingEventIds: [], pendingReplacement: false, lastRemoteEventIds: [] }
}

export async function saveLocal(envelope: LocalEnvelope): Promise<void> {
  const db = await openDb()
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(STORE, 'readwrite')
    tx.objectStore(STORE).put(envelope, KEY)
    tx.oncomplete = () => resolve()
    tx.onerror = () => reject(tx.error)
  })
  db.close()
}

export function validateDocument(value: unknown): InventoryDocument {
  if (!value || typeof value !== 'object') throw new Error('Die Datei enthält kein gültiges Objekt.')
  const doc = value as Partial<InventoryDocument>
  if (doc.schemaVersion !== 1) throw new Error('Nicht unterstützte oder fehlende schemaVersion.')
  if (!doc.settings || !Array.isArray(doc.events)) throw new Error('Einstellungen oder Ereignisliste fehlen.')
  const ids = new Set<string>()
  for (const event of doc.events) {
    if (!event.id || !event.type || !event.recordedAt || !event.effectiveAt || !Array.isArray(event.resourceIds)) {
      throw new Error('Mindestens ein Ereignis ist unvollständig.')
    }
    if (ids.has(event.id)) throw new Error(`Doppelte Ereignis-ID: ${event.id}`)
    ids.add(event.id)
  }
  return structuredClone(doc as InventoryDocument)
}
