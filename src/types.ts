export type BrewMethod = 'espresso' | 'filter'
export type BeanState = 'whole' | 'ground'
export type DatePrecision = 'exact' | 'approximate' | 'unknown'
export type PackageLocation = 'pantry' | 'frozen'
export type RuleProfile = 'A' | 'B' | 'DIRECT_S' | 'MANUAL'
export type JarSize = 'S' | 'M' | 'L'
export type JarStatus = 'free' | 'planned' | 'frozen' | 'active' | 'cleaning'

export interface FlexibleDate {
  value?: string
  precision: DatePrecision
}

export interface CategoryDefinition {
  id: string
  name: string
  ruleProfile: RuleProfile
  builtIn: boolean
}

export interface AppSettings {
  timezone: 'Europe/Berlin'
  espressoBaseGrams: number
  espressoAllowanceGrams: number
  filterBaseGrams: number
  filterAllowanceGrams: number
  categories: CategoryDefinition[]
}

export interface CoffeePackage {
  id: string
  name: string
  nominalGrams: number
  availableGrams: number
  actualGrams?: number
  roastDate: FlexibleDate
  purchaseDate: FlexibleDate
  location: PackageLocation
  frozenAt?: string
  openedAt?: string
  archived: boolean
}

export interface CoffeeLot {
  id: string
  packageId: string
  parentLotId?: string
  name: string
  brewMethod: BrewMethod
  beanState: BeanState
  categoryId: string
  ruleProfile: RuleProfile
  baseGrams: number
  allowanceGrams: number
  initialGrams: number
  unallocatedGrams: number
  thawCount: number
  refreezeCount: number
  archived: boolean
}

export interface JarState {
  id: string
  size: JarSize
  status: JarStatus
  lotId?: string
  grams: number
  filledAt?: string
  openedAt?: string
  lastFreedAt: string
  thawCount: number
  refreezeCount: number
}

export type EventType =
  | 'PACKAGE_CREATED'
  | 'PACKAGE_FROZEN'
  | 'PACKAGE_OPENED'
  | 'LOT_CREATED'
  | 'LOT_GROUND'
  | 'ALLOCATION_COMMITTED'
  | 'JAR_OPENED'
  | 'TRANSFER_COMMITTED'
  | 'CONSUMED'
  | 'JAR_CLEANED'
  | 'CATEGORY_CHANGED'
  | 'CATEGORY_DEFINED'
  | 'SETTINGS_CHANGED'
  | 'ARCHIVED'
  | 'RULE_OVERRIDE'
  | 'MEASUREMENT_ADJUSTMENT'
  | 'CORRECTION'

export interface InventoryEvent<T = Record<string, unknown>> {
  id: string
  type: EventType
  recordedAt: string
  effectiveAt: string
  resourceIds: string[]
  parentEventIds?: string[]
  reason?: string
  warnings?: string[]
  data: T
}

export interface InventoryDocument {
  schemaVersion: 1
  createdAt: string
  settings: AppSettings
  events: InventoryEvent[]
}

export interface InventoryState {
  packages: Record<string, CoffeePackage>
  lots: Record<string, CoffeeLot>
  jars: Record<string, JarState>
  correctedEventIds: Set<string>
  overrides: InventoryEvent[]
  warnings: string[]
}

export interface FillPlanItem {
  jarId: string
  size: JarSize
  suggestedGrams: number
  actualGrams: number
}

export interface AllocationPlan {
  fullPortions: number
  portionGrams: number
  restGrams: number
  fills: FillPlanItem[]
  reserve: Partial<Record<JarSize, number>>
  warnings: string[]
  unallocatedGrams: number
}

export interface LocalEnvelope {
  document: InventoryDocument
  pendingEventIds: string[]
  pendingReplacement: boolean
  lastRemoteEventIds: string[]
}

export interface DriveSnapshot {
  document: InventoryDocument
  fileId: string
  version: string
}
