package school.journal

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import school.model.ChildAccount
import school.model.Grade
import school.model.JournalClient
import school.model.JournalUnavailable
import school.model.Message
import school.model.MessageHeader
import school.model.MessageHeaderListing

class HttpJournalClient(
    private val endpoints: JournalEndpoints,
    login: String,
    passwordProvider: () -> String,
    configuredChild: String? = null,
    minRequestDelayMs: Long = 500L,
    private val messagesPageLimit: Int = 300,
    private val maxMessagePages: Int = 50,
    private val maxMessagesTotal: Int = 5_000,
) : JournalClient {
    private val logger = LoggerFactory.getLogger(HttpJournalClient::class.java)
    private val session = PortalSession(endpoints = endpoints, minDelayMs = minRequestDelayMs)
    private val json = Json { ignoreUnknownKeys = true }

    private val auth: JournalAuth = if (login.contains("@")) {
        PortalAuth(session, endpoints, login, passwordProvider, configuredChild, json)
    } else {
        GatewayAuth(session, endpoints, login, passwordProvider, json)
    }

    override fun login() {
        auth.login()
        logger.debug("e-journal login succeeded")
    }

    override fun listChildren(): List<ChildAccount> = auth.listChildren()

    override fun fetchGrades(childId: String?): List<Grade>? {
        val (standardAccessDenied, standardGrades) = try {
            val gradesRaw = getApiJson(ProtocolPaths.API_GRADES_PATH, childId)
            val parsed = try {
                json.decodeFromString<GradesResponseDto>(gradesRaw).grades
            } catch (e: Exception) {
                throw JournalUnavailable("layout_changed:grades", e)
            }
            false to parsed
        } catch (_: AccessDeniedException) {
            true to emptyList()
        }

        val (descriptiveAvailable, descriptiveGrades) =
            fetchOptionalList<DescriptiveGradesResponseDto, DescriptiveGradeDto>(
                path = ProtocolPaths.API_DESCRIPTIVE_GRADES_PATH,
                name = "descriptive_grades",
                childId = childId,
            ) { it.grades }

        val (descriptiveTextAvailable, descriptiveTextGrades) =
            fetchOptionalList<TextGradesResponseDto, TextGradeDto>(
                path = ProtocolPaths.API_DESCRIPTIVE_TEXT_GRADES_PATH,
                name = "descriptive_text_grades",
                childId = childId,
            ) { it.grades.filter(TextGradeDto::showInGradesView) }

        val (baseTextAvailable, baseTextGrades) =
            fetchOptionalList<TextGradesResponseDto, TextGradeDto>(
                path = ProtocolPaths.API_BASE_TEXT_GRADES_PATH,
                name = "base_text_grades",
                childId = childId,
            ) { it.grades.filter(TextGradeDto::showInGradesView) }

        val (textAvailable, textGrades) =
            fetchOptionalList<TextGradesResponseDto, TextGradeDto>(
                path = ProtocolPaths.API_TEXT_GRADES_PATH,
                name = "text_grades",
                childId = childId,
            ) { it.grades.filter(TextGradeDto::showInGradesView) }

        val (pointAvailable, pointGrades) =
            fetchOptionalList<PointGradesResponseDto, PointGradeDto>(
                path = ProtocolPaths.API_POINT_GRADES_PATH,
                name = "point_grades",
                childId = childId,
            ) { it.grades }

        val hasAnyGrades = standardGrades.isNotEmpty() ||
            descriptiveGrades.isNotEmpty() ||
            descriptiveTextGrades.isNotEmpty() ||
            baseTextGrades.isNotEmpty() ||
            textGrades.isNotEmpty() ||
            pointGrades.isNotEmpty()

        val anyModuleAvailable = !standardAccessDenied ||
            descriptiveAvailable ||
            descriptiveTextAvailable ||
            baseTextAvailable ||
            textAvailable ||
            pointAvailable

        if (!hasAnyGrades) {
            if (!anyModuleAvailable) {
                logger.info("e-journal grades resource not available for this account; keeping stored grades")
                return null
            }
            return emptyList()
        }

        val subjects = decodeOptionalLookup<SubjectsResponseDto>(ProtocolPaths.API_SUBJECTS_PATH, "subjects", childId)
            ?.subjects?.associateBy { it.id }.orEmpty()

        val users = decodeOptionalLookup<UsersResponseDto>(ProtocolPaths.API_USERS_PATH, "users", childId)
            ?.users?.associateBy { it.id }.orEmpty()

        val result = mutableListOf<Grade>()

        if (standardGrades.isNotEmpty()) {
            val categories = decodeLookup<GradeCategoriesResponseDto>(
                ProtocolPaths.API_CATEGORIES_PATH,
                "categories",
                childId,
            ).categories.associateBy { it.id }

            val comments = decodeLookup<GradeCommentsResponseDto>(ProtocolPaths.API_COMMENTS_PATH, "comments", childId)
                .comments
                .filter { it.grade != null && !it.text.isNullOrBlank() }
                .groupBy { it.grade!!.id }
                .mapValues { (_, list) -> list.joinToString("\n") { it.text.orEmpty().trim() } }

            result += GradesMapper.map(
                grades = standardGrades,
                subjects = subjects,
                categories = categories,
                commentsByGradeId = comments,
                users = users,
            )
        }

        if (descriptiveGrades.isNotEmpty()) {
            val skills = decodeOptionalLookup<DescriptiveSkillsResponseDto>(
                ProtocolPaths.API_DESCRIPTIVE_SKILLS_PATH,
                "descriptive_skills",
                childId,
            )?.skills?.associateBy { it.id }.orEmpty()

            val commentList = if (descriptiveGrades.any { it.comments.isNotEmpty() }) {
                decodeOptionalLookup<GradeCommentsResponseDto>(
                    ProtocolPaths.API_DESCRIPTIVE_COMMENTS_PATH,
                    "descriptive_comments",
                    childId,
                )?.comments.orEmpty()
            } else {
                emptyList()
            }
            val commentsById = commentList
                .filter { !it.text.isNullOrBlank() }
                .associate { it.id to it.text.orEmpty().trim() }
            val commentsByGradeId = commentList
                .filter { it.grade != null && !it.text.isNullOrBlank() }
                .groupBy { it.grade!!.id }
                .mapValues { (_, list) -> list.joinToString("\n") { it.text.orEmpty().trim() } }

            result += GradesMapper.mapDescriptive(
                grades = descriptiveGrades,
                subjects = subjects,
                skills = skills,
                commentsById = commentsById,
                commentsByGradeId = commentsByGradeId,
                users = users,
            )
        }

        if (descriptiveTextGrades.isNotEmpty() || baseTextGrades.isNotEmpty() || textGrades.isNotEmpty()) {
            val textCategories = if (baseTextGrades.any { it.category != null } ||
                textGrades.any { it.category != null }
            ) {
                decodeOptionalLookup<GradeCategoriesResponseDto>(
                    ProtocolPaths.API_TEXT_GRADE_CATEGORIES_PATH,
                    "text_grade_categories",
                    childId,
                )?.categories?.associateBy { it.id }.orEmpty()
            } else {
                emptyMap()
            }
            val textSkills = if (
                descriptiveTextGrades.any { it.skill != null } ||
                baseTextGrades.any { it.skill != null } ||
                textGrades.any { it.skill != null }
            ) {
                decodeOptionalLookup<DescriptiveSkillsResponseDto>(
                    ProtocolPaths.API_DESCRIPTIVE_TEXT_SKILLS_PATH,
                    "descriptive_text_skills",
                    childId,
                )?.skills?.associateBy { it.id }.orEmpty()
            } else {
                emptyMap()
            }

            if (descriptiveTextGrades.isNotEmpty()) {
                result += GradesMapper.mapText(
                    grades = descriptiveTextGrades,
                    prefix = "dtg",
                    subjects = subjects,
                    categories = textCategories,
                    skills = textSkills,
                    users = users,
                )
            }
            val combinedText = (baseTextGrades + textGrades).distinctBy { it.id }
            if (combinedText.isNotEmpty()) {
                result += GradesMapper.mapText(
                    grades = combinedText,
                    prefix = "tg",
                    subjects = subjects,
                    categories = textCategories,
                    skills = textSkills,
                    users = users,
                )
            }
        }

        if (pointGrades.isNotEmpty()) {
            val categories = decodeOptionalLookup<PointGradeCategoriesResponseDto>(
                ProtocolPaths.API_POINT_GRADE_CATEGORIES_PATH,
                "point_grade_categories",
                childId,
            )?.categories?.associateBy { it.id }.orEmpty()

            val commentList = if (pointGrades.any { it.comments.isNotEmpty() }) {
                decodeOptionalLookup<GradeCommentsResponseDto>(
                    ProtocolPaths.API_POINT_GRADE_COMMENTS_PATH,
                    "point_grade_comments",
                    childId,
                )?.comments.orEmpty()
            } else {
                emptyList()
            }
            val commentsById = commentList
                .filter { !it.text.isNullOrBlank() }
                .associate { it.id to it.text.orEmpty().trim() }
            val commentsByGradeId = commentList
                .filter { it.grade != null && !it.text.isNullOrBlank() }
                .groupBy { it.grade!!.id }
                .mapValues { (_, list) -> list.joinToString("\n") { it.text.orEmpty().trim() } }

            result += GradesMapper.mapPoint(
                grades = pointGrades,
                subjects = subjects,
                categories = categories,
                commentsById = commentsById,
                commentsByGradeId = commentsByGradeId,
                users = users,
            )
        }

        return result
    }

    override fun fetchAnnouncements(childId: String?): List<Message>? {
        val raw = try {
            getApiJson(ProtocolPaths.API_SCHOOL_NOTICES_PATH, childId)
        } catch (_: AccessDeniedException) {
            logger.info("e-journal announcements not available for this account; keeping stored announcements")
            return null
        }
        val notices = try {
            json.decodeFromString<SchoolNoticesResponseDto>(raw).notices
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:announcements", e)
        }
        if (notices.isEmpty()) {
            return emptyList()
        }
        val users = if (notices.any { it.addedBy != null }) {
            try {
                decodeLookup<UsersResponseDto>(ProtocolPaths.API_USERS_PATH, "users", childId).users.associateBy {
                    it.id
                }
            } catch (_: AccessDeniedException) {
                emptyMap()
            }
        } else {
            emptyMap()
        }
        return AnnouncementsMapper.map(notices, users)
    }

    private inline fun <reified T> decodeLookup(path: String, name: String, childId: String?): T {
        val raw = getApiJson(path, childId)
        return try {
            json.decodeFromString<T>(raw)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:$name", e)
        }
    }

    private inline fun <reified T> decodeOptionalLookup(path: String, name: String, childId: String?): T? {
        val raw = getOptionalApiJson(path, childId) ?: return null
        if (raw.isBlank()) return null
        return try {
            json.decodeFromString<T>(raw)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:$name", e)
        }
    }

    private inline fun <reified R, I> fetchOptionalList(
        path: String,
        name: String,
        childId: String?,
        extract: (R) -> List<I>,
    ): Pair<Boolean, List<I>> {
        val raw = getOptionalApiJson(path, childId) ?: return false to emptyList()
        if (raw.isBlank()) return true to emptyList()
        val items = try {
            extract(json.decodeFromString<R>(raw))
        } catch (_: Exception) {
            logger.debug("e-journal optional module {} returned unrecognized payload; skipping", name)
            return false to emptyList()
        }
        return true to items
    }

    override fun fetchMessageHeaders(childId: String?): MessageHeaderListing {
        val result = mutableListOf<MessageHeader>()
        val seenIds = HashSet<String>()

        for (page in 1..maxMessagePages) {
            val path = "${ProtocolPaths.MESSAGES_INBOX_PATH}?page=$page&limit=$messagesPageLimit"
            val raw = getMessagesJson(path, childId)
            val parsed = try {
                json.decodeFromString<MessagesListResponseDto>(raw)
            } catch (e: Exception) {
                throw JournalUnavailable("layout_changed:messages", e)
            }

            for (item in parsed.data) {
                val header = MessagesMapper.mapHeader(item)
                if (seenIds.add(header.sourceId)) {
                    result.add(header)
                    if (result.size >= maxMessagesTotal) {
                        logger.warn(
                            "e-journal inbox has more than {} messages; older ones are not tracked",
                            maxMessagesTotal,
                        )
                        return MessageHeaderListing(result, complete = false)
                    }
                }
            }

            if (parsed.data.size < messagesPageLimit) {
                return MessageHeaderListing(result, complete = true)
            }
        }

        logger.warn("e-journal inbox has more than {} pages; older messages are not tracked", maxMessagePages)
        return MessageHeaderListing(result, complete = false)
    }

    override fun fetchMessage(sourceId: String, childId: String?): Message {
        val rawId = sourceId.removePrefix("m_")
        val raw = getMessagesJson("${ProtocolPaths.MESSAGES_INBOX_PATH}/$rawId", childId)
        val parsed = try {
            json.decodeFromString<MessageDetailResponseDto>(raw)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:messages", e)
        }
        return MessagesMapper.mapDetail(parsed.data)
    }

    private fun getMessagesJson(path: String, childId: String?): String {
        auth.ensureMessagesSession(childId)
        val headers = mapOf("Accept" to "application/json", "User-Agent" to BROWSER_USER_AGENT)
        val first = session.get(url = endpoints.messagesUrl(path), headers = headers)
        if (!first.isAuthError()) {
            return first.requireRenewedSession()
        }
        auth.renewMessagesSession(childId)
        return session.get(url = endpoints.messagesUrl(path), headers = headers).requireRenewedSession()
    }

    /** GET on the JSON API (bearer or gateway cookie), re-authenticating once when the session expired. */
    internal fun getApiJson(path: String, childId: String? = null): String {
        val response = auth.getApi(path, childId)
        if (response.isAccessDenied()) {
            throw AccessDeniedException()
        }
        return response.requireRenewedSession()
    }

    /** Like [getApiJson], but returns `null` when an optional module answers 403 AccessDeny, 404 or 405. */
    internal fun getOptionalApiJson(path: String, childId: String? = null): String? {
        val response = auth.getApi(path, childId)
        if (response.isOptionalModuleUnavailable()) {
            return null
        }
        return response.requireRenewedSession()
    }

    private class AccessDeniedException : Exception()
}
