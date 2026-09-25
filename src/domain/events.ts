import type { EventType, InventoryEvent } from '../types'

export const makeId = (prefix: string) => `${prefix}_${crypto.randomUUID()}`

export function createEvent<T>(
  type: EventType,
  data: T,
  resourceIds: string[],
  options: { effectiveAt?: string; reason?: string; warnings?: string[]; parentEventIds?: string[] } = {},
): InventoryEvent<T> {
  const now = new Date().toISOString()
  return {
    id: makeId('evt'),
    type,
    recordedAt: now,
    effectiveAt: options.effectiveAt || now,
    resourceIds: [...new Set(resourceIds)],
    parentEventIds: options.parentEventIds,
    reason: options.reason,
    warnings: options.warnings,
    data,
  }
}
