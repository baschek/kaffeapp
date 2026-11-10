package de.baschek.kaffee.core

import de.baschek.kaffee.core.domain.AllocationPlan
import de.baschek.kaffee.core.domain.DomainError
import de.baschek.kaffee.core.domain.InventoryState
import de.baschek.kaffee.core.domain.Obj
import de.baschek.kaffee.core.domain.TransferDraft
import de.baschek.kaffee.core.domain.createJars
import de.baschek.kaffee.core.domain.effectiveSettings
import de.baschek.kaffee.core.domain.jsRound
import de.baschek.kaffee.core.domain.list
import de.baschek.kaffee.core.domain.o
import de.baschek.kaffee.core.domain.objs
import de.baschek.kaffee.core.domain.parseObj
import de.baschek.kaffee.core.domain.planAllocation
import de.baschek.kaffee.core.domain.reduceInventory
import de.baschek.kaffee.core.domain.sortEvents
import de.baschek.kaffee.core.domain.transferDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Kotlin port must match the TypeScript reference exactly (same fixture as TESS tests/test_coffee_golden.py).
 * Regenerate in the kaffeapp root: GOLDEN_OUT=<path> npx vitest run src/golden
 */
class CoffeeGoldenTest {
    private val golden: Obj = parseObj(javaClass.getResource("/coffee_golden.json")!!.readText())
    private val scenarios = golden.objs("scenarios")

    private fun comparable(state: InventoryState): Map<String, Any?> = mapOf(
        "packages" to state.packages, "lots" to state.lots, "jars" to state.jars,
        "correctedEventIds" to state.correctedEventIds.sorted(), "overrides" to state.overrides.map { it["id"] },
        "warnings" to state.warnings,
    )

    private fun expected(expected: Obj): Map<String, Any?> {
        val jars = createJars()
        jars.putAll(expected.o("jars"))
        return mapOf(
            "packages" to expected["packages"], "lots" to expected["lots"], "jars" to jars,
            "correctedEventIds" to expected.list("correctedEventIds"), "overrides" to expected.list("overrides"),
            "warnings" to expected.list("warnings"),
        )
    }

    private fun prefix(document: Obj, count: Int): Obj =
        LinkedHashMap(document).apply { this["events"] = sortEvents(document.objs("events")).take(count).toMutableList<Any?>() }

    private fun plan(input: Obj, state: InventoryState): AllocationPlan = planAllocation(
        input["grams"] as Double, input["brewMethod"] as String, input["baseGrams"] as Double,
        input["allowanceGrams"] as Double, input["ruleProfile"] as String, state, input["premiumDirectS"] == true,
    )

    private fun AllocationPlan.asMap(): Map<String, Any?> = mapOf(
        "fullPortions" to fullPortions.toDouble(), "portionGrams" to portionGrams, "restGrams" to restGrams,
        "fills" to fills.map { mapOf("jarId" to it.jarId, "size" to it.size, "suggestedGrams" to it.suggestedGrams, "actualGrams" to it.actualGrams) },
        "reserve" to reserve.mapValues { it.value.toDouble() }, "warnings" to warnings, "unallocatedGrams" to unallocatedGrams,
    )

    private fun TransferDraft.asMap(): Map<String, Any?> = mapOf(
        "targets" to targets.map { mapOf("jarId" to it.jarId, "grams" to it.grams, "frozen" to it.frozen) },
        "consumed" to consumed, "warnings" to warnings,
    )

    @Test
    fun reducerMatchesTypeScript() {
        for (scenario in scenarios) {
            val document = scenario.o("document")
            assertEquals(expected(scenario.o("expected")), comparable(reduceInventory(document)), scenario["name"] as String)
            assertEquals(scenario["settings"], effectiveSettings(document), scenario["name"] as String)
        }
    }

    @Test
    fun plansAndTransfersMatchTypeScript() {
        for (scenario in scenarios) {
            val document = scenario.o("document")
            for (case in scenario.objs("plans")) {
                val state = reduceInventory(prefix(document, (case["eventCount"] as Double).toInt()))
                if (case.containsKey("error")) {
                    val error = assertFailsWith<DomainError> { plan(case.o("input"), state) }
                    assertEquals(case["error"], error.message)
                } else {
                    assertEquals(case["plan"], plan(case.o("input"), state).asMap(), scenario["name"] as String)
                }
            }
            for (case in scenario.objs("transfers")) {
                val state = reduceInventory(prefix(document, (case["eventCount"] as Double).toInt()))
                val draft = transferDraft(state, case["jarId"] as String, case["consumeFirst"] == true)
                assertEquals(case["draft"], draft.asMap(), scenario["name"] as String)
            }
        }
    }

    @Test
    fun plannerCasesOnEmptyStock() {
        val state = InventoryState()
        for (case in golden.objs("planner")) {
            if (case.containsKey("error")) assertFailsWith<DomainError> { plan(case.o("input"), state) }
            else assertEquals(case["plan"], plan(case.o("input"), state).asMap(), case["input"].toString())
        }
    }

    @Test
    fun fixtureCoversEveryRule() {
        val types = scenarios.flatMap { s -> s.o("document").objs("events").map { it["type"] } }.toSet()
        assertTrue(types.containsAll(listOf("ALLOCATION_COMMITTED", "TRANSFER_COMMITTED", "CONSUMED", "JAR_CLEANED", "CORRECTION")))
        assertTrue(scenarios.sumOf { it.o("expected").list("warnings").size } > 10)
    }

    @Test
    fun jsRoundIsHalfUpLikeMathRound() {
        assertEquals(1.01, jsRound(1.005))
        assertEquals(-2.34, jsRound(-2.345))
        assertEquals(415.7, jsRound(470.3 - 18.2 * 3))
    }
}
