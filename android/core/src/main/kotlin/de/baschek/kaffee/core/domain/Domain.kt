package de.baschek.kaffee.core.domain

import kotlin.math.floor

/**
 * Line-by-line port of the coffee app's domain logic (kaffeapp/src/domain: defaults.ts, reducer.ts, planner.ts, plus
 * the transfer suggestion of TransferAction in App.tsx). The TypeScript code is the reference; the golden cases in
 * core/src/test/resources/coffee_golden.json are generated from it (src/golden/golden.test.ts) and the same file is
 * checked by TESS (Python). Change the rules there first.
 */
class DomainError(message: String) : Exception(message)

const val NEVER_FREED = "1970-01-01T00:00:00.000Z"
val JAR_COUNTS = listOf("S" to 48, "M" to 36, "L" to 12)
val EVENT_TYPES = setOf(
    "PACKAGE_CREATED", "PACKAGE_FROZEN", "PACKAGE_OPENED", "LOT_CREATED", "LOT_GROUND", "ALLOCATION_COMMITTED",
    "JAR_OPENED", "TRANSFER_COMMITTED", "CONSUMED", "JAR_CLEANED", "CATEGORY_CHANGED", "CATEGORY_DEFINED",
    "SETTINGS_CHANGED", "ARCHIVED", "RULE_OVERRIDE", "MEASUREMENT_ADJUSTMENT", "CORRECTION",
)

fun builtInCategories(): MutableList<Any?> = mutableListOf(
    obj("id" to "premium-whole", "name" to "Hochwertig / wichtig · ganze Bohne", "ruleProfile" to "A", "builtIn" to true),
    obj("id" to "daily-driver", "name" to "Daily Driver", "ruleProfile" to "B", "builtIn" to true),
    obj("id" to "daily-ground-m-to-s", "name" to "Daily · gemahlen · M → S", "ruleProfile" to "A", "builtIn" to true),
    obj("id" to "important-ground", "name" to "Wichtig · gemahlen", "ruleProfile" to "DIRECT_S", "builtIn" to true),
    obj("id" to "less-important-ground", "name" to "Weniger wichtig · gemahlen", "ruleProfile" to "MANUAL", "builtIn" to true),
)

fun defaultSettings(): Obj = obj(
    "timezone" to "Europe/Berlin", "espressoBaseGrams" to 18.0, "espressoAllowanceGrams" to 0.2,
    "filterBaseGrams" to 15.0, "filterAllowanceGrams" to 0.1, "categories" to builtInCategories(),
)

fun createJars(): Obj {
    val jars = obj()
    for ((size, count) in JAR_COUNTS) for (index in 1..count) {
        val id = size + index.toString().padStart(2, '0')
        jars[id] = obj("id" to id, "size" to size, "status" to "free", "grams" to 0.0, "lastFreedAt" to NEVER_FREED,
            "thawCount" to 0.0, "refreezeCount" to 0.0)
    }
    return jars
}

fun createEmptyDocument(createdAt: String): Obj =
    obj("schemaVersion" to 1.0, "createdAt" to createdAt, "settings" to defaultSettings(), "events" to mutableListOf<Any?>())

/** Settings plus CATEGORY_DEFINED and SETTINGS_CHANGED in journal order (unsorted, like the app). */
fun effectiveSettings(document: Obj): Obj {
    val settings = deepCopy(document.o("settings"))
    for (event in document.objs("events")) {
        when (event["type"]) {
            "CATEGORY_DEFINED" -> {
                val category = event.o("data").o("category")
                val kept = settings.objs("categories").filter { it["id"] != category["id"] }
                settings["categories"] = (kept + deepCopy(category)).toMutableList<Any?>()
            }
            "SETTINGS_CHANGED" -> event.o("data").forEach { (k, v) -> settings[k] = deepCopy(v) }
        }
    }
    return settings
}

fun portionCapacity(size: String, brew: String): Int = when (size) {
    "S" -> 1
    "M" -> if (brew == "espresso") 3 else 4
    else -> if (brew == "espresso") 9 else 12
}

/** Derived stock. Mirrors InventoryState plus [failedEventIds] (events that raised a warning). */
class InventoryState(
    val packages: Obj = obj(),
    val lots: Obj = obj(),
    val jars: Obj = createJars(),
    val correctedEventIds: MutableSet<String> = linkedSetOf(),
    val overrides: MutableList<Obj> = mutableListOf(),
    val warnings: MutableList<String> = mutableListOf(),
    val failedEventIds: MutableSet<String> = linkedSetOf(),
) {
    fun jar(id: String): Obj? = jars.oOrNull(id)
    fun lot(id: String?): Obj? = id?.let { lots.oOrNull(it) }
    fun pkg(id: String?): Obj? = id?.let { packages.oOrNull(it) }
    fun allJars(): List<Obj> = jars.values.map { it as Obj }
    fun allLots(): List<Obj> = lots.values.map { it as Obj }
    fun allPackages(): List<Obj> = packages.values.map { it as Obj }
}

/** recordedAt keeps the causal booking order even when effectiveAt is backdated (stable sort). */
fun sortEvents(events: Collection<Obj>): List<Obj> = events.sortedBy { it.s("recordedAt") }

fun reduceInventory(document: Obj, observer: ((Obj, InventoryState) -> Unit)? = null): InventoryState {
    val state = InventoryState()
    val unique = linkedMapOf<String, Obj>()
    for (event in document.objs("events")) unique[event.s("id")] = event // Map.set keeps position, last value wins
    val events = sortEvents(unique.values)
    for (event in events) if (event["type"] == "CORRECTION") {
        (event.oOrNull("data")?.get("targetEventId") as? String)?.let { state.correctedEventIds += it }
    }
    for (event in events) {
        if (event["type"] != "CORRECTION" && event.s("id") in state.correctedEventIds) continue
        try {
            observer?.invoke(event, state)
            applyEvent(state, event)
        } catch (error: DomainError) {
            state.warnings += "${event.s("id")}: ${error.message}"
            state.failedEventIds += event.s("id")
        } catch (error: RuntimeException) {
            state.warnings += "${event.s("id")}: Ungültiges Ereignis"
            state.failedEventIds += event.s("id")
        }
    }
    return state
}

private fun lotOf(state: InventoryState, id: Any?): Obj = state.lot(id as? String) ?: throw DomainError("Charge nicht gefunden")

fun applyEvent(state: InventoryState, event: Obj) {
    val data = event.o("data")
    val jars = state.jars
    when (event["type"]) {
        "PACKAGE_CREATED" -> {
            val pkg = deepCopy(data.o("package"))
            state.packages[pkg.s("id")] = pkg
        }
        "PACKAGE_FROZEN" -> {
            val pkg = state.pkg(data["packageId"] as? String) ?: throw DomainError("Packung nicht gefunden")
            pkg["location"] = "frozen"
            pkg.assign("frozenAt" to data["frozenAt"])
        }
        "PACKAGE_OPENED" -> {
            val pkg = state.pkg(data["packageId"] as? String) ?: throw DomainError("Packung nicht gefunden")
            pkg.assign("actualGrams" to data["actualGrams"], "availableGrams" to data["actualGrams"], "openedAt" to data["openedAt"])
        }
        "LOT_CREATED" -> {
            val lot = deepCopy(data.o("lot"))
            val pkg = state.pkg(lot["packageId"] as? String) ?: throw DomainError("Ursprungspackung nicht gefunden")
            if (pkg.d("availableGrams") + 0.001 < lot.d("initialGrams")) throw DomainError("Teilcharge übersteigt verfügbares Packungsgewicht")
            pkg["availableGrams"] = jsRound(pkg.d("availableGrams") - lot.d("initialGrams"))
            state.lots[lot.s("id")] = lot
        }
        "LOT_GROUND" -> lotOf(state, data["lotId"])["beanState"] = "ground"
        "ALLOCATION_COMMITTED" -> {
            val lot = lotOf(state, data["lotId"])
            for (fill in data.objs("fills")) {
                val jar = state.jar(fill.s("jarId"))
                if (jar == null || jar["status"] != "free") throw DomainError("${fill.s("jarId")} ist nicht frei")
                jar.assign("status" to "frozen", "lotId" to data["lotId"], "grams" to jsRound(fill.d("grams")),
                    "filledAt" to fill["frozenAt"], "openedAt" to null)
                jar["thawCount"] = lot["thawCount"]
                jar["refreezeCount"] = lot["refreezeCount"]
            }
            lot["unallocatedGrams"] = jsRound(data.d("unallocatedGrams"))
        }
        "JAR_OPENED" -> {
            val jar = state.jar(data.s("jarId"))
            if (jar == null || jar["status"] != "frozen") throw DomainError("${data.s("jarId")} ist nicht tiefgekühlt belegt")
            jar["status"] = "active"
            jar.assign("openedAt" to data["openedAt"])
            jar["thawCount"] = jar.d("thawCount") + 1
        }
        "TRANSFER_COMMITTED" -> {
            val source = state.jar(data.s("sourceJarId"))
            val targets = data.objs("targets")
            val consumed = data.dOrNull("consumedGrams") ?: 0.0
            if (source == null || (source["lotId"] as? String).isNullOrEmpty()) throw DomainError("Ausgangsglas ist leer")
            val transferred = jsRound(targets.sumOf { it.d("grams") })
            if (transferred + consumed > source.d("grams") + 0.001) throw DomainError("Umfüllmenge übersteigt Glasinhalt")
            for (target in targets) {
                val jar = state.jar(target.s("jarId"))
                if (jar == null || jar["status"] != "free") throw DomainError("${target.s("jarId")} ist nicht frei")
                val frozen = target.b("frozen")
                jar.assign("status" to (if (frozen) "frozen" else "active"), "lotId" to source["lotId"],
                    "grams" to jsRound(target.d("grams")), "filledAt" to event["effectiveAt"],
                    "openedAt" to (if (frozen) null else event["effectiveAt"]), "thawCount" to source["thawCount"],
                    "refreezeCount" to source.d("refreezeCount") + (if (frozen) 1 else 0))
            }
            source["grams"] = jsRound(source.d("grams") - transferred - consumed)
            if (source.d("grams") <= 0.001) clearToCleaning(source) else source["status"] = "active"
        }
        "CONSUMED" -> {
            val jar = state.jar(data.s("jarId"))
            val grams = data.d("grams")
            if (jar == null || (jar["lotId"] as? String).isNullOrEmpty() || jar["status"] !in setOf("active", "frozen")) {
                throw DomainError("${data.s("jarId")} enthält keinen verbuchbaren Kaffee")
            }
            if (grams <= 0 || grams > jar.d("grams") + 0.001) throw DomainError("Ungültige Verbrauchsmenge")
            if (jar["status"] == "frozen") {
                jar["status"] = "active"; jar["openedAt"] = event["effectiveAt"]; jar["thawCount"] = jar.d("thawCount") + 1
            }
            jar["grams"] = jsRound(jar.d("grams") - grams)
            if (jar.d("grams") <= 0.001) clearToCleaning(jar)
        }
        "JAR_CLEANED" -> {
            val jar = state.jar(data.s("jarId"))
            if (jar == null || jar["status"] != "cleaning") throw DomainError("${data.s("jarId")} wartet nicht auf Reinigung")
            jar.assign("status" to "free", "lotId" to null, "grams" to 0.0, "filledAt" to null, "openedAt" to null,
                "lastFreedAt" to event["effectiveAt"], "thawCount" to 0.0, "refreezeCount" to 0.0)
        }
        "CATEGORY_CHANGED" -> {
            val lot = lotOf(state, data["lotId"])
            lot["categoryId"] = data["categoryId"]; lot["ruleProfile"] = data["ruleProfile"]
        }
        "ARCHIVED" -> {
            val id = data["entityId"] as? String
            if (data["entityType"] == "package") state.pkg(id)?.set("archived", true)
            if (data["entityType"] == "lot") state.lot(id)?.set("archived", true)
        }
        "RULE_OVERRIDE" -> state.overrides += event
        "MEASUREMENT_ADJUSTMENT" -> {
            val lot = lotOf(state, data["lotId"])
            lot["unallocatedGrams"] = jsRound(lot.d("unallocatedGrams") + data.d("deltaGrams"))
        }
        // CATEGORY_DEFINED, SETTINGS_CHANGED, CORRECTION and unknown types change no stock.
    }
}

private fun clearToCleaning(jar: Obj) {
    jar["status"] = "cleaning"; jar["grams"] = 0.0; jar.remove("openedAt")
}

// --- planner.ts ---

data class FillPlanItem(var jarId: String, val size: String, val suggestedGrams: Double, var actualGrams: Double)

data class AllocationPlan(
    val fullPortions: Int,
    val portionGrams: Double,
    val restGrams: Double,
    val fills: List<FillPlanItem>,
    val reserve: Map<String, Int>,
    val warnings: List<String>,
    val unallocatedGrams: Double,
)

fun planAllocation(
    grams: Double, brewMethod: String, baseGrams: Double, allowanceGrams: Double, ruleProfile: String,
    state: InventoryState, premiumDirectS: Boolean = false,
): AllocationPlan {
    val portion = jsRound(baseGrams + allowanceGrams)
    if (grams <= 0 || portion <= 0) throw DomainError("Gewicht und Portionsgröße müssen positiv sein.")
    val full = floor((grams + 1e-9) / portion).toInt()
    val rest = jsRound(grams - full * portion)
    val requested = mutableListOf<Pair<String, Double>>()
    var unallocated = rest
    val profile = if (premiumDirectS) "DIRECT_S" else ruleProfile

    when (profile) {
        "A" -> {
            val mCapacity = portionCapacity("M", brewMethod)
            val fullM = full / mCapacity
            val remaining = full % mCapacity
            repeat(fullM) { requested += "M" to jsRound(mCapacity * portion) }
            repeat(remaining) { requested += "S" to portion }
            // A amounts always keep one S portion; an exact M batch is split into S portions.
            if (requested.none { it.first == "S" } && fullM > 0) {
                requested.removeAt(requested.lastIndex)
                repeat(mCapacity) { requested += "S" to portion }
            }
            // A small difference stays with its lot, not loose.
            if (rest > 0.001) { requested += "S" to rest; unallocated = 0.0 }
        }
        "B" -> {
            val lGrams = jsRound(portionCapacity("L", brewMethod) * portion)
            val mGrams = jsRound(portionCapacity("M", brewMethod) * portion)
            var remaining = grams
            while (remaining + 1e-9 >= lGrams) { requested += "L" to lGrams; remaining = jsRound(remaining - lGrams) }
            while (remaining > 0.001) {
                val fill = minOf(mGrams, remaining)
                requested += "M" to jsRound(fill); remaining = jsRound(remaining - fill)
            }
            // A daily driver needs at least one M jar; an exact L batch replaces one L with three M.
            if (requested.none { it.first == "M" } && requested.any { it.first == "L" }) {
                requested.removeAt(requested.lastIndex)
                repeat(portionCapacity("L", brewMethod) / portionCapacity("M", brewMethod)) { requested += "M" to mGrams }
            }
            unallocated = 0.0
        }
        "DIRECT_S" -> {
            repeat(full) { requested += "S" to portion }
            if (rest > 0.001) { requested += "S" to rest; unallocated = 0.0 }
        }
        else -> unallocated = grams
    }

    val selected = mutableSetOf<String>()
    val fills = requested.map { (size, amount) ->
        val jarId = chooseJar(state, size, selected) ?: throw DomainError("Nicht genügend freie $size-Gläser.")
        selected += jarId
        FillPlanItem(jarId, size, amount, amount)
    }
    val reserve = linkedMapOf<String, Int>()
    if (profile == "A" && fills.any { it.size == "M" }) reserve["S"] = if (brewMethod == "espresso") 2 else 3
    if (profile == "B" && fills.any { it.size == "L" }) reserve["M"] = 3
    val warnings = mutableListOf<String>()
    for ((size, needed) in reserve) {
        val freeAfter = state.allJars().count { it["size"] == size && it["status"] == "free" && it.s("id") !in selected }
        if (freeAfter < needed) warnings += "Reserve unterschritten: $needed freie $size-Gläser benötigt, danach nur $freeAfter frei."
    }
    if (brewMethod == "filter" && fills.any { it.size == "S" }) warnings += "Passform voluminöser Filterbohnen im S-Glas praktisch prüfen."
    return AllocationPlan(full, portion, rest, fills, reserve, warnings, jsRound(unallocated))
}

/** The first jar of the longest run of consecutive free numbers (ties: the run freed longest ago). */
fun chooseJar(state: InventoryState, size: String, excluded: Set<String> = emptySet()): String? {
    val free = state.allJars()
        .filter { it["size"] == size && it["status"] == "free" && it.s("id") !in excluded }
        .sortedWith(compareBy<Obj>({ it.s("lastFreedAt") }, { it.s("id") }))
    if (free.isEmpty()) return null
    val byNumber = free.sortedBy { it.s("id").drop(1).toInt() }
    val runs = mutableListOf<MutableList<Obj>>()
    var run = mutableListOf<Obj>()
    for (jar in byNumber) {
        if (run.isEmpty() || jar.s("id").drop(1).toInt() == run.last().s("id").drop(1).toInt() + 1) run += jar
        else { runs += run; run = mutableListOf(jar) }
    }
    if (run.isNotEmpty()) runs += run
    val best = runs.sortedWith(compareBy<List<Obj>>({ -it.size }, { it[0].s("lastFreedAt") }, { it[0].s("id") }))
    return best.firstOrNull()?.firstOrNull()?.s("id") ?: free[0].s("id")
}

fun formatPortions(grams: Double, portionGrams: Double): String {
    val full = floor((grams + 1e-9) / portionGrams).toInt()
    val rest = jsRound(grams - full * portionGrams)
    val base = "$full volle Portion${if (full == 1) "" else "en"}"
    return if (rest > 0) "$base + ${germanNumber(rest)} g Rest" else base
}

/** toLocaleString('de-DE') for the small gram values used in the UI. */
fun germanNumber(value: Double): String = jsNum(jsRound(value)).replace('.', ',')

// --- TransferAction (App.tsx) ---

data class TransferTarget(val jarId: String, val grams: Double, val frozen: Boolean)
data class TransferDraft(val targets: List<TransferTarget>, val consumed: Double, val warnings: List<String>)

fun transferDraft(state: InventoryState, jarId: String, consumeFirst: Boolean): TransferDraft {
    val jar = state.jar(jarId)!!
    val lot = state.lot(jar["lotId"] as String)!!
    val portion = jsRound(lot.d("baseGrams") + lot.d("allowanceGrams"))
    val targetSize = if (jar["size"] == "L") "M" else "S"
    val consumed = if (jar["size"] == "M" && consumeFirst) minOf(portion, jar.d("grams")) else 0.0
    var remaining = jsRound(jar.d("grams") - consumed)
    val cap = jsRound(portionCapacity(targetSize, lot.s("brewMethod")) * portion)
    val targets = mutableListOf<TransferTarget>()
    val used = mutableSetOf<String>()
    while (remaining > 0.001) {
        val free = state.allJars().filter { it["size"] == targetSize && it["status"] == "free" && it.s("id") !in used }
            .minByOrNull { it.s("id") } ?: break
        val grams = minOf(cap, remaining)
        used += free.s("id")
        targets += TransferTarget(free.s("id"), grams, !(jar["size"] == "L" && targets.isEmpty()))
        remaining = jsRound(remaining - grams)
        if (jar["size"] == "L" && targets.size == 3) break
    }
    val moved = targets.sumOf { it.grams }
    val remainder = jsRound(jar.d("grams") - consumed - moved)
    val warnings = mutableListOf<String>()
    if (remainder > 0.001) warnings += "${jsNum(remainder)} g bleiben im aktiven Ausgangsglas, weil Zielgläser fehlen."
    if (jar.d("refreezeCount") >= 1 && targets.any { it.frozen }) warnings += "Diese Buchung erzeugt eine weitere Wiedereinfrierung."
    return TransferDraft(targets, consumed, warnings)
}

/** validateDocument() of storage/local.ts plus a check of the payload shape. */
fun validateDocument(value: Any?): Obj {
    val doc = value as? Obj ?: throw DomainError("Die Datei enthält kein gültiges Objekt.")
    if ((doc["schemaVersion"] as? Number)?.toDouble() != 1.0) throw DomainError("Nicht unterstützte oder fehlende schemaVersion.")
    if (doc["settings"] !is Map<*, *> || doc["events"] !is List<*>) throw DomainError("Einstellungen oder Ereignisliste fehlen.")
    val ids = mutableSetOf<String>()
    for (raw in doc.list("events")) {
        val event = raw as? Obj ?: throw DomainError("Mindestens ein Ereignis ist unvollständig.")
        val id = event["id"] as? String
        if (id.isNullOrEmpty() || (event["type"] as? String).isNullOrEmpty() || (event["recordedAt"] as? String).isNullOrEmpty() ||
            (event["effectiveAt"] as? String).isNullOrEmpty() || event["resourceIds"] !is List<*>) {
            throw DomainError("Mindestens ein Ereignis ist unvollständig.")
        }
        if (event["data"] !is Map<*, *>) throw DomainError("Ereignis ohne Daten: $id")
        if (!ids.add(id)) throw DomainError("Doppelte Ereignis-ID: $id")
    }
    return deepCopy(doc)
}
