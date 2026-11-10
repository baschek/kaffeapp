import { PALETTES, TILE_STYLES, setLook, useLook, type Appearance } from './theme'
import { PreviewTile } from './JarTile'

const PREVIEW = [
  { status: 'frozen', id: 'S01', grams: 15, fill: 1 },
  { status: 'active', id: 'S05', grams: 18, fill: 0.55 },
  { status: 'cleaning', id: 'S10', grams: 0, fill: 0 },
  { status: 'free', id: 'S02', grams: 0, fill: 0 },
] as const

/** Einstellungen → Darstellung: Farben, Glas-Stil, Modus. Wirkt sofort und wird nur lokal in diesem Browser gespeichert. */
export function AppearanceSettings() {
  const look = useLook()
  return <div className="appearance">
    <article className="panel settings-card">
      <div className="panel-head"><div><p className="eyebrow">Look</p><h2>Farben</h2></div></div>
      <div role="radiogroup" aria-label="Farben" className="options">
        {PALETTES.map(p => <button key={p.id} role="radio" aria-checked={look.palette === p.id} className="option" onClick={() => setLook({ palette: p.id })}>
          <span className={`swatches pal-${p.id} ${look.mode}`} aria-hidden="true"><i style={{ background: 'var(--brand)' }} /><i style={{ background: 'var(--status-frozen-bg)' }} /><i style={{ background: 'var(--status-active-bg)' }} /><i style={{ background: 'var(--status-clean-bg)' }} /><i style={{ background: 'var(--surface-100)' }} /></span>
          <span className="option-text"><b>{p.label}</b><small>{p.hint}</small></span><span className="radio" aria-hidden="true" />
        </button>)}
      </div>
    </article>
    <article className="panel settings-card">
      <div className="panel-head"><div><p className="eyebrow">Gläser</p><h2>Glas-Stil</h2></div></div>
      <div role="radiogroup" aria-label="Glas-Stil" className="options">
        {TILE_STYLES.map(t => <button key={t.id} role="radio" aria-checked={look.tileStyle === t.id} className="option stacked" onClick={() => setLook({ tileStyle: t.id })}>
          <span className="option-line"><span className="option-text"><b>{t.label}</b><small>{t.hint}</small></span><span className="radio" aria-hidden="true" /></span>
          <span className="preview-tiles">{PREVIEW.map(j => <PreviewTile key={j.id} {...j} tileStyle={t.id} />)}</span>
        </button>)}
      </div>
    </article>
    <article className="panel settings-card">
      <div className="panel-head"><div><p className="eyebrow">Helligkeit</p><h2>Modus</h2></div></div>
      <div role="radiogroup" aria-label="Modus" className="options two">
        {([['light', 'Hell'], ['dark', 'Dunkel']] as Array<[Appearance, string]>).map(([id, label]) =>
          <button key={id} role="radio" aria-checked={look.mode === id} className="option center" onClick={() => setLook({ mode: id })}><b>{label}</b></button>)}
      </div>
      <p className="muted">Die Auswahl gilt sofort überall und bleibt nur in diesem Browser gespeichert.</p>
    </article>
  </div>
}
