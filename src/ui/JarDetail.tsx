import { useEffect, useState } from 'react'
import { createEvent } from '../domain/events'
import { formatPortions } from '../domain/planner'
import { round } from '../domain/reducer'
import { maxKeepPortions, transferDraft, type TransferDraft } from '../domain/transfer'
import type { CoffeeLot, InventoryEvent, InventoryState, JarSize, JarState } from '../types'
import { EVENT_LABEL, methodWord } from './common'
import { StatusBadge, STATUS_WORD, fmtGrams } from './JarTile'

type Append = (events: InventoryEvent[], label?: string) => void
const SIZE_WORD: Record<JarSize, string> = { S: 'Klein', M: 'Mittel', L: 'Groß' }
const clampStep = (value: number, max: number) => Math.max(0.1, Math.min(max, round(value)))

export function JarDetail({ jar, state, events, onClose, onAppend }: { jar: JarState; state: InventoryState; events: InventoryEvent[]; onClose: () => void; onAppend: Append }) {
  const lot = jar.lotId ? state.lots[jar.lotId] : undefined
  const [grams, setGrams] = useState(() => lot ? Math.min(round(lot.baseGrams + lot.allowanceGrams), jar.grams || 0.1) : 0)
  const [transfer, setTransfer] = useState(false)
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  const related = events.filter(event => event.resourceIds.includes(jar.id)).sort((a, b) => b.effectiveAt.localeCompare(a.effectiveAt))
  const book = (list: InventoryEvent[], label: string) => { onAppend(list, label); onClose() }
  const clean = () => book([createEvent('JAR_CLEANED', { jarId: jar.id }, [jar.id])], `${jar.id} gereinigt, wieder frei`)
  const open = () => book([createEvent('JAR_OPENED', { jarId: jar.id, openedAt: new Date().toISOString() }, [jar.id, jar.lotId!])], `${jar.id} geöffnet, jetzt aktiv`)
  const consume = (amount: number, label?: string) => {
    if (!(amount > 0 && amount <= jar.grams + 0.001)) return
    const empty = jar.grams - amount <= 0.05
    book([createEvent('CONSUMED', { jarId: jar.id, grams: amount }, [jar.id, jar.lotId!])],
      label || `${jar.id}: ${fmtGrams(amount)} gebucht${empty ? '. Glas leer, zu reinigen' : ''}`)
  }
  const canTransfer = Boolean(lot && (jar.size === 'L' || (jar.size === 'M' && lot.ruleProfile === 'A')) && ['frozen', 'active'].includes(jar.status))
  const canBook = grams > 0 && grams <= jar.grams + 0.001
  return <div className="sheet-backdrop" onMouseDown={e => e.target === e.currentTarget && onClose()}>
    <aside className="sheet" role="dialog" aria-modal="true" aria-labelledby="jar-title">
      <div className="sheet-head">
        <div><p className="eyebrow">{jar.size}-Glas · {STATUS_WORD[jar.status]}</p><h2 id="jar-title">{lot ? `${jar.id} · ${lot.name}` : jar.id}</h2></div>
        <button className="close-button" onClick={onClose} aria-label="Schließen">×</button>
      </div>
      <StatusBadge status={jar.status} />
      {lot && <>
        <p className="lead small">{fmtGrams(jar.grams)} · {methodWord(lot.brewMethod)} · {formatPortions(jar.grams, lot.baseGrams + lot.allowanceGrams)}</p>
        <dl className="facts">
          <div><dt>Kaffee</dt><dd>{lot.name}</dd></div>
          <div><dt>Inhalt</dt><dd>{fmtGrams(jar.grams)}</dd></div>
          <div><dt>Zubereitung</dt><dd>{methodWord(lot.brewMethod)}</dd></div>
          <div><dt>Größe</dt><dd>{SIZE_WORD[jar.size]}</dd></div>
          <div><dt>Auftauen</dt><dd>{jar.thawCount}×</dd></div>
          <div><dt>Wiedereinfrieren</dt><dd>{jar.refreezeCount}×</dd></div>
          <div className="wide"><dt>Eingefroren</dt><dd>{jar.filledAt ? new Date(jar.filledAt).toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' }) : '—'}</dd></div>
        </dl></>}
      <div className="sheet-actions">
        {jar.status === 'frozen' && <button className="primary" onClick={open}>Glas öffnen</button>}
        {jar.status === 'frozen' && jar.size === 'S' && <button className="secondary" onClick={() => consume(jar.grams, `${jar.id} geöffnet und verbraucht, zu reinigen`)}>Öffnen & vollständig verbrauchen</button>}
        {jar.status === 'active' && <>
          <p className="hint" id="amount-label">Verbrauch in Gramm</p>
          <div className="stepper" role="group" aria-labelledby="amount-label">
            <button className="secondary" aria-label="0,1 g weniger" onClick={() => setGrams(clampStep(grams - 0.1, jar.grams))}>−</button>
            <label className="amount"><input type="number" inputMode="decimal" min="0.1" step="0.1" max={jar.grams} value={grams} onChange={e => setGrams(Number(e.target.value))} aria-label="Verbrauch in Gramm" /><span>g</span></label>
            <button className="secondary" aria-label="0,1 g mehr" onClick={() => setGrams(clampStep(grams + 0.1, jar.grams))}>+</button>
          </div>
          <button className="accent" disabled={!canBook} onClick={() => consume(grams)}>Verbrauch buchen</button>
          <button className="secondary" onClick={() => consume(jar.grams, `${jar.id}: Rest verbraucht, zu reinigen`)}>Rest vollständig verbrauchen</button>
        </>}
        {jar.status === 'cleaning' && <button className="primary" onClick={clean}>Als gereinigt markieren</button>}
        {jar.status === 'free' && <p className="hint">Dieses Glas ist frei. Es wird beim Aufteilen einer Packung befüllt.</p>}
        {canTransfer && !transfer && <button className="secondary" onClick={() => setTransfer(true)}>Umfüllen</button>}
        {canTransfer && transfer && lot && <TransferAction jar={jar} lot={lot} state={state} onAppend={(list, label) => book(list, label || '')} />}
      </div>
      {related.length > 0 && <div className="history"><h3>Glasverlauf</h3>{related.map(event => <div key={event.id}><time>{new Date(event.effectiveAt).toLocaleDateString('de-DE')}</time><span>{EVENT_LABEL[event.type] || event.type}</span></div>)}</div>}
    </aside>
  </div>
}

function TransferAction({ jar, lot, state, onAppend }: { jar: JarState; lot: CoffeeLot; state: InventoryState; onAppend: Append }) {
  const [draft, setDraft] = useState<TransferDraft>()
  const [consumeFirst, setConsumeFirst] = useState(true)
  const [keep, setKeep] = useState(0)
  const [reason, setReason] = useState('')
  const portion = round(lot.baseGrams + lot.allowanceGrams)
  const maxKeep = maxKeepPortions(state, jar.id, consumeFirst)
  const keepNow = Math.min(keep, maxKeep)
  const changeKeep = (value: number) => { setKeep(Math.max(0, Math.min(maxKeep, value))); setDraft(undefined) }
  const create = () => setDraft(transferDraft(state, jar.id, consumeFirst, keepNow))
  const commit = () => {
    if (!draft || !draft.targets.length) return
    const { targets, consumed, kept, warnings } = draft
    if (warnings.length && !reason.trim()) return
    const actionEvents: InventoryEvent[] = []
    if (jar.status === 'frozen') actionEvents.push(createEvent('JAR_OPENED', { jarId: jar.id, openedAt: new Date().toISOString() }, [jar.id, lot.id]))
    actionEvents.push(createEvent('TRANSFER_COMMITTED', { sourceJarId: jar.id, targets, consumedGrams: consumed }, [jar.id, lot.id, ...targets.map(t => t.jarId)], { reason: reason.trim() || undefined, warnings }))
    if (warnings.length) actionEvents.push(createEvent('RULE_OVERRIDE', { kind: 'transfer-exception', sourceJarId: jar.id }, [jar.id, lot.id], { reason: reason.trim(), warnings }))
    onAppend(actionEvents, kept > 0 ? `${jar.id} teilweise umgefüllt, ${fmtGrams(kept)} bleiben im Glas` : `${jar.id} umgefüllt`); setDraft(undefined)
  }
  const needsReason = Boolean(draft?.warnings.length)
  return <div className="transfer-box"><h3>{jar.size === 'L' ? 'In M-Gläser umfüllen' : 'In S-Gläser umfüllen'}</h3>
    {jar.size === 'M' && <label className="check-row"><input type="checkbox" checked={consumeFirst} onChange={e => { setConsumeFirst(e.target.checked); setDraft(undefined) }} /> Eine Portion sofort verbrauchen</label>}
    {maxKeep > 0 && <>
      <p className="hint" id="keep-label">Im Glas behalten (Portionen à {fmtGrams(portion)})</p>
      <div className="stepper" role="group" aria-labelledby="keep-label">
        <button className="secondary" aria-label="Eine Portion weniger behalten" disabled={keepNow <= 0} onClick={() => changeKeep(keepNow - 1)}>−</button>
        <span className="amount keep-count" aria-live="polite">{keepNow === 0 ? 'keine, alles umfüllen' : `${keepNow} ${keepNow === 1 ? 'Portion' : 'Portionen'}`}</span>
        <button className="secondary" aria-label="Eine Portion mehr behalten" disabled={keepNow >= maxKeep} onClick={() => changeKeep(keepNow + 1)}>+</button>
      </div></>}
    {!draft ? <button className="secondary" onClick={create}>Umfüllvorschlag</button> : <>
      <div className="transfer-targets">
        {draft.targets.map(target => <span key={target.jarId}>{target.jarId} · {target.grams.toLocaleString('de-DE')} g · {target.frozen ? 'tiefgekühlt' : 'aktiv'}</span>)}
        {draft.kept > 0 && <span>{jar.id} · {draft.kept.toLocaleString('de-DE')} g bleiben im Glas · aktiv</span>}
        {!draft.targets.length && <span>Keine freien {jar.size === 'L' ? 'M' : 'S'}-Gläser.</span>}
        {draft.warnings.map(warning => <span key={warning} className="warning-text">{warning}</span>)}
      </div>
      {needsReason && <label>Pflichtbegründung<input value={reason} onChange={e => setReason(e.target.value)} placeholder="Warum wird von der Regel abgewichen?" /></label>}
      <button className="primary" disabled={!draft.targets.length || (needsReason && !reason.trim())} onClick={commit}>Umfüllung buchen</button></>}
  </div>
}
