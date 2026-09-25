import { useEffect, useMemo, useRef, useState } from 'react'
import { createEvent, makeId } from './domain/events'
import { effectiveSettings, portionCapacity } from './domain/defaults'
import { formatPortions, planAllocation } from './domain/planner'
import { reduceInventory, round } from './domain/reducer'
import { downloadCsvFiles, downloadJson } from './exporter'
import { validateDocument } from './storage/local'
import { InventoryRepository } from './storage/repository'
import type {
  AllocationPlan, BeanState, BrewMethod, CoffeeLot, CoffeePackage, FlexibleDate, InventoryEvent,
  JarSize, JarState, LocalEnvelope, RuleProfile,
} from './types'

declare global {
  interface Document {
    readonly modelContext?: {
      registerTool(tool: { name: string; title?: string; description: string; inputSchema: object; annotations?: { readOnlyHint?: boolean; untrustedContentHint?: boolean }; execute(input: unknown): unknown | Promise<unknown> }, options?: { signal?: AbortSignal }): void | Promise<void>
    }
  }
}

type View = 'dashboard' | 'jars' | 'coffee' | 'history' | 'settings'
const repository = new InventoryRepository()
const statusLabel: Record<JarState['status'], string> = { free: 'Frei', planned: 'Geplant', frozen: 'Tiefgekühlt', active: 'Aktiv', cleaning: 'Zu reinigen' }

export default function App() {
  const [envelope, setEnvelope] = useState<LocalEnvelope | null>(null)
  const [view, setView] = useState<View>('dashboard')
  const [dialog, setDialog] = useState<'package' | 'allocate' | 'restore' | null>(null)
  const [selectedJar, setSelectedJar] = useState<string>()
  const [selectedLot, setSelectedLot] = useState<string>()
  const [query, setQuery] = useState('')
  const [message, setMessage] = useState('Lokaler Stand wird geladen …')
  const [syncing, setSyncing] = useState(false)
  const [conflictIds, setConflictIds] = useState<string[]>([])

  useEffect(() => { repository.load().then(value => { setEnvelope(value); setMessage(value.pendingEventIds.length ? 'Änderungen warten auf Synchronisierung' : 'Lokal gespeichert') }) }, [])
  const state = useMemo(() => envelope ? reduceInventory(envelope.document) : null, [envelope])
  const settings = useMemo(() => envelope ? effectiveSettings(envelope.document) : null, [envelope])

  useEffect(() => {
    if (!state || !document.modelContext?.registerTool) return
    const lifecycle = new AbortController()
    const register = (tool: Parameters<NonNullable<Document['modelContext']>['registerTool']>[0]) => {
      try { void Promise.resolve(document.modelContext!.registerTool(tool, { signal: lifecycle.signal })).catch(() => undefined) } catch { /* experiment unavailable */ }
    }
    register({
      name: 'read_inventory_summary', title: 'Kaffeebestand zusammenfassen',
      description: 'Liest den aktuellen Bestand an Packungen, Chargen und Gläsern, ohne Daten zu verändern.',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false }, annotations: { readOnlyHint: true, untrustedContentHint: false },
      execute: () => ({ packages: Object.keys(state.packages).length, lots: Object.keys(state.lots).length, jars: Object.fromEntries(['free', 'frozen', 'active', 'cleaning'].map(status => [status, Object.values(state.jars).filter(jar => jar.status === status).length])), pendingChanges: envelope?.pendingEventIds.length || 0 }),
    })
    register({
      name: 'navigate_to_jar', title: 'Glas anzeigen',
      description: 'Öffnet die Detailansicht einer vorhandenen Glasnummer wie S15, M04 oder L12.',
      inputSchema: { type: 'object', properties: { jarId: { type: 'string', pattern: '^[SML][0-9]{2}$' } }, required: ['jarId'], additionalProperties: false }, annotations: { readOnlyHint: true, untrustedContentHint: false },
      execute: input => { const id = String((input as { jarId?: string }).jarId || '').toUpperCase(); if (!state.jars[id]) throw new Error('Glasnummer nicht gefunden'); setView('jars'); setSelectedJar(id); return { jarId: id, status: state.jars[id].status, grams: state.jars[id].grams } },
    })
    register({
      name: 'start_package_creation', title: 'Neue Packung beginnen',
      description: 'Öffnet das Formular für eine neue Kaffeepackung; gespeichert wird erst nach sichtbarer Bestätigung.',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false }, annotations: { readOnlyHint: true, untrustedContentHint: false },
      execute: () => { setDialog('package'); return { status: 'form_opened' } },
    })
    return () => lifecycle.abort()
  }, [state, envelope?.pendingEventIds.length])

  const append = async (events: InventoryEvent[]) => {
    if (!envelope) return
    const next = await repository.append(envelope, events)
    setEnvelope(next); setMessage(`${next.pendingEventIds.length} Änderung${next.pendingEventIds.length === 1 ? '' : 'en'} wartet/warten auf Drive`)
    if (repository.drive.connected && navigator.onLine) void sync(next)
  }

  const sync = async (source = envelope) => {
    if (!source || !repository.drive.connected) return
    setSyncing(true); setMessage('Synchronisiere mit Google Drive …')
    try {
      const result = await repository.sync(source)
      setEnvelope(result.envelope); setConflictIds(result.conflicts.map(event => event.id))
      setMessage(result.conflicts.length ? `${result.conflicts.length} Konflikt${result.conflicts.length === 1 ? '' : 'e'} benötigt/benötigen Klärung` : 'Mit Google Drive synchronisiert')
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Synchronisierung fehlgeschlagen') }
    finally { setSyncing(false) }
  }

  const connect = async () => {
    setSyncing(true)
    try {
      await repository.connect(import.meta.env.VITE_GOOGLE_CLIENT_ID || '')
      await sync(envelope)
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Google-Verbindung fehlgeschlagen') }
    finally { setSyncing(false) }
  }

  const openSearch = () => {
    const id = query.trim().toUpperCase()
    if (state?.jars[id]) { setSelectedJar(id); setView('jars'); setQuery('') }
    else setMessage(`Glas „${id}“ wurde nicht gefunden.`)
  }

  if (!envelope || !state || !settings) return <div className="loading">Kaffee-Bestand wird vorbereitet …</div>

  const nav: Array<[View, string, string]> = [
    ['dashboard', 'Übersicht', '⌂'], ['jars', 'Gläser', '▦'], ['coffee', 'Kaffees', '◉'], ['history', 'Verlauf', '↗'], ['settings', 'Daten', '⋯'],
  ]

  return <div className="app-shell">
    <aside className="sidebar">
      <div className="brand"><span className="brand-mark">K</span><div><strong>Kaffee-Bestand</strong><small>96 Gläser · eine Geschichte</small></div></div>
      <nav>{nav.map(([id, label, icon]) => <button key={id} className={view === id ? 'active' : ''} onClick={() => setView(id)}><span>{icon}</span>{label}</button>)}</nav>
      <div className="side-status">
        <span className={`sync-dot ${repository.drive.connected ? 'online' : ''}`} />
        <div><strong>{repository.drive.connected ? 'Drive verbunden' : 'Nur lokal'}</strong><small>{envelope.pendingEventIds.length} ausstehend</small></div>
      </div>
    </aside>

    <main>
      <header className="topbar">
        <div className="mobile-brand">Kaffee-Bestand</div>
        <form className="jar-search" onSubmit={event => { event.preventDefault(); openSearch() }}>
          <input value={query} onChange={e => setQuery(e.target.value)} placeholder="Glas suchen, z. B. S15" aria-label="Glasnummer suchen" />
          <button type="submit">Suchen</button>
        </form>
        <button className="sync-button" onClick={repository.drive.connected ? () => sync() : connect} disabled={syncing}>
          {syncing ? 'Bitte warten …' : repository.drive.connected ? 'Jetzt synchronisieren' : 'Mit Google Drive verbinden'}
        </button>
      </header>

      <div className="status-strip" role="status"><span>{message}</span>{!navigator.onLine && <b>Offline</b>}{conflictIds.length > 0 && <button onClick={() => setView('history')}>Konflikte ansehen</button>}</div>

      {view === 'dashboard' && <Dashboard state={state} settings={settings} onAdd={() => setDialog('package')} onAllocate={() => setDialog('allocate')} onJar={(id: string) => { setSelectedJar(id); setView('jars') }} onView={setView} />}
      {view === 'jars' && <JarMatrix state={state} selected={selectedJar} onSelect={setSelectedJar} />}
      {view === 'coffee' && <CoffeeView state={state} settings={settings} selected={selectedLot} onSelect={setSelectedLot} onAllocate={() => setDialog('allocate')} />}
      {view === 'history' && <HistoryView events={envelope.document.events} corrected={state.correctedEventIds} conflicts={conflictIds} onCorrect={event => {
        const reason = window.prompt('Warum ist diese Buchung fehlerhaft?')?.trim()
        if (reason) void append([createEvent('CORRECTION', { targetEventId: event.id }, event.resourceIds, { reason, parentEventIds: [event.id] })])
      }} />}
      {view === 'settings' && <DataView envelope={envelope} settings={settings} state={state} connected={repository.drive.connected} onBackup={async () => {
        try { await repository.manualBackup(envelope); setMessage('Manuelle Sicherung in Google Drive erstellt') } catch (error) { setMessage(error instanceof Error ? error.message : 'Sicherung fehlgeschlagen') }
      }} onRestore={() => setDialog('restore')} onAppend={append} />}
    </main>

    <nav className="mobile-nav">{nav.slice(0, 4).map(([id, label, icon]) => <button key={id} className={view === id ? 'active' : ''} onClick={() => setView(id)}><span>{icon}</span>{label}</button>)}</nav>
    {dialog === 'package' && <AddPackageDialog onClose={() => setDialog(null)} onSave={events => { void append(events); setDialog(null) }} />}
    {dialog === 'allocate' && <AllocateDialog state={state} settings={settings} onClose={() => setDialog(null)} onSave={events => { void append(events); setDialog(null); setView('jars') }} />}
    {dialog === 'restore' && <RestoreDialog onClose={() => setDialog(null)} onRestore={async doc => { const next = await repository.replace(envelope, doc); setEnvelope(next); setDialog(null); setMessage('Wiederherstellung vorgemerkt – bitte mit Drive synchronisieren') }} />}
    {selectedJar && <JarDetail jar={state.jars[selectedJar]} state={state} settings={settings} events={envelope.document.events} onClose={() => setSelectedJar(undefined)} onAppend={append} />}
  </div>
}

function Dashboard({ state, settings, onAdd, onAllocate, onJar, onView }: any) {
  const jars = Object.values(state.jars) as JarState[]
  const counts = (size: JarSize) => jars.filter(j => j.size === size && j.status === 'free').length
  const cleaning = jars.filter(j => j.status === 'cleaning').length
  const frozen = jars.filter(j => j.status === 'frozen').length
  const active = jars.filter(j => j.status === 'active').length
  const packages = Object.values(state.packages) as CoffeePackage[]
  const lots = Object.values(state.lots) as CoffeeLot[]
  return <section className="page dashboard">
    <div className="page-heading"><div><p className="eyebrow">Bestand heute</p><h1>Alles an seinem Platz.</h1></div><div className="actions"><button className="secondary" onClick={onAllocate}>Kaffee öffnen & aufteilen</button><button className="primary" onClick={onAdd}>Neue Packung</button></div></div>
    <div className="metric-grid">
      <Metric label="Tiefgekühlte Gläser" value={frozen} detail={`${active} aktiv aufgetaut`} tone="coffee" />
      <Metric label="Freie Gläser" value={counts('S') + counts('M') + counts('L')} detail={`${counts('S')} S · ${counts('M')} M · ${counts('L')} L`} tone="ice" />
      <Metric label="Ungeöffnete Packungen" value={packages.filter(p => !p.openedAt && !p.archived).length} detail={`${packages.filter(p => p.location === 'frozen' && !p.openedAt).length} komplett eingefroren`} tone="gold" />
      <Metric label="Zu reinigen" value={cleaning} detail={cleaning ? 'Noch nicht wieder verfügbar' : 'Alle Gläser einsatzbereit'} tone="slate" />
    </div>
    <div className="dashboard-grid">
      <article className="panel inventory-panel"><div className="panel-head"><div><p className="eyebrow">Kaffees</p><h2>Verfügbare Portionen</h2></div><button className="text-button" onClick={() => onView('coffee')}>Alle ansehen →</button></div>
        {lots.length === 0 ? <Empty title="Noch keine portionierte Charge" text="Erfasse zuerst eine Packung und teile sie anschließend auf." action="Packung hinzufügen" onAction={onAdd} /> :
          <div className="coffee-list">{lots.filter(l => !l.archived).slice(0, 6).map(lot => {
            const grams = jars.filter(j => j.lotId === lot.id).reduce((sum, jar) => sum + jar.grams, 0) + lot.unallocatedGrams
            const portion = lot.baseGrams + lot.allowanceGrams
            return <button key={lot.id} className="coffee-row" onClick={() => onView('coffee')}><span className={`method-badge ${lot.brewMethod}`}>{lot.brewMethod === 'espresso' ? 'E' : 'F'}</span><span><strong>{lot.name}</strong><small>{settings.categories.find((c: any) => c.id === lot.categoryId)?.name || 'Eigene Kategorie'}</small></span><b>{formatPortions(grams, portion)}</b></button>
          })}</div>}
      </article>
      <article className="panel jar-preview"><div className="panel-head"><div><p className="eyebrow">Gefrierfach</p><h2>Glasstatus</h2></div><button className="text-button" onClick={() => onView('jars')}>Matrix öffnen →</button></div>
        <div className="mini-jars">{jars.filter(j => j.status !== 'free').slice(0, 24).map(jar => <button key={jar.id} className={`mini-jar ${jar.status}`} onClick={() => onJar(jar.id)}><b>{jar.id}</b><small>{Math.round(jar.grams)} g</small></button>)}</div>
        {!jars.some(j => j.status !== 'free') && <Empty title="Alle Gläser sind frei" text="Sobald du eine Charge bestätigst, erscheint sie hier." />}
      </article>
    </div>
    {state.overrides.length > 0 && <div className="notice warning"><b>{state.overrides.length} dokumentierte Regelabweichung{state.overrides.length === 1 ? '' : 'en'}</b><span>Im Verlauf bleiben Begründung und betroffene Charge dauerhaft sichtbar.</span></div>}
  </section>
}

function Metric({ label, value, detail, tone }: { label: string; value: number; detail: string; tone: string }) {
  return <article className={`metric ${tone}`}><span>{label}</span><strong>{value}</strong><small>{detail}</small></article>
}

function Empty({ title, text, action, onAction }: { title: string; text: string; action?: string; onAction?: () => void }) {
  return <div className="empty"><span>○</span><strong>{title}</strong><p>{text}</p>{action && <button className="text-button" onClick={onAction}>{action} →</button>}</div>
}

function JarMatrix({ state, selected, onSelect }: { state: ReturnType<typeof reduceInventory>; selected?: string; onSelect: (id: string) => void }) {
  return <section className="page"><div className="page-heading"><div><p className="eyebrow">S01 bis L12</p><h1>Alle 96 Gläser</h1><p className="lead">Ein Klick zeigt Inhalt, Herkunft und die nächsten möglichen Aktionen.</p></div><div className="legend"><span><i className="free" />Frei</span><span><i className="frozen" />Tiefgekühlt</span><span><i className="active" />Aktiv</span><span><i className="cleaning" />Reinigung</span></div></div>
    {(['S', 'M', 'L'] as JarSize[]).map(size => <article className="panel jar-group" key={size}><div className="jar-group-title"><div className="size-token">{size}</div><div><h2>{size === 'S' ? 'Einzelportionen' : size === 'M' ? 'Mehrportionen' : 'Großgläser'}</h2><small>{size === 'S' ? '53 ml · 48 Gläser' : size === 'M' ? '212 ml · 36 Gläser' : '555 ml · 12 Gläser'}</small></div></div><div className={`jar-grid size-${size.toLowerCase()}`}>{Object.values(state.jars).filter(j => j.size === size).map(jar => <button key={jar.id} aria-label={`${jar.id}, ${statusLabel[jar.status]}`} className={`jar-tile ${jar.status} ${selected === jar.id ? 'selected' : ''}`} onClick={() => onSelect(jar.id)}><strong>{jar.id}</strong>{jar.grams > 0 && <small>{jar.grams.toLocaleString('de-DE')} g</small>}</button>)}</div></article>)}
  </section>
}

function CoffeeView({ state, settings, selected, onSelect, onAllocate }: any) {
  const packages = Object.values(state.packages) as CoffeePackage[]
  const lots = Object.values(state.lots) as CoffeeLot[]
  const lot = selected ? state.lots[selected] : lots[0]
  return <section className="page"><div className="page-heading"><div><p className="eyebrow">Packungen & Chargen</p><h1>Der Weg jeder Bohne</h1></div><button className="primary" onClick={onAllocate}>Packung öffnen</button></div>
    <div className="coffee-layout"><div className="panel coffee-index"><h2>Ungeöffnete Packungen</h2>{packages.filter(p => !p.openedAt && !p.archived).map(pkg => <div className="package-card" key={pkg.id}><span className="package-icon">▰</span><div><strong>{pkg.name}</strong><small>{pkg.availableGrams.toLocaleString('de-DE')} g · {pkg.location === 'frozen' ? 'komplett tiefgekühlt' : 'Vorrat'}</small></div></div>)}{!packages.some(p => !p.openedAt && !p.archived) && <p className="muted">Keine ungeöffneten Packungen.</p>}
      <h2>Teilchargen</h2>{lots.filter(l => !l.archived).map(item => <button key={item.id} className={`lot-button ${lot?.id === item.id ? 'active' : ''}`} onClick={() => onSelect(item.id)}><span className={`method-badge ${item.brewMethod}`}>{item.brewMethod === 'espresso' ? 'E' : 'F'}</span><span><strong>{item.name}</strong><small>{settings.categories.find((c: any) => c.id === item.categoryId)?.name}</small></span></button>)}</div>
      <div className="panel lineage">{lot ? <LotLineage lot={lot} state={state} settings={settings} /> : <Empty title="Noch keine Teilcharge" text="Öffne eine Packung und bestätige ihre Glasaufteilung." />}</div></div>
  </section>
}

function LotLineage({ lot, state, settings }: { lot: CoffeeLot; state: ReturnType<typeof reduceInventory>; settings: any }) {
  const pkg = state.packages[lot.packageId]
  const jars = Object.values(state.jars).filter(j => j.lotId === lot.id)
  const total = jars.reduce((sum, jar) => sum + jar.grams, 0) + lot.unallocatedGrams
  return <><div className="lineage-head"><div><p className="eyebrow">Herkunftsbaum</p><h2>{lot.name}</h2><p>{lot.brewMethod === 'espresso' ? 'Espresso' : 'Filter'} · {lot.beanState === 'whole' ? 'ganze Bohne' : 'gemahlen'} · {formatPortions(total, lot.baseGrams + lot.allowanceGrams)}</p></div><span className="weight-pill">{total.toLocaleString('de-DE')} g</span></div>
    <div className="tree"><div className="tree-node root"><small>Ursprungspackung</small><strong>{pkg?.name}</strong><span>{pkg?.nominalGrams.toLocaleString('de-DE')} g Nenngewicht</span></div><div className="tree-line" /><div className="tree-node"><small>Teilcharge</small><strong>{settings.categories.find((c: any) => c.id === lot.categoryId)?.name || 'Eigene Kategorie'}</strong><span>{lot.baseGrams + lot.allowanceGrams} g je Portion</span></div><div className="tree-line" /><div className="tree-children">{jars.map(jar => <div className={`tree-node jar ${jar.status}`} key={jar.id}><small>{statusLabel[jar.status]}</small><strong>{jar.id}</strong><span>{jar.grams.toLocaleString('de-DE')} g</span></div>)}{lot.unallocatedGrams > 0 && <div className="tree-node remainder"><small>Nicht eingelagert</small><strong>Restmenge</strong><span>{lot.unallocatedGrams.toLocaleString('de-DE')} g</span></div>}</div></div>
    <div className="lot-facts"><div><span>Auftauvorgänge</span><b>{Math.max(0, ...jars.map(j => j.thawCount))}</b></div><div><span>Wiedereinfrierungen</span><b>{Math.max(0, ...jars.map(j => j.refreezeCount))}</b></div><div><span>Regelprofil</span><b>{lot.ruleProfile}</b></div></div></>
}

function HistoryView({ events, corrected, conflicts, onCorrect }: { events: InventoryEvent[]; corrected: Set<string>; conflicts: string[]; onCorrect: (event: InventoryEvent) => void }) {
  const labels: Record<string, string> = { PACKAGE_CREATED: 'Packung angelegt', PACKAGE_FROZEN: 'Packung eingefroren', PACKAGE_OPENED: 'Packung geöffnet', LOT_CREATED: 'Teilcharge erstellt', LOT_GROUND: 'Vorratsmahlung', ALLOCATION_COMMITTED: 'Gläser befüllt', JAR_OPENED: 'Glas geöffnet', TRANSFER_COMMITTED: 'Kaffee umgefüllt', CONSUMED: 'Kaffee verbraucht', JAR_CLEANED: 'Glas gereinigt', CATEGORY_CHANGED: 'Kategorie geändert', CATEGORY_DEFINED: 'Kategorie angelegt', SETTINGS_CHANGED: 'Einstellung geändert', ARCHIVED: 'Archiviert', RULE_OVERRIDE: 'Regelabweichung', MEASUREMENT_ADJUSTMENT: 'Messkorrektur', CORRECTION: 'Fehler-Gegenbuchung' }
  return <section className="page"><div className="page-heading"><div><p className="eyebrow">Unveränderliches Journal</p><h1>Vollständiger Verlauf</h1><p className="lead">Fehler verschwinden nicht: Eine Gegenbuchung hebt ihre Bestandswirkung nachvollziehbar auf.</p></div></div><article className="panel timeline">{[...events].sort((a, b) => b.effectiveAt.localeCompare(a.effectiveAt)).map(event => <div className={`timeline-row ${corrected.has(event.id) ? 'corrected' : ''} ${conflicts.includes(event.id) ? 'conflict' : ''}`} key={event.id}><div className="timeline-mark" /><div><div className="timeline-title"><strong>{labels[event.type] || event.type}</strong><time>{new Date(event.effectiveAt).toLocaleString('de-DE')}</time></div><p>{event.resourceIds.join(' · ') || 'Bestand'}{event.reason ? ` — ${event.reason}` : ''}</p>{event.warnings?.map(w => <span className="event-warning" key={w}>{w}</span>)}<small>{event.id}</small></div>{event.type !== 'CORRECTION' && !corrected.has(event.id) && <button className="icon-button" onClick={() => onCorrect(event)}>Fehler buchen</button>}</div>)}{events.length === 0 && <Empty title="Noch keine Ereignisse" text="Die erste erfasste Packung beginnt dein Journal." />}</article></section>
}

function DataView({ envelope, settings, state, connected, onBackup, onRestore, onAppend }: any) {
  const [name, setName] = useState('')
  const [profile, setProfile] = useState<RuleProfile>('A')
  return <section className="page"><div className="page-heading"><div><p className="eyebrow">Sicherung & Regeln</p><h1>Deine Daten bleiben greifbar.</h1><p className="lead">Die Drive-Datei ist die zentrale Quelle; JSON und CSV kannst du jederzeit zusätzlich herunterladen.</p></div></div><div className="settings-grid">
    <article className="panel settings-card"><h2>Export & Wiederherstellung</h2><p>Vollständiges Ereignisjournal: <b>{envelope.document.events.length} Buchungen</b></p><div className="button-stack"><button className="primary" onClick={() => downloadJson(envelope.document)}>Vollständiges JSON laden</button><button className="secondary" onClick={() => downloadCsvFiles(envelope.document)}>Drei CSV-Dateien laden</button><button className="secondary" onClick={onRestore}>JSON wiederherstellen</button><button className="secondary" disabled={!connected} onClick={onBackup}>Manuelle Drive-Sicherung</button></div></article>
    <article className="panel settings-card"><h2>Eigene Kategorie</h2><p>Eigene Bezeichnungen nutzen weiterhin ein klar festgelegtes Regelprofil.</p><form onSubmit={event => { event.preventDefault(); if (!name.trim()) return; const id = makeId('category'); void onAppend([createEvent('CATEGORY_DEFINED', { category: { id, name: name.trim(), ruleProfile: profile, builtIn: false } }, [id])]); setName('') }}><label>Name<input value={name} onChange={e => setName(e.target.value)} placeholder="z. B. Wettbewerb" /></label><label>Regelprofil<select value={profile} onChange={e => setProfile(e.target.value as RuleProfile)}><option value="A">A · M und S</option><option value="B">B · L und M</option><option value="DIRECT_S">Direkt in S</option><option value="MANUAL">Manuell</option></select></label><button className="primary" type="submit">Kategorie anlegen</button></form><div className="category-tags">{settings.categories.map((cat: any) => <span key={cat.id}>{cat.name} · {cat.ruleProfile}</span>)}</div></article>
    <article className="panel settings-card"><h2>Datenprüfung</h2><dl><div><dt>Gläser</dt><dd>{Object.keys(state.jars).length}</dd></div><div><dt>Packungen</dt><dd>{Object.keys(state.packages).length}</dd></div><div><dt>Teilchargen</dt><dd>{Object.keys(state.lots).length}</dd></div><div><dt>Ableitungswarnungen</dt><dd>{state.warnings.length}</dd></div></dl>{state.warnings.map((warning: string) => <p className="error-text" key={warning}>{warning}</p>)}</article>
  </div></section>
}

function AddPackageDialog({ onClose, onSave }: { onClose: () => void; onSave: (events: InventoryEvent[]) => void }) {
  const [name, setName] = useState('')
  const [grams, setGrams] = useState(250)
  const [roast, setRoast] = useState('')
  const [roastPrecision, setRoastPrecision] = useState<FlexibleDate['precision']>('exact')
  const [purchase, setPurchase] = useState(new Date().toISOString().slice(0, 10))
  const [purchasePrecision, setPurchasePrecision] = useState<FlexibleDate['precision']>('exact')
  const [location, setLocation] = useState<CoffeePackage['location']>('pantry')
  const save = (event: React.FormEvent) => {
    event.preventDefault(); if (!name.trim() || grams <= 0) return
    const id = makeId('package'); const now = new Date().toISOString()
    const pkg: CoffeePackage = { id, name: name.trim(), nominalGrams: grams, availableGrams: grams, roastDate: { value: roast || undefined, precision: roast ? roastPrecision : 'unknown' }, purchaseDate: { value: purchase || undefined, precision: purchase ? purchasePrecision : 'unknown' }, location, frozenAt: location === 'frozen' ? now : undefined, archived: false }
    const events: InventoryEvent[] = [createEvent('PACKAGE_CREATED', { package: pkg }, [id], { effectiveAt: purchase ? `${purchase}T12:00:00.000Z` : now })]
    if (location === 'frozen') events.push(createEvent('PACKAGE_FROZEN', { packageId: id, frozenAt: now }, [id]))
    onSave(events)
  }
  return <Modal title="Neue Packung" subtitle="Ungeöffnet im Vorrat oder komplett tiefgekühlt" onClose={onClose}><form onSubmit={save} className="form-grid"><label className="span-2">Kaffeename<input autoFocus value={name} onChange={e => setName(e.target.value)} placeholder="z. B. Sterne Espresso" required /></label><label>Nenngewicht in Gramm<input type="number" min="0.1" step="0.1" value={grams} onChange={e => setGrams(Number(e.target.value))} required /></label><label>Lagerort<select value={location} onChange={e => setLocation(e.target.value as CoffeePackage['location'])}><option value="pantry">Vorrat</option><option value="frozen">Komplett tiefgekühlt</option></select></label><DateField label="Röstdatum" value={roast} precision={roastPrecision} onValue={setRoast} onPrecision={setRoastPrecision} /><DateField label="Kaufdatum" value={purchase} precision={purchasePrecision} onValue={setPurchase} onPrecision={setPurchasePrecision} /><div className="modal-actions span-2"><button type="button" className="secondary" onClick={onClose}>Abbrechen</button><button className="primary" type="submit">Packung speichern</button></div></form></Modal>
}

function AllocateDialog({ state, settings, onClose, onSave }: { state: ReturnType<typeof reduceInventory>; settings: ReturnType<typeof effectiveSettings>; onClose: () => void; onSave: (events: InventoryEvent[]) => void }) {
  const unopened = Object.values(state.packages).filter(pkg => !pkg.archived && pkg.availableGrams > 0)
  const [packageId, setPackageId] = useState(unopened[0]?.id || '')
  const pkg = state.packages[packageId]
  const [actual, setActual] = useState(pkg?.actualGrams || pkg?.nominalGrams || 0)
  const [amount, setAmount] = useState(pkg?.availableGrams || 0)
  const [brew, setBrew] = useState<BrewMethod>('espresso')
  const [bean, setBean] = useState<BeanState>('whole')
  const [categoryId, setCategoryId] = useState('premium-whole')
  const category = settings.categories.find(item => item.id === categoryId) || settings.categories[0]
  const [manualProfile, setManualProfile] = useState<RuleProfile>('A')
  const [base, setBase] = useState(18)
  const [allowance, setAllowance] = useState(0.2)
  const [directS, setDirectS] = useState(false)
  const [draft, setDraft] = useState<{ plan: AllocationPlan; lot: CoffeeLot; openedAt: string }>()
  const [warningConfirmed, setWarningConfirmed] = useState(false)
  const [error, setError] = useState('')
  const updatePackage = (id: string) => { const next = state.packages[id]; setPackageId(id); setActual(next.actualGrams || next.nominalGrams); setAmount(next.availableGrams) }
  const updateBrew = (value: BrewMethod) => { setBrew(value); setBase(value === 'espresso' ? settings.espressoBaseGrams : settings.filterBaseGrams); setAllowance(value === 'espresso' ? settings.espressoAllowanceGrams : settings.filterAllowanceGrams) }
  const makePlan = (event: React.FormEvent) => {
    event.preventDefault(); setError('')
    try {
      if (!pkg) throw new Error('Bitte zuerst eine Packung anlegen.')
      const available = pkg.openedAt ? pkg.availableGrams : actual
      if (amount <= 0 || amount > available + 0.001) throw new Error('Die Teilmenge übersteigt das verfügbare Gewicht.')
      const profile = category.ruleProfile === 'MANUAL' ? manualProfile : category.ruleProfile
      const lot: CoffeeLot = { id: makeId('lot'), packageId, name: pkg.name, brewMethod: brew, beanState: bean, categoryId, ruleProfile: profile, baseGrams: base, allowanceGrams: allowance, initialGrams: amount, unallocatedGrams: amount, thawCount: pkg.location === 'frozen' ? 1 : 0, refreezeCount: pkg.location === 'frozen' ? 1 : 0, archived: false }
      const plan = planAllocation({ grams: amount, brewMethod: brew, baseGrams: base, allowanceGrams: allowance, ruleProfile: profile, premiumDirectS: directS, state })
      setDraft({ plan, lot, openedAt: new Date().toISOString() }); setWarningConfirmed(false)
    } catch (e) { setError(e instanceof Error ? e.message : 'Plan konnte nicht berechnet werden.') }
  }
  const commit = () => {
    if (!draft || !pkg) return
    const sum = round(draft.plan.fills.reduce((total, fill) => total + fill.actualGrams, 0))
    if (sum > draft.lot.initialGrams + 0.001) { setError('Die bestätigten Glasgewichte überschreiten die Teilcharge.'); return }
    if (new Set(draft.plan.fills.map(fill => fill.jarId)).size !== draft.plan.fills.length) { setError('Eine Glasnummer wurde doppelt verwendet.'); return }
    if (draft.plan.warnings.length && !warningConfirmed) { setError('Bitte bestätige die Hinweise vor der Einlagerung.'); return }
    const events: InventoryEvent[] = []
    if (!pkg.openedAt) events.push(createEvent('PACKAGE_OPENED', { packageId, actualGrams: actual, openedAt: draft.openedAt }, [packageId], { effectiveAt: draft.openedAt }))
    events.push(createEvent('LOT_CREATED', { lot: draft.lot }, [packageId, draft.lot.id], { effectiveAt: draft.openedAt }))
    events.push(createEvent('ALLOCATION_COMMITTED', { lotId: draft.lot.id, fills: draft.plan.fills.map(fill => ({ jarId: fill.jarId, grams: fill.actualGrams, frozenAt: draft.openedAt })), unallocatedGrams: round(draft.lot.initialGrams - sum) }, [draft.lot.id, ...draft.plan.fills.map(fill => fill.jarId)], { warnings: draft.plan.warnings }))
    if (pkg.location === 'frozen') events.push(createEvent('RULE_OVERRIDE', { kind: 'frozen-package-refreeze', packageId, lotId: draft.lot.id }, [packageId, draft.lot.id], { reason: 'Zuvor komplett eingefrorene Packung wurde portioniert und wieder eingefroren.' }))
    onSave(events)
  }
  return <Modal wide title={draft ? 'Aufteilung bestätigen' : 'Packung öffnen & aufteilen'} subtitle={draft ? `${draft.plan.fullPortions} volle Portionen · ${draft.plan.restGrams.toLocaleString('de-DE')} g rechnerischer Rest` : 'Der Vorschlag belegt noch keine Gläser.'} onClose={onClose}>
    {!unopened.length ? <Empty title="Keine verfügbare Packung" text="Lege zuerst eine Packung an." /> : !draft ? <form onSubmit={makePlan} className="form-grid"><label className="span-2">Packung<select value={packageId} onChange={e => updatePackage(e.target.value)}>{unopened.map(item => <option value={item.id} key={item.id}>{item.name} · {item.availableGrams} g</option>)}</select></label>{pkg && !pkg.openedAt && <label>Tatsächlich verfügbar<input type="number" step="0.1" value={actual} onChange={e => { setActual(Number(e.target.value)); setAmount(Number(e.target.value)) }} /></label>}<label>Teilmenge<input type="number" min="0.1" step="0.1" value={amount} onChange={e => setAmount(Number(e.target.value))} /></label><label>Zubereitung<select value={brew} onChange={e => updateBrew(e.target.value as BrewMethod)}><option value="espresso">Espresso</option><option value="filter">Filter</option></select></label><label>Mahlzustand<select value={bean} onChange={e => setBean(e.target.value as BeanState)}><option value="whole">Ganze Bohne</option><option value="ground">Gemahlen</option></select></label><label className="span-2">Kategorie<select value={categoryId} onChange={e => setCategoryId(e.target.value)}>{settings.categories.map(item => <option value={item.id} key={item.id}>{item.name}</option>)}</select></label>{category.ruleProfile === 'MANUAL' && <label className="span-2">Aufteilungsprofil<select value={manualProfile} onChange={e => setManualProfile(e.target.value as RuleProfile)}><option value="A">A · M und S</option><option value="B">B · L und M</option></select></label>}<label>Grundmenge je Portion<input type="number" step="0.1" min="0.1" value={base} onChange={e => setBase(Number(e.target.value))} /></label><label>Mahlverlust je Portion<input type="number" step="0.1" min="0" value={allowance} onChange={e => setAllowance(Number(e.target.value))} /></label>{category.ruleProfile === 'A' && <label className="check-row span-2"><input type="checkbox" checked={directS} onChange={e => setDirectS(e.target.checked)} /> Qualitätsalternative: vollständig direkt in S aufteilen</label>}{error && <p className="error-text span-2">{error}</p>}<div className="modal-actions span-2"><button type="button" className="secondary" onClick={onClose}>Abbrechen</button><button className="primary" type="submit">Vorschlag berechnen</button></div></form> : <div><div className="plan-summary"><div><span>Portionsziel</span><b>{draft.plan.portionGrams.toLocaleString('de-DE')} g</b></div><div><span>Gläser</span><b>{draft.plan.fills.length}</b></div><div><span>Nicht eingelagert</span><b>{draft.plan.unallocatedGrams.toLocaleString('de-DE')} g</b></div></div><div className="fill-list">{draft.plan.fills.map((fill, index) => <div className="fill-row" key={index}><select value={fill.jarId} onChange={e => setDraft(current => current ? { ...current, plan: { ...current.plan, fills: current.plan.fills.map((item, i) => i === index ? { ...item, jarId: e.target.value } : item) } } : current)}>{Object.values(state.jars).filter(jar => jar.size === fill.size && jar.status === 'free').map(jar => <option key={jar.id}>{jar.id}</option>)}</select><span>Soll {fill.suggestedGrams.toLocaleString('de-DE')} g</span><label>Ist <input type="number" step="0.1" min="0" value={fill.actualGrams} onChange={e => setDraft(current => current ? { ...current, plan: { ...current.plan, fills: current.plan.fills.map((item, i) => i === index ? { ...item, actualGrams: Number(e.target.value) } : item) } } : current)} /> g</label></div>)}</div>{draft.plan.warnings.length > 0 && <div className="notice warning"><b>Vor dem Einfrieren prüfen</b>{draft.plan.warnings.map(w => <span key={w}>{w}</span>)}<label className="check-row"><input type="checkbox" checked={warningConfirmed} onChange={e => setWarningConfirmed(e.target.checked)} /> Hinweise geprüft und bewusst bestätigt</label></div>}{error && <p className="error-text">{error}</p>}<div className="modal-actions"><button className="secondary" onClick={() => setDraft(undefined)}>Zurück</button><button className="primary" onClick={commit}>Gläser verbindlich buchen</button></div></div>}
  </Modal>
}

function JarDetail({ jar, state, settings, events, onClose, onAppend }: { jar: JarState; state: ReturnType<typeof reduceInventory>; settings: any; events: InventoryEvent[]; onClose: () => void; onAppend: (events: InventoryEvent[]) => void }) {
  const lot = jar.lotId ? state.lots[jar.lotId] : undefined
  const pkg = lot ? state.packages[lot.packageId] : undefined
  const [grams, setGrams] = useState(lot ? lot.baseGrams + lot.allowanceGrams : 0)
  const related = events.filter(event => event.resourceIds.includes(jar.id)).sort((a, b) => b.effectiveAt.localeCompare(a.effectiveAt))
  const clean = () => onAppend([createEvent('JAR_CLEANED', { jarId: jar.id }, [jar.id])])
  const open = () => onAppend([createEvent('JAR_OPENED', { jarId: jar.id, openedAt: new Date().toISOString() }, [jar.id, jar.lotId!])])
  const consume = (amount = grams) => { if (amount > 0 && amount <= jar.grams + 0.001) onAppend([createEvent('CONSUMED', { jarId: jar.id, grams: amount }, [jar.id, jar.lotId!])]) }
  return <div className="drawer-backdrop" onMouseDown={e => e.target === e.currentTarget && onClose()}><aside className="drawer"><button className="drawer-close" onClick={onClose}>×</button><div className={`drawer-jar ${jar.status}`}><span>{jar.id}</span></div><p className="eyebrow">{jar.size}-Glas · {statusLabel[jar.status]}</p><h2>{lot?.name || 'Leeres Glas'}</h2>{lot && <><p className="lead">{jar.grams.toLocaleString('de-DE')} g · {lot.brewMethod === 'espresso' ? 'Espresso' : 'Filter'} · {formatPortions(jar.grams, lot.baseGrams + lot.allowanceGrams)}</p><dl className="detail-list"><div><dt>Ursprung</dt><dd>{pkg?.name}</dd></div><div><dt>Eingefroren</dt><dd>{jar.filledAt ? new Date(jar.filledAt).toLocaleString('de-DE') : '—'}</dd></div><div><dt>Auftauen</dt><dd>{jar.thawCount}</dd></div><div><dt>Wiedereinfrieren</dt><dd>{jar.refreezeCount}</dd></div></dl></>}
    <div className="drawer-actions">{jar.status === 'frozen' && <button className="primary" onClick={open}>Glas öffnen</button>}{jar.status === 'frozen' && jar.size === 'S' && <button className="secondary" onClick={() => consume(jar.grams)}>Öffnen & vollständig verbrauchen</button>}{jar.status === 'active' && <><label>Verbrauch in Gramm<input type="number" min="0.1" step="0.1" max={jar.grams} value={grams} onChange={e => setGrams(Number(e.target.value))} /></label><button className="primary" onClick={() => consume()}>Verbrauch buchen</button><button className="secondary" onClick={() => consume(jar.grams)}>Rest vollständig verbrauchen</button></>}{jar.status === 'cleaning' && <button className="primary" onClick={clean}>Als gereinigt markieren</button>} {lot && (jar.size === 'L' || (jar.size === 'M' && lot.ruleProfile === 'A')) && ['frozen', 'active'].includes(jar.status) && <TransferAction jar={jar} lot={lot} state={state} onAppend={onAppend} />}</div>
    <div className="drawer-history"><h3>Glasverlauf</h3>{related.map(event => <div key={event.id}><time>{new Date(event.effectiveAt).toLocaleDateString('de-DE')}</time><span>{event.type}</span></div>)}</div></aside></div>
}

function TransferAction({ jar, lot, state, onAppend }: { jar: JarState; lot: CoffeeLot; state: ReturnType<typeof reduceInventory>; onAppend: (events: InventoryEvent[]) => void }) {
  const [draft, setDraft] = useState<Array<{ jarId: string; grams: number; frozen: boolean }>>()
  const [consumeFirst, setConsumeFirst] = useState(true)
  const [reason, setReason] = useState('')
  const portion = round(lot.baseGrams + lot.allowanceGrams)
  const create = () => {
    const targetSize: JarSize = jar.size === 'L' ? 'M' : 'S'
    const consumed = jar.size === 'M' && consumeFirst ? Math.min(portion, jar.grams) : 0
    let remaining = round(jar.grams - consumed)
    const cap = round(portionCapacity(targetSize, lot.brewMethod) * portion)
    const targets: Array<{ jarId: string; grams: number; frozen: boolean }> = []
    const used = new Set<string>()
    while (remaining > 0.001) {
      const free = Object.values(state.jars).filter(j => j.size === targetSize && j.status === 'free' && !used.has(j.id)).sort((a, b) => a.id.localeCompare(b.id))[0]
      if (!free) break
      const grams = Math.min(cap, remaining); used.add(free.id); targets.push({ jarId: free.id, grams, frozen: !(jar.size === 'L' && targets.length === 0) }); remaining = round(remaining - grams)
      if (jar.size === 'L' && targets.length === 3) break
    }
    setDraft(targets)
  }
  const commit = () => {
    if (!draft) return
    const consumed = jar.size === 'M' && consumeFirst ? Math.min(portion, jar.grams) : 0
    const moved = draft.reduce((sum, target) => sum + target.grams, 0)
    const remainder = round(jar.grams - consumed - moved)
    const warnings: string[] = []
    if (remainder > 0.001) warnings.push(`${remainder} g bleiben im aktiven Ausgangsglas, weil Zielgläser fehlen.`)
    if (jar.refreezeCount >= 1 && draft.some(target => target.frozen)) warnings.push('Diese Buchung erzeugt eine weitere Wiedereinfrierung.')
    if (warnings.length && !reason.trim()) return
    const actionEvents: InventoryEvent[] = []
    if (jar.status === 'frozen') actionEvents.push(createEvent('JAR_OPENED', { jarId: jar.id, openedAt: new Date().toISOString() }, [jar.id, lot.id]))
    actionEvents.push(createEvent('TRANSFER_COMMITTED', { sourceJarId: jar.id, targets: draft, consumedGrams: consumed }, [jar.id, lot.id, ...draft.map(t => t.jarId)], { reason: reason.trim() || undefined, warnings }))
    if (warnings.length) actionEvents.push(createEvent('RULE_OVERRIDE', { kind: 'transfer-exception', sourceJarId: jar.id }, [jar.id, lot.id], { reason: reason.trim(), warnings }))
    onAppend(actionEvents); setDraft(undefined)
  }
  return <div className="transfer-box"><h3>{jar.size === 'L' ? 'In M-Gläser umfüllen' : 'In S-Gläser umfüllen'}</h3>{jar.size === 'M' && <label className="check-row"><input type="checkbox" checked={consumeFirst} onChange={e => setConsumeFirst(e.target.checked)} /> Eine Portion sofort verbrauchen</label>}{!draft ? <button className="secondary" onClick={create}>Umfüllvorschlag</button> : <><div className="transfer-targets">{draft.map(target => <span key={target.jarId}>{target.jarId} · {target.grams.toLocaleString('de-DE')} g · {target.frozen ? 'tiefgekühlt' : 'aktiv'}</span>)}</div>{(round(jar.grams - (jar.size === 'M' && consumeFirst ? Math.min(portion, jar.grams) : 0) - draft.reduce((s, t) => s + t.grams, 0)) > 0.001 || (jar.refreezeCount >= 1 && draft.some(t => t.frozen))) && <label>Pflichtbegründung<input value={reason} onChange={e => setReason(e.target.value)} placeholder="Warum wird von der Regel abgewichen?" /></label>}<button className="primary" onClick={commit}>Umfüllung buchen</button></>}</div>
}

function RestoreDialog({ onClose, onRestore }: { onClose: () => void; onRestore: (doc: ReturnType<typeof validateDocument>) => void }) {
  const [doc, setDoc] = useState<ReturnType<typeof validateDocument>>()
  const [error, setError] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)
  const load = async (file?: File) => { if (!file) return; try { setDoc(validateDocument(JSON.parse(await file.text()))); setError('') } catch (e) { setError(e instanceof Error ? e.message : 'Datei ungültig') } }
  return <Modal title="JSON wiederherstellen" subtitle="Der aktuelle Stand wird vor dem Ersetzen gesichert." onClose={onClose}><div className="restore-box"><input ref={fileRef} type="file" accept="application/json,.json" onChange={e => void load(e.target.files?.[0])} />{doc && <div className="notice success"><b>Datei ist gültig</b><span>{doc.events.length} Ereignisse · erstellt am {new Date(doc.createdAt).toLocaleDateString('de-DE')}</span></div>}{error && <p className="error-text">{error}</p>}<div className="modal-actions"><button className="secondary" onClick={onClose}>Abbrechen</button><button className="primary" disabled={!doc} onClick={() => doc && window.confirm('Aktuellen lokalen Stand wirklich durch diese Datei ersetzen?') && onRestore(doc)}>Wiederherstellung vormerken</button></div></div></Modal>
}

function DateField({ label, value, precision, onValue, onPrecision }: { label: string; value: string; precision: FlexibleDate['precision']; onValue: (value: string) => void; onPrecision: (value: FlexibleDate['precision']) => void }) {
  return <fieldset className="date-field"><legend>{label}</legend><input type="date" value={value} disabled={precision === 'unknown'} onChange={e => onValue(e.target.value)} /><select value={precision} onChange={e => onPrecision(e.target.value as FlexibleDate['precision'])}><option value="exact">Exakt</option><option value="approximate">Ungefähr</option><option value="unknown">Unbekannt</option></select></fieldset>
}

function Modal({ title, subtitle, onClose, wide, children }: { title: string; subtitle: string; onClose: () => void; wide?: boolean; children: React.ReactNode }) {
  return <div className="modal-backdrop" onMouseDown={e => e.target === e.currentTarget && onClose()}><section className={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-labelledby="modal-title"><button className="modal-close" onClick={onClose}>×</button><div className="modal-head"><p className="eyebrow">Buchung vorbereiten</p><h2 id="modal-title">{title}</h2><p>{subtitle}</p></div>{children}</section></div>
}
