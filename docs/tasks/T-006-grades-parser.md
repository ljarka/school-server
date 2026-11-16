# T-006 — Grades from the JSON API

**Status:** done
**Depends on:** T-005
**ADRs:** 0011, 0003, 0006

## Summary

Implement `JournalClient.fetchGrades()` using API 3.0 JSON (ADR 0011) and map the result to
neutral `Grade` objects with stable ids.

## Reference

The reference SDK's grades methods and its JSON API v3 OpenAPI file (both linked in
`private/journal-references.md`): the grades endpoint returns ids that point
to other resources (subject, category, added-by user, comments). Use the SDK to see which
lookups it calls and how it resolves them (subjects, grade categories, users, grade comments;
also descriptive/text grades if the school uses them).

## Domain type (`:model`)

```kotlin
@Serializable data class Grade(
    val sourceId: String,           // "g_" + API grade id
    val subject: String,
    val value: String,              // as shown: "5", "4+", "np", "+"
    val numericValue: Double?,      // "4+" → 4.5, "3-" → 2.75, "np"/"+"/"-" → null
    val weight: Int?,               // from the grade category
    val category: String,
    val semester: Int,
    val kind: GradeKind,            // REGULAR, PROPOSED_SEMESTER, SEMESTER, PROPOSED_FINAL, FINAL (from API flags)
    val countsToAverage: Boolean,   // from the category
    val teacher: String,            // "First Last" from users lookup
    val comment: String?,
    val issuedOn: LocalDate,
)
```

## Scope

- Fetch grades + lookups in as few calls as possible (lookups once per sync, cached in memory
  for that sync only).
- Missing lookup entry → fill a neutral placeholder (`"?"`) and log at DEBUG; never drop the grade.
- `numericValue` mapping in one pure function with a table-driven test.
- Unexpected JSON shape (required field missing) → `JournalUnavailable("layout_changed:grades")`,
  never an empty list (an empty list would cause mass `deleted` events).
- Empty grades array (start of the year) → empty list (valid).
- Mapping from provider DTOs to `Grade` is a pure function, tested without HTTP.

## Tests

JSON fixtures in `journal/src/test/resources/fixtures/grades/` — **synthetic** (built from
the reference SDK's OpenAPI schemas/examples, invented Polish data) until T-013 adds
anonymised real ones: `grades.json`,
`subjects.json`, `categories.json`, `users.json`, `comments.json`, `grades_empty.json`,
`grades_broken.json`, plus semester/final/proposed examples.

- Full objects compared field by field; semesters; kinds; weights; comments with Polish
  characters; `np`, `+`, `-`; missing lookup → placeholder.
- Broken JSON shape → `JournalUnavailable`.
- Same input twice → identical `sourceId`s.

## Acceptance

Tests green; no provider DTO leaks out of `:journal`.

## Decisions

## Remaining
