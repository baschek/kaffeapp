import { useSyncExternalStore } from 'react'

// Darstellung: Farbwelt, Glas-Stil und Modus. Nur lokal im Browser gespeichert, nie im Journal und nie synchronisiert.
export type Palette = 'sand' | 'frost' | 'schiefer' | 'mono'
export type TileStyle = 'ruhig' | 'fuellstand'
export type Appearance = 'light' | 'dark'
export interface Look { palette: Palette; tileStyle: TileStyle; mode: Appearance }

export const PALETTES: Array<{ id: Palette; label: string; hint: string }> = [
  { id: 'sand', label: 'Sand', hint: 'Warm, wie die bisherige App' },
  { id: 'frost', label: 'Frost', hint: 'Kühles Blaugrün' },
  { id: 'schiefer', label: 'Schiefer', hint: 'Ruhiges Blaugrau' },
  { id: 'mono', label: 'Mono', hint: 'Fast farblos' },
]
export const TILE_STYLES: Array<{ id: TileStyle; label: string; hint: string }> = [
  { id: 'ruhig', label: 'Ruhig', hint: 'Flache Töne, Status über Rand und Deckel' },
  { id: 'fuellstand', label: 'Füllstand', hint: 'Das Glas ist so voll wie sein Inhalt' },
]

const KEY = 'kaffee-bestand.look.v1'
const systemMode = (): Appearance => typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
const defaults = (): Look => ({ palette: 'sand', tileStyle: 'ruhig', mode: systemMode() })

function read(): Look {
  const base = defaults()
  try {
    const raw = JSON.parse(localStorage.getItem(KEY) || 'null') as Partial<Look> | null
    if (!raw) return base
    return {
      palette: PALETTES.some(p => p.id === raw.palette) ? raw.palette! : base.palette,
      tileStyle: TILE_STYLES.some(t => t.id === raw.tileStyle) ? raw.tileStyle! : base.tileStyle,
      mode: raw.mode === 'dark' || raw.mode === 'light' ? raw.mode : base.mode,
    }
  } catch { return base }
}

let current: Look = read()
const listeners = new Set<() => void>()

/** Farbwelt und Modus liegen als Klassen am Wurzelelement (pal-sand, dark), damit alle Tokens in styles.css greifen. */
export function applyLook(look: Look = current) {
  const root = document.documentElement
  root.classList.remove(...PALETTES.map(p => `pal-${p.id}`), 'dark', 'light')
  root.classList.add(`pal-${look.palette}`, look.mode)
  root.style.colorScheme = look.mode
  const sidebar = getComputedStyle(root).getPropertyValue('--sidebar').trim()
  if (sidebar) document.querySelector('meta[name="theme-color"]')?.setAttribute('content', sidebar)
}

export function setLook(patch: Partial<Look>) {
  current = { ...current, ...patch }
  try { localStorage.setItem(KEY, JSON.stringify(current)) } catch { /* privater Modus: Auswahl gilt nur bis zum Neuladen */ }
  applyLook(current)
  listeners.forEach(listener => listener())
}

export function useLook(): Look {
  return useSyncExternalStore(
    listener => { listeners.add(listener); return () => { listeners.delete(listener) } },
    () => current,
  )
}
