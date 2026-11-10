import { useEffect, useMemo, useRef, useState } from 'react'
import { createEvent, makeId } from './domain/events'
import { effectiveSettings, portionCapacity } from './domain/defaults'
import { formatPortions, planAllocation } from './domain/planner'
import { reduceInventory, round } from './domain/reducer'
import { downloadCsvFiles, downloadJson } from './exporter'
import { validateDocument } from './storage/local'
import { InventoryRepository } from './storage/repository'
import { readMode, SessionExpiredError, TessRepository, writeMode, type Mode, type RejectedNotice, type TessEnvelope } from './storage/tess'
import { passkeysSupported } from './storage/webauthn'
import { AppearanceSettings } from './ui/Appearance'
import { CoffeeView } from './ui/Coffee'
import { EVENT_LABEL, Empty, NavIcon, PageHeading, type NavId } from './ui/common'
import { Dashboard } from './ui/Dashboard'
import { JarDetail } from './ui/JarDetail'
import { JarMatrix } from './ui/Jars'
import type {
  AllocationPlan, BeanState, BrewMethod, CoffeeLot, CoffeePackage, FlexibleDate, InventoryEvent,
  LocalEnvelope, RuleProfile,
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
const tessRepository = new TessRepository()
const initialMode = (): Mode | null => readMode() ?? (tessRepository.serverUrl ? 'tess' : null)

export default function App() {
  const [envelope, setEnvelope] = useState<LocalEnvelope | null>(null)
  const [view, setView] = useState<View>('dashboard')
  const [dialog, setDialog] = useState<'package' | 'allocate' | 'restore' | null>(null)
  const [allocatePackage, setAllocatePackage] = useState<string>()
  const [toast, setToast] = useState<{ text: string; undo?: InventoryEvent[] }>()
  const [selectedJar, setSelectedJar] = useState<string>()
  const [selectedLot, setSelectedLot] = useState<string>()
  const [query, setQuery] = useState('')
  const [message, setMessage] = useState('Lokaler Stand wird geladen …')
  const [syncing, setSyncing] = useState(false)
  const [conflictIds, setConflictIds] = useState<string[]>([])
  const [mode, setMode] = useState<Mode | null>(initialMode)
  const [signedIn, setSignedIn] = useState(tessRepository.signedIn)
  const [setupOpen, setSetupOpen] = useState(false)
  const tessMode = mode === 'tess'

  useEffect(() => {
    let cancelled = false
    setEnvelope(null)
    const loading = tessMode ? tessRepository.load() : repository.load()
    loading.then(value => {
      if (cancelled) return
      setEnvelope(value)
      setMessage(value.pendingEventIds.length ? 'Änderungen warten auf Synchronisierung' : 'Lokal gespeichert')
      if (tessMode && tessRepository.signedIn && navigator.onLine) void syncTess()
    })
    return () => { cancelled = true }
  }, [mode])

  useEffect(() => {
    if (!tessMode) return
    const onFocus = () => { if (tessRepository.signedIn && navigator.onLine) void syncTess() }
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [mode])
  useEffect(() => {
    if (!toast) return
    const timer = window.setTimeout(() => setToast(undefined), 7000)
    return () => window.clearTimeout(timer)
  }, [toast])
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

  const syncTess = async () => {
    setSyncing(true); setMessage('Synchronisiere mit TESS …')
    try {
      const result = await tessRepository.sync()
      setEnvelope(result.envelope)
      setMessage(result.rejected.length ? `Buchung abgelehnt: ${result.rejected.map(item => item.reason).join(' · ')}` : 'Mit TESS synchronisiert')
    } catch (error) {
      if (error instanceof SessionExpiredError) setSignedIn(false)
      setMessage(error instanceof Error ? error.message : 'Synchronisierung fehlgeschlagen')
    } finally { setSyncing(false) }
  }

  const loginTess = async () => {
    setSyncing(true); setMessage('Anmeldung mit Passkey …')
    try {
      await tessRepository.login(); setSignedIn(true)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Anmeldung fehlgeschlagen'); setSyncing(false); return
    }
    setSyncing(false); await syncTess()
  }

  const logoutTess = async () => { await tessRepository.logout(); setSignedIn(false); setMessage('Abgemeldet – Buchungen bleiben lokal, bis du dich wieder anmeldest.') }

  const switchMode = (next: Mode) => { writeMode(next); setConflictIds([]); setSetupOpen(false); setMode(next) }

  /** Bucht Ereignisse. Mit `label` erscheint danach eine Meldung mit „Rückgängig“ (bucht dieselben Ereignisse als Gegenbuchung zurück). */
  const append = async (events: InventoryEvent[], label?: string) => {
    if (!envelope) return
    if (label) setToast({ text: label, undo: events })
    if (tessMode) {
      const next = await tessRepository.append(events)
      setEnvelope(next); setMessage(`${next.pendingEventIds.length} Änderung${next.pendingEventIds.length === 1 ? '' : 'en'} wartet/warten auf TESS`)
      if (tessRepository.signedIn && navigator.onLine) void syncTess()
      return
    }
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
    if (state?.jars[id]) { setSelectedJar(id); setQuery('') }
    else setMessage(`Glas „${id}“ wurde nicht gefunden.`)
  }

  if (mode === null || setupOpen || (tessMode && !tessRepository.serverUrl)) {
    return <TessSetup serverUrl={tessRepository.serverUrl} canCancel={mode !== null && Boolean(tessRepository.serverUrl)} drivePending={mode === 'drive' ? envelope?.pendingEventIds.length || 0 : 0}
      onCancel={() => setSetupOpen(false)} onDrive={() => switchMode('drive')}
      onDone={() => {
        setSignedIn(tessRepository.signedIn)
        if (mode === 'tess') { setSetupOpen(false); if (tessRepository.signedIn) void syncTess() } else switchMode('tess')
      }} />
  }

  if (!envelope || !state || !settings) return <div className="loading">Kaffee-Bestand wird vorbereitet …</div>
  const rejected = tessMode ? (envelope as TessEnvelope).rejected || [] : []
  const lastSync = tessMode && (envelope as TessEnvelope).lastSyncAt ? new Date((envelope as TessEnvelope).lastSyncAt!).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' }) : null

  const nav: Array<[View, string]> = [
    ['dashboard', 'Übersicht'], ['jars', 'Gläser'], ['coffee', 'Kaffees'], ['history', 'Verlauf'], ['settings', 'Einstellungen'],
  ]
  const navIcon = (id: View) => NavIcon[(id === 'dashboard' ? 'dashboard' : id) as NavId]
  const synced = tessMode ? signedIn : repository.drive.connected
  const syncButton = tessMode
    ? <button className="sync-button" onClick={signedIn ? () => syncTess() : loginTess} disabled={syncing}>
      {syncing ? 'Bitte warten …' : signedIn ? 'Jetzt synchronisieren' : 'Mit Passkey anmelden'}
    </button>
    : <button className="sync-button" onClick={repository.drive.connected ? () => sync() : connect} disabled={syncing}>
      {syncing ? 'Bitte warten …' : repository.drive.connected ? 'Jetzt synchronisieren' : 'Mit Google Drive verbinden'}
    </button>
  const go = (next: View) => { setView(next); setSelectedJar(undefined) }
  const undo = (events: InventoryEvent[]) => {
    setToast(undefined)
    void append([...events].reverse().map(event => createEvent('CORRECTION', { targetEventId: event.id }, event.resourceIds, { reason: 'Rückgängig', parentEventIds: [event.id] })))
  }

  return <div className="app-shell">
    <aside className="sidebar">
      <div className="brand"><span className="brand-mark">K</span><div><strong>Kaffee-Bestand</strong><small>Gefrierfach-Inventar</small></div></div>
      <nav aria-label="Hauptnavigation">{nav.map(([id, label]) => <button key={id} className={view === id ? 'active' : ''} aria-current={view === id ? 'page' : undefined} onClick={() => go(id)}>{navIcon(id)}{label}</button>)}</nav>
      <div className="side-status">
        <span className={`sync-dot ${synced ? 'online' : ''}`} />
        <div><strong>{tessMode ? (signedIn ? 'TESS verbunden' : 'TESS · nicht angemeldet') : repository.drive.connected ? 'Notfall: Drive verbunden' : 'Notfall: nur lokal'}</strong><small>{envelope.pendingEventIds.length} ausstehend{lastSync ? ` · ${lastSync}` : ''}</small></div>
      </div>
    </aside>

    <main>
      <header className="topbar">
        <div className="mobile-top">
          <span className="mobile-brand">Kaffee-Bestand</span>
          <span className="mobile-sync"><i className={`sync-dot ${synced ? 'online' : ''}`} />{synced ? 'Verbunden' : 'Lokal'}</span>
          <button className="gear" onClick={() => go('settings')} aria-label="Einstellungen">{NavIcon.settings}</button>
        </div>
        <form className="jar-search" onSubmit={event => { event.preventDefault(); openSearch() }}>
          <input value={query} onChange={e => setQuery(e.target.value)} placeholder="Glas suchen, z. B. S15" aria-label="Glasnummer suchen" autoComplete="off" />
          <button type="submit">Suchen</button>
        </form>
      </header>

      <div className="status-strip" role="status"><span className="status-text">{message}</span>{!navigator.onLine && <b>Offline</b>}{!tessMode && <b>Notfallmodus Google Drive</b>}{conflictIds.length > 0 && <button className="link" onClick={() => go('history')}>Konflikte ansehen</button>}{rejected.length > 0 && <button className="link" onClick={() => go('settings')}>Abgelehnte Buchungen ansehen</button>}{syncButton}</div>

      {view === 'dashboard' && <Dashboard state={state} onAdd={() => setDialog('package')} onAllocate={() => { setAllocatePackage(undefined); setDialog('allocate') }} onJar={setSelectedJar} onJars={() => go('jars')} />}
      {view === 'jars' && <JarMatrix state={state} selected={selectedJar} onSelect={setSelectedJar} />}
      {view === 'coffee' && <CoffeeView state={state} categories={settings.categories} selected={selectedLot} onSelect={setSelectedLot} onJar={setSelectedJar} onAllocate={packageId => { setAllocatePackage(packageId); setDialog('allocate') }} />}
      {view === 'history' && <HistoryView events={envelope.document.events} corrected={state.correctedEventIds} conflicts={conflictIds} onCorrect={event => {
        const reason = window.prompt('Warum ist diese Buchung fehlerhaft?')?.trim()
        if (reason) void append([createEvent('CORRECTION', { targetEventId: event.id }, event.resourceIds, { reason, parentEventIds: [event.id] })])
      }} />}
      {view === 'settings' && <section className="page">
        <PageHeading eyebrow="Darstellung" title="Einstellungen" lead="Farben, Glas-Stil und Helligkeit. Die Auswahl gilt sofort überall." />
        <AppearanceSettings />
        <PageHeading small eyebrow="Server & Sicherung" title="Verbindung und Daten" />
        <ConnectionPanel mode={mode} serverUrl={tessRepository.serverUrl} signedIn={signedIn} lastSync={lastSync} pending={envelope.pendingEventIds.length} rejected={rejected}
          onLogin={loginTess} onLogout={logoutTess} onAddPasskey={() => setSetupOpen(true)} onDismiss={async () => setEnvelope(await tessRepository.dismissRejected())}
          onSwitch={(next: Mode) => {
            if (next === 'drive' && !window.confirm('Notfallmodus: Buchungen gehen dann in die Google-Drive-Datei statt zu TESS. Der Pi übernimmt sie bei der nächsten nächtlichen Sicherung. Nur nutzen, wenn der Pi nicht erreichbar ist. Wechseln?')) return
            switchMode(next)
          }} />
        <DataView envelope={envelope} settings={settings} state={state} connected={repository.drive.connected} tessMode={tessMode} onBackup={async () => {
          try { await repository.manualBackup(envelope); setMessage('Manuelle Sicherung in Google Drive erstellt') } catch (error) { setMessage(error instanceof Error ? error.message : 'Sicherung fehlgeschlagen') }
        }} onRestore={() => setDialog('restore')} onAppend={append} />
      </section>}
    </main>

    <nav className="mobile-nav" aria-label="Hauptnavigation">{nav.slice(0, 4).map(([id, label]) => <button key={id} className={view === id ? 'active' : ''} aria-current={view === id ? 'page' : undefined} onClick={() => go(id)}>{navIcon(id)}{label}</button>)}</nav>
    {dialog === 'package' && <AddPackageDialog onClose={() => setDialog(null)} onSave={events => { void append(events); setDialog(null) }} />}
    {dialog === 'allocate' && <AllocateDialog state={state} settings={settings} initialPackageId={allocatePackage} onClose={() => setDialog(null)} onSave={events => { void append(events); setDialog(null); go('jars') }} />}
    {dialog === 'restore' && <RestoreDialog onClose={() => setDialog(null)} onRestore={async doc => { const next = await repository.replace(envelope, doc); setEnvelope(next); setDialog(null); setMessage('Wiederherstellung vorgemerkt – bitte mit Drive synchronisieren') }} />}
    {selectedJar && state.jars[selectedJar] && <JarDetail key={selectedJar} jar={state.jars[selectedJar]} state={state} events={envelope.document.events} onClose={() => setSelectedJar(undefined)} onAppend={(events, label) => { void append(events, label) }} />}
    {toast && <div className="toast" role="status"><span>{toast.text}</span>{toast.undo && <button onClick={() => undo(toast.undo!)}>Rückgängig</button>}</div>}
  </div>
}

function HistoryView({ events, corrected, conflicts, onCorrect }: { events: InventoryEvent[]; corrected: Set<string>; conflicts: string[]; onCorrect: (event: InventoryEvent) => void }) {
  const labels = EVENT_LABEL
  return <section className="page"><PageHeading eyebrow="Unveränderliches Journal" title="Verlauf" lead="Fehler verschwinden nicht: Eine Gegenbuchung hebt ihre Bestandswirkung nachvollziehbar auf." /><article className="panel timeline">{[...events].sort((a, b) => b.effectiveAt.localeCompare(a.effectiveAt)).map(event => <div className={`timeline-row ${corrected.has(event.id) ? 'corrected' : ''} ${conflicts.includes(event.id) ? 'conflict' : ''}`} key={event.id}><div className="timeline-mark" /><div><div className="timeline-title"><strong>{labels[event.type] || event.type}</strong><time>{new Date(event.effectiveAt).toLocaleString('de-DE')}</time></div><p>{event.resourceIds.join(' · ') || 'Bestand'}{event.reason ? ` — ${event.reason}` : ''}</p>{event.warnings?.map(w => <span className="event-warning" key={w}>{w}</span>)}<small>{event.id}</small></div>{event.type !== 'CORRECTION' && !corrected.has(event.id) && <button className="icon-button" onClick={() => onCorrect(event)}>Fehler buchen</button>}</div>)}{events.length === 0 && <Empty title="Noch keine Ereignisse" text="Die erste erfasste Packung beginnt dein Journal." />}</article></section>
}

function DataView({ envelope, settings, state, connected, tessMode, onBackup, onRestore, onAppend }: any) {
  const [name, setName] = useState('')
  const [profile, setProfile] = useState<RuleProfile>('A')
  return <div className="settings-section"><p className="lead">{tessMode ? 'TESS auf dem Pi ist die zentrale Quelle und sichert jede Nacht nach Google Drive; JSON und CSV kannst du jederzeit zusätzlich herunterladen.' : 'Notfallmodus: Die Drive-Datei ist die Quelle, bis TESS wieder erreichbar ist; JSON und CSV kannst du jederzeit zusätzlich herunterladen.'}</p><div className="settings-grid">
    <article className="panel settings-card"><h2>Export & Wiederherstellung</h2><p>Vollständiges Ereignisjournal: <b>{envelope.document.events.length} Buchungen</b></p><div className="button-stack"><button className="primary" onClick={() => downloadJson(envelope.document)}>Vollständiges JSON laden</button><button className="secondary" onClick={() => downloadCsvFiles(envelope.document)}>Drei CSV-Dateien laden</button>{tessMode ? <p className="muted">Wiederherstellen geht im TESS-Modus nur auf dem Pi (./tessctl kaffee-import), weil das Journal dort nie überschrieben wird.</p> : <><button className="secondary" onClick={onRestore}>JSON wiederherstellen</button><button className="secondary" disabled={!connected} onClick={onBackup}>Manuelle Drive-Sicherung</button></>}</div></article>
    <article className="panel settings-card"><h2>Eigene Kategorie</h2><p>Eigene Bezeichnungen nutzen weiterhin ein klar festgelegtes Regelprofil.</p><form onSubmit={event => { event.preventDefault(); if (!name.trim()) return; const id = makeId('category'); void onAppend([createEvent('CATEGORY_DEFINED', { category: { id, name: name.trim(), ruleProfile: profile, builtIn: false } }, [id])]); setName('') }}><label>Name<input value={name} onChange={e => setName(e.target.value)} placeholder="z. B. Wettbewerb" /></label><label>Regelprofil<select value={profile} onChange={e => setProfile(e.target.value as RuleProfile)}><option value="A">A · M und S</option><option value="B">B · L und M</option><option value="DIRECT_S">Direkt in S</option><option value="MANUAL">Manuell</option></select></label><button className="primary" type="submit">Kategorie anlegen</button></form><div className="category-tags">{settings.categories.map((cat: any) => <span key={cat.id}>{cat.name} · {cat.ruleProfile}</span>)}</div></article>
    <article className="panel settings-card"><h2>Datenprüfung</h2><dl><div><dt>Gläser</dt><dd>{Object.keys(state.jars).length}</dd></div><div><dt>Packungen</dt><dd>{Object.keys(state.packages).length}</dd></div><div><dt>Teilchargen</dt><dd>{Object.keys(state.lots).length}</dd></div><div><dt>Ableitungswarnungen</dt><dd>{state.warnings.length}</dd></div></dl>{state.warnings.map((warning: string) => <p className="error-text" key={warning}>{warning}</p>)}</article>
  </div></div>
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

function AllocateDialog({ state, settings, initialPackageId, onClose, onSave }: { state: ReturnType<typeof reduceInventory>; settings: ReturnType<typeof effectiveSettings>; initialPackageId?: string; onClose: () => void; onSave: (events: InventoryEvent[]) => void }) {
  const unopened = Object.values(state.packages).filter(pkg => !pkg.archived && pkg.availableGrams > 0)
  const [packageId, setPackageId] = useState(unopened.find(item => item.id === initialPackageId)?.id || unopened[0]?.id || '')
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
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return <div className="modal-backdrop" onMouseDown={e => e.target === e.currentTarget && onClose()}><section className={`modal ${wide ? 'wide' : ''}`} role="dialog" aria-modal="true" aria-labelledby="modal-title"><button className="modal-close" onClick={onClose} aria-label="Schließen">×</button><div className="modal-head"><p className="eyebrow">Buchung vorbereiten</p><h2 id="modal-title">{title}</h2><p>{subtitle}</p></div>{children}</section></div>
}

function TessSetup({ serverUrl, canCancel, drivePending, onCancel, onDrive, onDone }: { serverUrl: string; canCancel: boolean; drivePending: number; onCancel: () => void; onDrive: () => void; onDone: () => void }) {
  const [url, setUrl] = useState(serverUrl)
  const [code, setCode] = useState('')
  const [label, setLabel] = useState(navigator.userAgent.includes('Mobile') ? 'Handy' : 'Laptop')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const supported = passkeysSupported()
  const run = async (action: () => Promise<unknown>) => {
    setBusy(true); setError('')
    try { tessRepository.setServer(url); await action(); onDone() } catch (e) { setError(e instanceof Error ? e.message : 'Unbekannter Fehler') } finally { setBusy(false) }
  }
  return <div className="setup-shell"><section className="panel setup-card">
    <div className="brand setup-brand"><span className="brand-mark">K</span><div><strong>Kaffee-Bestand</strong><small>mit TESS verbinden</small></div></div>
    <h1>Mit TESS verbinden</h1>
    <p className="lead">Dein Bestand liegt auf dem Pi. Diese Website meldet sich mit einem Passkey an (Fingerabdruck oder Gesichtserkennung); ein neues Gerät braucht einmalig einen Code vom Pi.</p>
    {!supported && <p className="error-text">Dieser Browser unterstützt keine Passkeys.</p>}
    <form className="setup-form" onSubmit={event => { event.preventDefault(); void run(() => tessRepository.registerPasskey(code, label).then(() => tessRepository.login())) }}>
      <label>Server-Adresse<input value={url} onChange={e => setUrl(e.target.value)} placeholder="https://tess.<name>.ts.net" inputMode="url" autoComplete="url" required /></label>
      <label>Einmal-Code<input value={code} onChange={e => setCode(e.target.value)} placeholder="aus ./tessctl kaffee-passkey-code" autoComplete="one-time-code" /></label>
      <label>Name des Passkeys<input value={label} onChange={e => setLabel(e.target.value)} maxLength={40} /></label>
      <div className="button-stack">
        <button className="primary" type="submit" disabled={busy || !supported || !code.trim()}>Passkey anlegen und anmelden</button>
        <button className="secondary" type="button" disabled={busy || !supported || !url.trim()} onClick={() => void run(() => tessRepository.login())}>Ich habe schon einen Passkey: anmelden</button>
        {canCancel && <button className="text-button" type="button" onClick={onCancel}>Abbrechen</button>}
      </div>
    </form>
    {error && <p className="error-text">{error}</p>}
    <div className="notice warning"><b>Notfall: Google Drive (wenn der Pi nicht erreichbar ist)</b><span>Bucht in die Drive-Datei wie die alte App. Der Pi übernimmt diese Buchungen bei der nächsten nächtlichen Sicherung; danach wieder hierher zurückwechseln.</span>{drivePending > 0 && <span>Im Notfallmodus warten noch {drivePending} Buchungen auf Drive – dort zuerst synchronisieren.</span>}<button className="secondary" type="button" onClick={onDrive}>Notfallmodus öffnen</button></div>
  </section></div>
}

function ConnectionPanel({ mode, serverUrl, signedIn, lastSync, pending, rejected, onLogin, onLogout, onAddPasskey, onDismiss, onSwitch }: {
  mode: Mode | null; serverUrl: string; signedIn: boolean; lastSync: string | null; pending: number; rejected: RejectedNotice[]
  onLogin: () => void; onLogout: () => void; onAddPasskey: () => void; onDismiss: () => void; onSwitch: (mode: Mode) => void
}) {
  const labels: Record<string, string> = { CONSUMED: 'Verbrauch', JAR_OPENED: 'Glas geöffnet', JAR_CLEANED: 'Glas gereinigt', TRANSFER_COMMITTED: 'Umfüllen', ALLOCATION_COMMITTED: 'Gläser befüllt', PACKAGE_CREATED: 'Packung angelegt', CORRECTION: 'Gegenbuchung' }
  return <div className="settings-section"><article className="panel settings-card connection-card">
    <h2>Verbindung</h2>
    {mode === 'tess' ? <>
      <dl><div><dt>Quelle</dt><dd>TESS (Pi)</dd></div><div><dt>Server</dt><dd>{serverUrl}</dd></div><div><dt>Anmeldung</dt><dd>{signedIn ? 'mit Passkey angemeldet' : 'nicht angemeldet'}</dd></div><div><dt>Ausstehend</dt><dd>{pending}</dd></div><div><dt>Zuletzt synchronisiert</dt><dd>{lastSync || 'noch nie'}</dd></div></dl>
      <div className="button-stack">{signedIn ? <button className="secondary" onClick={onLogout}>Abmelden</button> : <button className="primary" onClick={onLogin}>Mit Passkey anmelden</button>}<button className="secondary" onClick={onAddPasskey}>Passkey hinzufügen oder Server ändern</button><button className="secondary" onClick={() => onSwitch('drive')}>Notfall: Google Drive (wenn der Pi nicht erreichbar ist)</button></div>
      {rejected.length > 0 && <div className="notice warning"><b>Abgelehnte Buchungen</b>{rejected.map(item => <span key={item.eventId}>Buchung abgelehnt ({labels[item.type] || item.type}, {new Date(item.at).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' })}): {item.reason}</span>)}<button className="text-button" onClick={onDismiss}>Hinweise ausblenden</button></div>}
    </> : <>
      <div className="notice warning"><b>Notfallmodus Google Drive</b><span>Buchungen landen in der Drive-Datei. Der Pi übernimmt sie bei der nächsten nächtlichen Sicherung; nur nutzen, solange TESS nicht erreichbar ist.</span></div>
      <div className="button-stack"><button className="primary" onClick={() => serverUrl ? onSwitch('tess') : onAddPasskey()}>Zurück zu TESS</button></div>
    </>}
  </article></div>
}
