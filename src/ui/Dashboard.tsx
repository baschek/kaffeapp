import type { CoffeeLot, CoffeePackage, InventoryState, JarSize, JarState } from '../types'
import { Empty, MethodBadge, NavIcon, PageHeading, PanelHead, methodWord } from './common'
import { JarIcon, fmtGrams, type Status } from './JarTile'

type Props = { state: InventoryState; onAdd: () => void; onAllocate: () => void; onJar: (id: string) => void; onJars: () => void }

function Metric({ status, label, value, detail }: { status?: Status; label: string; value: number; detail: string }) {
  return <article className={`metric ${status ? `s-${status === 'cleaning' ? 'clean' : status}` : 'plain'}`}>
    <span className="metric-label">{status ? <JarIcon status={status} style={{ width: 14, height: 17 }} /> : <span className="metric-icon">{NavIcon.coffee}</span>}{label}</span>
    <strong>{value}</strong><small>{detail}</small>
  </article>
}

export function Dashboard({ state, onAdd, onAllocate, onJar, onJars }: Props) {
  const jars = Object.values(state.jars) as JarState[]
  const free = (size: JarSize) => jars.filter(j => j.size === size && j.status === 'free').length
  const cleaning = jars.filter(j => j.status === 'cleaning').length
  const frozen = jars.filter(j => j.status === 'frozen').length
  const activeJars = jars.filter(j => j.status === 'active')
  const packages = Object.values(state.packages) as CoffeePackage[]
  const unopened = packages.filter(p => !p.openedAt && !p.archived)
  return <section className="page dashboard">
    <PageHeading eyebrow="Bestand heute" title="Übersicht">
      <div className="actions"><button className="secondary" onClick={onAllocate}>Kaffee öffnen & aufteilen</button><button className="primary" onClick={onAdd}>Neue Packung</button></div>
    </PageHeading>
    <div className="metric-grid">
      <Metric status="frozen" label="Tiefgekühlt" value={frozen} detail={`${activeJars.length} aktiv aufgetaut`} />
      <Metric status="free" label="Frei" value={free('S') + free('M') + free('L')} detail={`${free('S')} S · ${free('M')} M · ${free('L')} L`} />
      <Metric label="Ungeöffnet" value={unopened.length} detail={`${unopened.filter(p => p.location === 'frozen').length} komplett eingefroren`} />
      <Metric status="cleaning" label="Zu reinigen" value={cleaning} detail={cleaning ? 'warten auf Reinigung' : 'Alle Gläser einsatzbereit'} />
    </div>
    <article className="panel active-panel">
      <PanelHead eyebrow="Gefrierfach" title="Aktive Gläser" action="Matrix öffnen →" onAction={onJars} />
      {activeJars.length === 0
        ? <Empty title="Kein Glas ist aktiv" text="Ein tiefgekühltes Glas öffnest du über das Glas-Detail." />
        : activeJars.map(jar => {
          const lot: CoffeeLot | undefined = jar.lotId ? state.lots[jar.lotId] : undefined
          return <button key={jar.id} className="crow" onClick={() => onJar(jar.id)}>
            {lot ? <MethodBadge method={lot.brewMethod} /> : <span className="method-badge" aria-hidden="true">?</span>}
            <span><b>{jar.id}{lot ? ` · ${lot.name}` : ''}</b><small>{lot ? `${methodWord(lot.brewMethod)} · ` : ''}aufgetaut</small></span>
            <span className="crow-end">{fmtGrams(jar.grams)}</span>
          </button>
        })}
    </article>
    {state.overrides.length > 0 && <div className="notice warning"><b>{state.overrides.length} dokumentierte Regelabweichung{state.overrides.length === 1 ? '' : 'en'}</b><span>Im Verlauf bleiben Begründung und betroffene Charge dauerhaft sichtbar.</span></div>}
  </section>
}
