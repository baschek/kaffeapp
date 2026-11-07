package de.baschek.kaffee.core.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** Date.prototype.toISOString(). */
fun isoNow(clock: () -> Instant = Instant::now): String = ISO.format(clock())
fun isoOf(instant: Instant): String = ISO.format(instant)

/** makeId() of events.ts */
fun makeId(prefix: String): String = "${prefix}_${UUID.randomUUID()}"

/** createEvent() of events.ts: a new journal event (resourceIds de-duplicated, undefined options left out). */
fun createEvent(
    type: String,
    data: Obj,
    resourceIds: List<String>,
    effectiveAt: String? = null,
    reason: String? = null,
    warnings: List<String>? = null,
    parentEventIds: List<String>? = null,
    now: String = isoNow(),
): Obj {
    val event = obj("id" to makeId("evt"), "type" to type, "recordedAt" to now, "effectiveAt" to (effectiveAt ?: now),
        "resourceIds" to resourceIds.distinct().toMutableList<Any?>())
    if (parentEventIds != null) event["parentEventIds"] = parentEventIds.toMutableList<Any?>()
    if (reason != null) event["reason"] = reason
    if (warnings != null) event["warnings"] = warnings.toMutableList<Any?>()
    event["data"] = data
    return event
}
