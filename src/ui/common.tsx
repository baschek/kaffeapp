import { useEffect, useState, type ReactNode } from 'react'
import type { BrewMethod } from '../types'

export function Empty({ title, text, action, onAction }: { title: string; text: string; action?: string; onAction?: () => void }) {
  return <div className="empty"><span aria-hidden="true">○</span><strong>{title}</strong><p>{text}</p>{action && <button className="text-button" onClick={onAction}>{action} →</button>}</div>
}

export function PageHeading({ eyebrow, title, lead, small, children }: { eyebrow: string; title: string; lead?: string; small?: boolean; children?: ReactNode }) {
  return <div className={`page-heading${small ? ' small' : ''}`}><div><p className="eyebrow">{eyebrow}</p><h1>{title}</h1>{lead && <p className="lead">{lead}</p>}</div>{children}</div>
}

export function PanelHead({ eyebrow, title, action, onAction }: { eyebrow: string; title: string; action?: string; onAction?: () => void }) {
  return <div className="panel-head"><div><p className="eyebrow">{eyebrow}</p><h2>{title}</h2></div>{action && <button className="text-button" onClick={onAction}>{action}</button>}</div>
}

export const MethodBadge = ({ method }: { method: BrewMethod }) =>
  <span className={`method-badge ${method}`} aria-hidden="true">{method === 'espresso' ? 'E' : 'F'}</span>
export const methodWord = (method: BrewMethod) => method === 'espresso' ? 'Espresso' : 'Filter'

export function useMediaQuery(query: string): boolean {
  const get = () => typeof matchMedia === 'function' && matchMedia(query).matches
  const [matches, setMatches] = useState(get)
  useEffect(() => {
    const list = matchMedia(query)
    const onChange = () => setMatches(list.matches)
    onChange(); list.addEventListener('change', onChange)
    return () => list.removeEventListener('change', onChange)
  }, [query])
  return matches
}

const svg = (children: ReactNode) => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{children}</svg>
export const NavIcon = {
  dashboard: svg(<path d="M4 11l8-7 8 7v9H4z" />),
  jars: svg(<><rect x="6" y="3" width="12" height="3" rx="1" /><path d="M5 6h14v12a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3z" /></>),
  coffee: svg(<path d="M5 8h12v6a5 5 0 0 1-5 5h-2a5 5 0 0 1-5-5zM17 10h2a2 2 0 0 1 0 4h-2" />),
  history: svg(<><circle cx="12" cy="12" r="8" /><path d="M12 8v4l3 2" /></>),
  settings: svg(<><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3h0a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8v0a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" /></>),
}
export type NavId = keyof typeof NavIcon

export const EVENT_LABEL: Record<string, string> = {
  PACKAGE_CREATED: 'Packung angelegt', PACKAGE_FROZEN: 'Packung eingefroren', PACKAGE_OPENED: 'Packung geöffnet', LOT_CREATED: 'Teilcharge erstellt',
  LOT_GROUND: 'Vorratsmahlung', ALLOCATION_COMMITTED: 'Gläser befüllt', JAR_OPENED: 'Glas geöffnet', TRANSFER_COMMITTED: 'Kaffee umgefüllt',
  CONSUMED: 'Kaffee verbraucht', JAR_CLEANED: 'Glas gereinigt', CATEGORY_CHANGED: 'Kategorie geändert', CATEGORY_DEFINED: 'Kategorie angelegt',
  SETTINGS_CHANGED: 'Einstellung geändert', ARCHIVED: 'Archiviert', RULE_OVERRIDE: 'Regelabweichung', MEASUREMENT_ADJUSTMENT: 'Messkorrektur',
  CORRECTION: 'Fehler-Gegenbuchung',
}
