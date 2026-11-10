import type { CSSProperties } from 'react'
import type { InventoryState, JarState } from '../types'
import { portionCapacity } from '../domain/defaults'
import type { TileStyle } from './theme'

export type Status = JarState['status']
export const STATUS_WORD: Record<Status, string> = { free: 'Frei', planned: 'Geplant', frozen: 'Tiefgekühlt', active: 'Aktiv', cleaning: 'Zu reinigen' }
/** Anzeigeklasse je Status: planned wird wie frei dargestellt, cleaning heißt im Design „clean“. */
const cls = (status: Status) => status === 'cleaning' ? 'clean' : status === 'planned' ? 'free' : status

export const fmtGrams = (g: number) => `${(Math.round(g * 10) / 10).toLocaleString('de-DE', { maximumFractionDigits: 1 })} g`

/** Wie voll das Glas ist (0 bis 1): Inhalt geteilt durch die Fassung seiner Charge. Ohne Charge nur „voll oder leer“. */
export function fillRatio(jar: JarState, state: InventoryState): number {
  if (jar.grams <= 0 || (jar.status !== 'frozen' && jar.status !== 'active')) return 0
  const lot = jar.lotId ? state.lots[jar.lotId] : undefined
  if (!lot) return 1
  const capacity = portionCapacity(jar.size, lot.brewMethod) * (lot.baseGrams + lot.allowanceGrams)
  return capacity > 0 ? Math.max(0.08, Math.min(1, jar.grams / capacity)) : 1
}

/** Glas-Symbol: Deckel (offen bei aktiv), Rand (gestrichelt bei frei), optional Füllstand. */
export function JarIcon({ status, fill = 0, className = 'jar', style }: { status: Status; fill?: number; className?: string; style?: CSSProperties }) {
  const k = cls(status)
  const lid = k === 'active'
    ? <rect x="7" y="0.5" width="10" height="3" rx="1" fill="currentColor" transform="rotate(-24 7 2)" />
    : <rect x="5" y="1.5" width="10" height="3" rx="1" fill="currentColor" />
  return <svg className={className} style={style} viewBox="0 0 20 24" aria-hidden="true">
    {lid}
    <path d="M4 5h12v14a3 3 0 0 1-3 3H7a3 3 0 0 1-3-3z" fill="none" stroke="currentColor" strokeWidth="1.8" strokeDasharray={k === 'free' ? '3 2.4' : undefined} />
    {fill > 0 && <rect x="5" y={(21 - 15 * fill).toFixed(1)} width="10" height={(15 * fill).toFixed(1)} rx="1.5" fill="currentColor" opacity=".45" />}
  </svg>
}

export function JarTile({ jar, state, tileStyle, selected, onClick }: { jar: JarState; state: InventoryState; tileStyle: TileStyle; selected?: boolean; onClick?: () => void }) {
  const k = cls(jar.status)
  const word = jar.status === 'cleaning' ? 'leer' : jar.grams > 0 ? fmtGrams(jar.grams) : 'frei'
  const label = `Glas ${jar.id}, ${STATUS_WORD[jar.status]}${jar.grams > 0 ? `, ${fmtGrams(jar.grams)}` : ''}`
  return <button type="button" aria-label={label} className={`tile s-${k} ts-${tileStyle}${selected ? ' selected' : ''}`} onClick={onClick}>
    <JarIcon status={jar.status} fill={tileStyle === 'fuellstand' ? fillRatio(jar, state) : 0} />
    <b>{jar.id}</b><small>{word}</small>
  </button>
}

/** Nicht klickbare Kachel für Vorschauen (Einstellungen). */
export function PreviewTile({ status, id, grams, tileStyle, fill }: { status: Status; id: string; grams: number; tileStyle: TileStyle; fill: number }) {
  const k = cls(status)
  return <div className={`tile s-${k} ts-${tileStyle}`}>
    <JarIcon status={status} fill={tileStyle === 'fuellstand' ? fill : 0} />
    <b>{id}</b><small>{status === 'cleaning' ? 'leer' : grams > 0 ? fmtGrams(grams) : 'frei'}</small>
  </div>
}

export function StatusBadge({ status }: { status: Status }) {
  return <span className={`badge s-${cls(status)}`}><JarIcon status={status} style={{ width: 14, height: 17 }} />{STATUS_WORD[status]}</span>
}

/** Legende: kleines Kachelsymbol plus das Wort. */
export function Legend({ tileStyle }: { tileStyle: TileStyle }) {
  const items: Array<[Status, number]> = [['free', 0], ['frozen', 1], ['active', 0.45], ['cleaning', 0]]
  return <div className="key" aria-label="Legende">{items.map(([status, fill]) =>
    <span key={status}><span className={`lg s-${cls(status)} ts-${tileStyle}`}><JarIcon status={status} fill={tileStyle === 'fuellstand' ? fill : 0} style={{ width: 14, height: 17 }} /></span>{STATUS_WORD[status]}</span>)}</div>
}
