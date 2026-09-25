import { reduceInventory } from './domain/reducer'
import type { InventoryDocument } from './types'

export function downloadJson(document: InventoryDocument) {
  download(`kaffee-bestand-${stamp()}.json`, JSON.stringify(document, null, 2), 'application/json')
}

export function downloadCsvFiles(document: InventoryDocument) {
  const state = reduceInventory(document)
  const eventRows = document.events.map(event => ({
    ID: event.id, Typ: event.type, Wirksam: event.effectiveAt, Erfasst: event.recordedAt,
    Ressourcen: event.resourceIds.join(', '), Grund: event.reason || '', Warnungen: event.warnings?.join(' | ') || '', Daten: JSON.stringify(event.data),
  }))
  const inventoryRows = [
    ...Object.values(state.packages).map(pkg => ({ Art: 'Packung', ID: pkg.id, Name: pkg.name, Status: pkg.openedAt ? 'geöffnet' : pkg.location, Gramm: pkg.availableGrams, Bezug: '' })),
    ...Object.values(state.lots).map(lot => ({ Art: 'Charge', ID: lot.id, Name: lot.name, Status: lot.archived ? 'archiviert' : lot.beanState, Gramm: lot.unallocatedGrams, Bezug: lot.packageId })),
  ]
  const jarRows = Object.values(state.jars).map(jar => ({ Glas: jar.id, Größe: jar.size, Status: jar.status, Charge: jar.lotId || '', Gramm: jar.grams, Eingefroren: jar.filledAt || '', Geöffnet: jar.openedAt || '' }))
  download(`ereignisse-${stamp()}.csv`, toCsv(eventRows), 'text/csv;charset=utf-8')
  download(`bestand-${stamp()}.csv`, toCsv(inventoryRows), 'text/csv;charset=utf-8')
  download(`glaeser-${stamp()}.csv`, toCsv(jarRows), 'text/csv;charset=utf-8')
}

function toCsv(rows: Array<Record<string, unknown>>) {
  if (!rows.length) return '\ufeff'
  const columns = Object.keys(rows[0])
  const quote = (value: unknown) => `"${String(value ?? '').replaceAll('"', '""')}"`
  return `\ufeff${columns.map(quote).join(';')}\r\n${rows.map(row => columns.map(column => quote(format(row[column]))).join(';')).join('\r\n')}`
}

const format = (value: unknown) => typeof value === 'number' ? value.toLocaleString('de-DE', { useGrouping: false, maximumFractionDigits: 2 }) : value
const stamp = () => new Date().toISOString().replaceAll(':', '-').slice(0, 19)

function download(name: string, content: string, type: string) {
  const url = URL.createObjectURL(new Blob([content], { type }))
  const anchor = Object.assign(document.createElement('a'), { href: url, download: name })
  anchor.click(); URL.revokeObjectURL(url)
}
