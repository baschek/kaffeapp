import type { CoffeeLot, CoffeePackage, FlexibleDate, InventoryState, JarSize, JarState } from '../types'
import { formatPortions } from '../domain/planner'
import { Empty, MethodBadge, PageHeading, methodWord, useMediaQuery } from './common'
import { JarTile } from './JarTile'
import { useLook } from './theme'

type CategoryList = Array<{ id: string; name: string }>
const categoryName = (categories: CategoryList, id: string) => categories.find(c => c.id === id)?.name || 'Eigene Kategorie'
const num = (n: number) => n.toLocaleString('de-DE')
const portionOf = (lot: CoffeeLot) => lot.baseGrams + lot.allowanceGrams
const lotJars = (state: InventoryState, lot: CoffeeLot) => (Object.values(state.jars) as JarState[]).filter(j => j.lotId === lot.id)
const lotGrams = (state: InventoryState, lot: CoffeeLot) => lotJars(state, lot).reduce((sum, jar) => sum + jar.grams, 0) + lot.unallocatedGrams
const dateText = (date: FlexibleDate) => date.precision === 'unknown' || !date.value ? null : `${date.precision === 'approximate' ? 'ca. ' : ''}${new Date(date.value).toLocaleDateString('de-DE')}`
const cls = (status: JarState['status']) => status === 'cleaning' ? 'clean' : status === 'planned' ? 'free' : status

function LotCard({ lot, state, categories, open, onToggle }: { lot: CoffeeLot; state: InventoryState; categories: CategoryList; open: boolean; onToggle: () => void }) {
  const jars = lotJars(state, lot)
  return <button className="lot-card" aria-expanded={open} onClick={onToggle}>
    <span className="lot-row"><MethodBadge method={lot.brewMethod} />
      <span><b>{lot.name}</b><small>{methodWord(lot.brewMethod)} · {categoryName(categories, lot.categoryId)}</small></span>
      <span className="lot-portions">{formatPortions(lotGrams(state, lot), portionOf(lot))}</span></span>
    <span className="mini-strip" aria-hidden="true">{jars.slice(0, 48).map(j => <i key={j.id} className={`mini-jar s-${cls(j.status)}`} />)}</span>
  </button>
}

function LotTree({ lot, state, categories, onJar }: { lot: CoffeeLot; state: InventoryState; categories: CategoryList; onJar: (id: string) => void }) {
  const { tileStyle } = useLook()
  const pkg: CoffeePackage | undefined = state.packages[lot.packageId]
  const jars = lotJars(state, lot)
  const dates = pkg ? [dateText(pkg.roastDate) && `Röstdatum ${dateText(pkg.roastDate)}`, dateText(pkg.purchaseDate) && `gekauft ${dateText(pkg.purchaseDate)}`].filter(Boolean) : []
  return <div className="tree">
    <div className="tree-node"><small>Packung</small><b>{pkg?.name || '—'}</b><span>{[`${num(pkg?.nominalGrams || 0)} g Nenngewicht`, ...dates].join(' · ')}</span></div>
    <div className="tree-line" />
    <div className="tree-node"><small>Teilcharge</small><b>{num(lot.initialGrams)} g {methodWord(lot.brewMethod)}</b><span>{lot.beanState === 'whole' ? 'Ganze Bohne' : 'Gemahlen'} · {categoryName(categories, lot.categoryId)} · {num(portionOf(lot))} g je Portion</span></div>
    <div className="tree-line" />
    <div className="tree-jars"><small>Gläser</small>
      {(['S', 'M', 'L'] as JarSize[]).map(size => {
        const list = jars.filter(j => j.size === size)
        return list.length ? <div key={size}><div className="tree-size">{size} · {list.length} {list.length === 1 ? 'Glas' : 'Gläser'}</div>
          <div className="tile-grid compact">{list.map(jar => <JarTile key={jar.id} jar={jar} state={state} tileStyle={tileStyle} onClick={() => onJar(jar.id)} />)}</div></div> : null
      })}
      {jars.length === 0 && <p className="muted">Noch keine Gläser.</p>}
      {lot.unallocatedGrams > 0 && <div className="tree-node remainder"><small>Nicht eingelagert</small><b>Restmenge</b><span>{num(lot.unallocatedGrams)} g</span></div>}
    </div>
    <div className="lot-facts"><div><span>Auftauvorgänge</span><b>{Math.max(0, ...jars.map(j => j.thawCount))}</b></div><div><span>Wiedereinfrierungen</span><b>{Math.max(0, ...jars.map(j => j.refreezeCount))}</b></div><div><span>Regelprofil</span><b>{lot.ruleProfile}</b></div></div>
  </div>
}

export function CoffeeView({ state, categories, selected, onSelect, onAllocate, onJar }: {
  state: InventoryState; categories: CategoryList; selected?: string; onSelect: (id?: string) => void; onAllocate: (packageId?: string) => void; onJar: (id: string) => void
}) {
  const wide = useMediaQuery('(min-width: 1000px)')
  const lots = (Object.values(state.lots) as CoffeeLot[]).filter(l => !l.archived)
  const packages = (Object.values(state.packages) as CoffeePackage[]).filter(p => !p.openedAt && !p.archived)
  const toggle = (id: string) => onSelect(selected === id ? undefined : id)
  const open = selected ? state.lots[selected] : undefined
  const unopened = <>
    <h2 className="h2">Ungeöffnete Packungen</h2>
    {packages.map(pkg => <div className="pk" key={pkg.id}>
      <span><b>{pkg.name}</b><small>{num(pkg.availableGrams)} g · {pkg.location === 'frozen' ? 'tiefgekühlt' : 'Vorrat'}{dateText(pkg.roastDate) ? ` · Röstdatum ${dateText(pkg.roastDate)}` : ''}</small></span>
      <button className="secondary" onClick={() => onAllocate(pkg.id)} aria-label={`${pkg.name} öffnen`}>Öffnen</button>
    </div>)}
    {packages.length === 0 && <p className="muted">Keine ungeöffneten Packungen.</p>}
  </>
  return <section className="page">
    <PageHeading eyebrow="Packungen & Chargen" title="Kaffees"><button className="primary" onClick={() => onAllocate()}>Packung öffnen</button></PageHeading>
    <div className={wide ? 'coffee-layout' : undefined}>
      <div>
        <h2 className="h2 first">Teilchargen</h2>
        {lots.length === 0 && <Empty title="Noch keine Teilcharge" text="Öffne eine Packung und bestätige ihre Glasaufteilung." />}
        {lots.map(lot => <div key={lot.id}>
          <LotCard lot={lot} state={state} categories={categories} open={selected === lot.id} onToggle={() => toggle(lot.id)} />
          {!wide && selected === lot.id && <LotTree lot={lot} state={state} categories={categories} onJar={onJar} />}
        </div>)}
        {unopened}
      </div>
      {wide && <div><h2 className="h2 first">Herkunft</h2>
        {open ? <LotTree lot={open} state={state} categories={categories} onJar={onJar} />
          : <div className="empty"><strong>Kaffee antippen</strong><p>Dann siehst du, woher er kommt und in welchen Gläsern er liegt.</p></div>}</div>}
    </div>
  </section>
}
