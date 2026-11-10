package de.baschek.kaffee.core

import de.baschek.kaffee.core.domain.Actions
import de.baschek.kaffee.core.domain.CsvExport
import de.baschek.kaffee.core.domain.DomainError
import de.baschek.kaffee.core.domain.Obj
import de.baschek.kaffee.core.domain.createEmptyDocument
import de.baschek.kaffee.core.domain.effectiveSettings
import de.baschek.kaffee.core.domain.list
import de.baschek.kaffee.core.domain.reduceInventory
import de.baschek.kaffee.core.domain.transferDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The bookings of the app's dialogs must derive cleanly, exactly like in the web app. */
class ActionsTest {
    private val document: Obj = createEmptyDocument("2026-11-07T08:00:00.000Z")
    private fun book(events: List<Obj>) { document.list("events").addAll(events) }
    private fun state() = reduceInventory(document)

    @Test
    fun packageSplitBrewTransferCleanAndCorrection() {
        book(Actions.newPackage("Testbohne", 250.0, Actions.FlexibleDate("2026-10-01", "exact"), Actions.FlexibleDate(null, "exact"), "pantry"))
        val pkgId = state().allPackages().single()["id"] as String
        val draft = Actions.planSplit(state(), effectiveSettings(document), pkgId, 248.3, 248.3, "filter", "whole", "daily-driver", "A", 15.0, 0.1, false)
        assertEquals(listOf("L", "M", "M"), draft.plan.fills.map { it.size })
        assertFailsWith<DomainError> { Actions.commitSplit(state(), draft.copy(plan = draft.plan.copy(warnings = listOf("x"))), warningsConfirmed = false) }
        book(Actions.commitSplit(state(), draft, warningsConfirmed = true))
        val big = draft.plan.fills[0].jarId
        val m = draft.plan.fills[1].jarId
        book(Actions.openJar(state().jar(m)!!))
        book(Actions.consume(state().jar(m)!!, Actions.portionOf(state().lot(state().jar(m)!!["lotId"] as String)!!)))
        assertEquals(45.3, state().jar(m)!!["grams"])
        assertFailsWith<DomainError> { Actions.consume(state().jar(m)!!, 99.0) }
        val transfer = transferDraft(state(), big, consumeFirst = true)
        book(Actions.transfer(state(), big, transfer, ""))
        assertEquals("cleaning", state().jar(big)!!["status"])
        book(Actions.clean(state().jar(big)!!))
        assertEquals("free", state().jar(big)!!["status"])
        val lastConsume = document.list("events").map { it as Obj }.last { it["type"] == "CONSUMED" }
        book(Actions.correction(lastConsume, "Doppelt gebucht"))
        assertEquals(60.4, state().jar(m)!!["grams"])
        book(Actions.defineCategory("Wettbewerb", "B"))
        assertTrue(effectiveSettings(document).list("categories").size == 6)
        assertEquals(emptyList(), state().warnings)
        val csv = CsvExport.files(document)
        assertTrue(csv.getValue("glaeser").startsWith("﻿\"Glas\";\"Größe\""))
    }

    @Test
    fun frozenPackageGetsTheRefreezeOverride() {
        book(Actions.newPackage("Frost", 100.0, Actions.FlexibleDate(null, "exact"), Actions.FlexibleDate("2026-11-01", "exact"), "frozen"))
        val pkgId = state().allPackages().single()["id"] as String
        val draft = Actions.planSplit(state(), effectiveSettings(document), pkgId, 100.0, 100.0, "espresso", "whole", "premium-whole", "A", 18.0, 0.2, false)
        book(Actions.commitSplit(state(), draft, warningsConfirmed = true))
        assertEquals(1, state().overrides.size)
        assertEquals(emptyList(), state().warnings)
    }
}
