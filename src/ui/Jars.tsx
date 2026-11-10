import type { InventoryState, JarSize, JarState } from '../types'
import { PageHeading } from './common'
import { JarTile, Legend } from './JarTile'
import { useLook } from './theme'

const SIZES: Record<JarSize, [string, string]> = { S: ['Einzelportionen', '53 ml · 48 Gläser'], M: ['Mehrportionen', '212 ml · 36 Gläser'], L: ['Großgläser', '555 ml · 12 Gläser'] }

export function JarMatrix({ state, selected, onSelect }: { state: InventoryState; selected?: string; onSelect: (id: string) => void }) {
  const { tileStyle } = useLook()
  const jars = Object.values(state.jars) as JarState[]
  return <section className="page">
    <PageHeading eyebrow="S01 bis L12" title="Gläser" lead="Ein Tipp zeigt Inhalt, Herkunft und die nächsten möglichen Aktionen." />
    <Legend tileStyle={tileStyle} />
    {(['S', 'M', 'L'] as JarSize[]).map(size => <article className="panel jar-group" key={size}>
      <div className="jar-group-title"><div className="size-token">{size}</div><div><h2>{SIZES[size][0]}</h2><small>{SIZES[size][1]}</small></div></div>
      <div className="tile-grid">{jars.filter(j => j.size === size).map(jar => <JarTile key={jar.id} jar={jar} state={state} tileStyle={tileStyle} selected={selected === jar.id} onClick={() => onSelect(jar.id)} />)}</div>
    </article>)}
  </section>
}
