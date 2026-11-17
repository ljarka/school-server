package school.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlin.math.roundToLong
import school.model.GradeKind
import school.model.GradesListDto
import school.model.SubjectSummaryDto
import school.model.SubjectsListDto
import school.server.store.Store
import school.server.store.StoredGrade

fun Route.gradesRoutes(store: Store) {
    get("/grades") {
        ensureDataAvailable(store)

        val childId = store.resolveChildId(call.request.queryParameters["child"])
        val subject = call.request.queryParameters["subject"]?.trim()?.ifEmpty { null }
        val semester = parseSemesterParam(call.request.queryParameters["semester"])
        val kind = parseKindParam(call.request.queryParameters["kind"])
        val since = parseInstantParam(call.request.queryParameters["since"], "since")
        val includeDeleted = parseBooleanParam(call.request.queryParameters["includeDeleted"], "includeDeleted")

        val grades = store.listGrades(
            semester = semester,
            subject = subject,
            includeDeleted = includeDeleted,
            childId = childId,
        ).filter { stored ->
            (kind == null || stored.grade.kind == kind) &&
                (since == null || stored.firstSeenAt >= since)
        }.map { it.toDto() }

        call.respond(GradesListDto(items = grades))
    }

    get("/grades/subjects") {
        ensureDataAvailable(store)

        val childId = store.resolveChildId(call.request.queryParameters["child"])
        val semester = parseSemesterParam(call.request.queryParameters["semester"])
        val grades = store.listGrades(semester = semester, includeDeleted = false, childId = childId)

        val summaries = grades
            .groupBy { it.grade.subject }
            .toSortedMap()
            .map { (subject, subjectGrades) ->
                SubjectSummaryDto(
                    subject = subject,
                    average = computeWeightedAverage(subjectGrades),
                    grades = subjectGrades.map { it.toDto() },
                )
            }

        call.respond(SubjectsListDto(items = summaries))
    }
}

private fun parseSemesterParam(raw: String?): Int? {
    if (raw == null) return null
    val parsed = raw.toIntOrNull()
    if (parsed != 1 && parsed != 2) {
        throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "Invalid parameter 'semester': expected 1 or 2")
    }
    return parsed
}

private fun parseKindParam(raw: String?): GradeKind? {
    if (raw == null) return null
    return when (raw) {
        "regular" -> GradeKind.REGULAR
        "proposed_semester" -> GradeKind.PROPOSED_SEMESTER
        "semester" -> GradeKind.SEMESTER
        "proposed_final" -> GradeKind.PROPOSED_FINAL
        "final" -> GradeKind.FINAL
        else -> throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "Invalid parameter 'kind'")
    }
}

internal fun computeWeightedAverage(grades: List<StoredGrade>): Double? {
    var weightedSum = 0.0
    var weightTotal = 0.0

    for (stored in grades) {
        val g = stored.grade
        val num = g.numericValue ?: continue
        if (!g.countsToAverage) continue
        val w = (g.weight ?: 1).toDouble()
        if (w <= 0.0) continue
        weightedSum += num * w
        weightTotal += w
    }

    if (weightTotal == 0.0) return null
    val raw = weightedSum / weightTotal
    return (raw * 100.0).roundToLong() / 100.0
}
