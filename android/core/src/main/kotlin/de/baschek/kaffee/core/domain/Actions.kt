package de.baschek.kaffee.core.domain

/**
 * The bookings the app's dialogs create (App.tsx: AddPackageDialog, AllocateDialog, JarDetail, TransferAction,
 * HistoryView correction, DataView category). Pure functions returning new events; the caller appends them.
 */
object Actions {
    data class FlexibleDate(val value: String?, val precision: String) {
        fun toObj(): Obj = if (value.isNullOrBlank()) obj("precision" to "unknown") else obj("value" to value, "precision" to precision)
    }

    fun newPackage(name: String, grams: Double, roast: FlexibleDate, purchase: FlexibleDate, location: String, now: String = isoNow()): List<Obj> {
        require(name.isNotBlank() && grams > 0) { "Name und Gewicht fehlen" }
        val id = makeId("package")
        val pkg = obj("id" to id, "name" to name.trim(), "nominalGrams" to grams, "availableGrams" to grams,
            "roastDate" to roast.toObj(), "purchaseDate" to purchase.toObj(), "location" to location)
        if (location == "frozen") pkg["frozenAt"] = now
        pkg["archived"] = false
        val effective = if (!purchase.value.isNullOrBlank()) "${purchase.value}T12:00:00.000Z" else now
        val events = mutableListOf(createEvent("PACKAGE_CREATED", obj("package" to pkg), listOf(id), effectiveAt = effective))
        if (location == "frozen") events += createEvent("PACKAGE_FROZEN", obj("packageId" to id, "frozenAt" to now), listOf(id))
        return events
    }

    data class SplitDraft(val plan: AllocationPlan, val lot: Obj, val openedAt: String, val actualGrams: Double)

    /** makePlan of AllocateDialog. */
    fun planSplit(
        state: InventoryState, settings: Obj, packageId: String, actualGrams: Double, amount: Double, brew: String, bean: String,
        categoryId: String, manualProfile: String, baseGrams: Double, allowanceGrams: Double, directS: Boolean,
    ): SplitDraft {
        val pkg = state.pkg(packageId) ?: throw DomainError("Bitte zuerst eine Packung anlegen.")
        val available = if (pkg["openedAt"] != null) pkg.d("availableGrams") else actualGrams
        if (amount <= 0 || amount > available + 0.001) throw DomainError("Die Teilmenge übersteigt das verfügbare Gewicht.")
        val category = settings.objs("categories").firstOrNull { it["id"] == categoryId } ?: settings.objs("categories").first()
        val profile = if (category["ruleProfile"] == "MANUAL") manualProfile else category.s("ruleProfile")
        val frozen = pkg["location"] == "frozen"
        val lot = obj("id" to makeId("lot"), "packageId" to packageId, "name" to pkg["name"], "brewMethod" to brew, "beanState" to bean,
            "categoryId" to categoryId, "ruleProfile" to profile, "baseGrams" to baseGrams, "allowanceGrams" to allowanceGrams,
            "initialGrams" to amount, "unallocatedGrams" to amount, "thawCount" to (if (frozen) 1.0 else 0.0),
            "refreezeCount" to (if (frozen) 1.0 else 0.0), "archived" to false)
        val plan = planAllocation(amount, brew, baseGrams, allowanceGrams, profile, state, directS)
        return SplitDraft(plan, lot, isoNow(), actualGrams)
    }

    /** commit of AllocateDialog; throws DomainError with the app's messages. */
    fun commitSplit(state: InventoryState, draft: SplitDraft, warningsConfirmed: Boolean): List<Obj> {
        val pkg = state.pkg(draft.lot.s("packageId")) ?: throw DomainError("Packung nicht gefunden")
        val sum = jsRound(draft.plan.fills.sumOf { it.actualGrams })
        if (sum > draft.lot.d("initialGrams") + 0.001) throw DomainError("Die bestätigten Glasgewichte überschreiten die Teilcharge.")
        if (draft.plan.fills.map { it.jarId }.toSet().size != draft.plan.fills.size) throw DomainError("Eine Glasnummer wurde doppelt verwendet.")
        if (draft.plan.warnings.isNotEmpty() && !warningsConfirmed) throw DomainError("Bitte bestätige die Hinweise vor der Einlagerung.")
        val events = mutableListOf<Obj>()
        val lotId = draft.lot.s("id")
        if (pkg["openedAt"] == null) {
            events += createEvent("PACKAGE_OPENED", obj("packageId" to pkg["id"], "actualGrams" to draft.actualGrams, "openedAt" to draft.openedAt),
                listOf(pkg.s("id")), effectiveAt = draft.openedAt)
        }
        events += createEvent("LOT_CREATED", obj("lot" to deepCopy(draft.lot)), listOf(pkg.s("id"), lotId), effectiveAt = draft.openedAt)
        events += createEvent("ALLOCATION_COMMITTED",
            obj("lotId" to lotId, "fills" to draft.plan.fills.map { obj("jarId" to it.jarId, "grams" to it.actualGrams, "frozenAt" to draft.openedAt) }.toMutableList<Any?>(),
                "unallocatedGrams" to jsRound(draft.lot.d("initialGrams") - sum)),
            listOf(lotId) + draft.plan.fills.map { it.jarId }, warnings = draft.plan.warnings)
        if (pkg["location"] == "frozen") {
            events += createEvent("RULE_OVERRIDE", obj("kind" to "frozen-package-refreeze", "packageId" to pkg["id"], "lotId" to lotId),
                listOf(pkg.s("id"), lotId), reason = "Zuvor komplett eingefrorene Packung wurde portioniert und wieder eingefroren.")
        }
        return events
    }

    fun openJar(jar: Obj): List<Obj> =
        listOf(createEvent("JAR_OPENED", obj("jarId" to jar["id"], "openedAt" to isoNow()), listOf(jar.s("id"), jar.s("lotId"))))

    /** One brew: a full portion by default; an S jar is always used up completely. */
    fun consume(jar: Obj, grams: Double): List<Obj> {
        if (grams <= 0 || grams > jar.d("grams") + 0.001) throw DomainError("Ungültige Verbrauchsmenge")
        return listOf(createEvent("CONSUMED", obj("jarId" to jar["id"], "grams" to grams), listOf(jar.s("id"), jar.s("lotId"))))
    }

    fun portionOf(lot: Obj): Double = jsRound(lot.d("baseGrams") + lot.d("allowanceGrams"))

    fun clean(jar: Obj): List<Obj> = listOf(createEvent("JAR_CLEANED", obj("jarId" to jar["id"]), listOf(jar.s("id"))))

    /** commit of TransferAction; a reason is required when the draft has warnings. */
    fun transfer(state: InventoryState, jarId: String, draft: TransferDraft, reason: String): List<Obj> {
        val jar = state.jar(jarId)!!
        val lotId = jar.s("lotId")
        if (draft.targets.isEmpty()) throw DomainError("Keine freien Zielgläser")
        if (draft.warnings.isNotEmpty() && reason.isBlank()) throw DomainError("Bitte begründe die Abweichung.")
        val events = mutableListOf<Obj>()
        if (jar["status"] == "frozen") events += createEvent("JAR_OPENED", obj("jarId" to jarId, "openedAt" to isoNow()), listOf(jarId, lotId))
        events += createEvent("TRANSFER_COMMITTED",
            obj("sourceJarId" to jarId, "targets" to draft.targets.map { obj("jarId" to it.jarId, "grams" to it.grams, "frozen" to it.frozen) }.toMutableList<Any?>(),
                "consumedGrams" to draft.consumed),
            listOf(jarId, lotId) + draft.targets.map { it.jarId }, reason = reason.trim().ifBlank { null }, warnings = draft.warnings)
        if (draft.warnings.isNotEmpty()) {
            events += createEvent("RULE_OVERRIDE", obj("kind" to "transfer-exception", "sourceJarId" to jarId), listOf(jarId, lotId),
                reason = reason.trim(), warnings = draft.warnings)
        }
        return events
    }

    fun correction(target: Obj, reason: String): List<Obj> {
        require(reason.isNotBlank()) { "Grund fehlt" }
        @Suppress("UNCHECKED_CAST")
        return listOf(createEvent("CORRECTION", obj("targetEventId" to target["id"]), target["resourceIds"] as List<String>,
            reason = reason.trim(), parentEventIds = listOf(target.s("id"))))
    }

    fun defineCategory(name: String, ruleProfile: String): List<Obj> {
        val id = makeId("category")
        return listOf(createEvent("CATEGORY_DEFINED",
            obj("category" to obj("id" to id, "name" to name.trim(), "ruleProfile" to ruleProfile, "builtIn" to false)), listOf(id)))
    }
}

/** CSV export like exporter.ts (three files: Ereignisse, Bestand, Gläser; ';' separated, UTF-8 BOM). */
object CsvExport {
    fun files(document: Obj): Map<String, String> {
        val state = reduceInventory(document)
        val events = document.objs("events").map { e ->
            linkedMapOf<String, Any?>("ID" to e["id"], "Typ" to e["type"], "Wirksam" to e["effectiveAt"], "Erfasst" to e["recordedAt"],
                "Ressourcen" to (e["resourceIds"] as List<*>).joinToString(", "), "Grund" to (e["reason"] ?: ""),
                "Warnungen" to ((e["warnings"] as? List<*>)?.joinToString(" | ") ?: ""), "Daten" to e["data"].toJsonString())
        }
        val inventory = state.allPackages().map { p ->
            linkedMapOf<String, Any?>("Art" to "Packung", "ID" to p["id"], "Name" to p["name"],
                "Status" to (if (p["openedAt"] != null) "geöffnet" else p["location"]), "Gramm" to p["availableGrams"], "Bezug" to "")
        } + state.allLots().map { l ->
            linkedMapOf<String, Any?>("Art" to "Charge", "ID" to l["id"], "Name" to l["name"],
                "Status" to (if (l.b("archived")) "archiviert" else l["beanState"]), "Gramm" to l["unallocatedGrams"], "Bezug" to l["packageId"])
        }
        val jars = state.allJars().map { j ->
            linkedMapOf<String, Any?>("Glas" to j["id"], "Größe" to j["size"], "Status" to j["status"], "Charge" to (j["lotId"] ?: ""),
                "Gramm" to j["grams"], "Eingefroren" to (j["filledAt"] ?: ""), "Geöffnet" to (j["openedAt"] ?: ""))
        }
        return linkedMapOf("ereignisse" to toCsv(events), "bestand" to toCsv(inventory), "glaeser" to toCsv(jars))
    }

    fun toCsv(rows: List<Map<String, Any?>>): String {
        if (rows.isEmpty()) return "﻿"
        val columns = rows[0].keys.toList()
        fun quote(value: Any?) = "\"" + (value?.toString() ?: "").replace("\"", "\"\"") + "\""
        fun format(value: Any?): Any? = if (value is Double) germanNumber(value) else value
        return "﻿" + columns.joinToString(";") { quote(it) } + "\r\n" +
            rows.joinToString("\r\n") { row -> columns.joinToString(";") { quote(format(row[it])) } }
    }
}
