import { reduceInventory } from '../domain/reducer'
import type { DriveSnapshot, InventoryDocument, InventoryEvent, LocalEnvelope } from '../types'
import { DriveConflictError, DriveStore } from './drive'
import { loadLocal, saveLocal } from './local'

export interface SyncResult { envelope: LocalEnvelope; conflicts: InventoryEvent[]; snapshot?: DriveSnapshot }

export class InventoryRepository {
  readonly drive = new DriveStore()
  private snapshot?: DriveSnapshot | null

  async load() { return loadLocal() }

  async append(envelope: LocalEnvelope, events: InventoryEvent[]): Promise<LocalEnvelope> {
    const known = new Set(envelope.document.events.map(event => event.id))
    const additions = events.filter(event => !known.has(event.id))
    const next = {
      ...envelope,
      document: { ...envelope.document, events: [...envelope.document.events, ...additions] },
      pendingEventIds: [...new Set([...envelope.pendingEventIds, ...additions.map(event => event.id)])],
    }
    reduceInventory(next.document)
    await saveLocal(next)
    return next
  }

  async replace(envelope: LocalEnvelope, document: InventoryDocument): Promise<LocalEnvelope> {
    reduceInventory(document)
    const next = { ...envelope, document, pendingEventIds: [], pendingReplacement: true }
    await saveLocal(next)
    return next
  }

  async connect(clientId: string) { await this.drive.connect(clientId) }

  async sync(envelope: LocalEnvelope): Promise<SyncResult> {
    let remote = await this.drive.load()
    if (!remote) {
      remote = await this.drive.save(envelope.document, null)
      const clean = { ...envelope, pendingEventIds: [], pendingReplacement: false, lastRemoteEventIds: envelope.document.events.map(e => e.id) }
      await saveLocal(clean); this.snapshot = remote
      return { envelope: clean, conflicts: [], snapshot: remote }
    }
    this.snapshot = remote
    if (envelope.pendingReplacement) {
      await this.drive.createDailyBackup(remote.document, true)
      const saved = await this.drive.save(envelope.document, remote)
      const clean = { ...envelope, pendingEventIds: [], pendingReplacement: false, lastRemoteEventIds: envelope.document.events.map(e => e.id) }
      await saveLocal(clean); this.snapshot = saved
      return { envelope: clean, conflicts: [], snapshot: saved }
    }

    const pending = envelope.document.events.filter(event => envelope.pendingEventIds.includes(event.id))
    const lastKnown = new Set(envelope.lastRemoteEventIds)
    const newRemote = remote.document.events.filter(event => !lastKnown.has(event.id))
    const conflicts = pending.filter(local => newRemote.some(other => intersects(local.resourceIds, other.resourceIds)))
    if (conflicts.length) return { envelope, conflicts, snapshot: remote }

    const merged = mergeDocuments(remote.document, pending)
    try {
      const saved = await this.drive.save(merged, remote, remote.document)
      const clean: LocalEnvelope = { document: merged, pendingEventIds: [], pendingReplacement: false, lastRemoteEventIds: merged.events.map(e => e.id) }
      await saveLocal(clean); this.snapshot = saved
      return { envelope: clean, conflicts: [], snapshot: saved }
    } catch (error) {
      if (error instanceof DriveConflictError) return this.sync(envelope)
      throw error
    }
  }

  async manualBackup(envelope: LocalEnvelope) { await this.drive.createDailyBackup(envelope.document, true) }
}

function mergeDocuments(remote: InventoryDocument, localEvents: InventoryEvent[]): InventoryDocument {
  const events = new Map(remote.events.map(event => [event.id, event]))
  localEvents.forEach(event => events.set(event.id, event))
  return { ...remote, events: [...events.values()].sort((a, b) => a.recordedAt.localeCompare(b.recordedAt)) }
}

const intersects = (a: string[], b: string[]) => a.some(value => b.includes(value))
