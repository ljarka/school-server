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
        val gradesRaw = try {
            getApiJson(ProtocolPaths.API_GRADES_PATH, childId)
        } catch (_: AccessDeniedException) {
            logger.info("e-journal grades resource not available for this account; keeping stored grades")
            return null
        }
        val gradesResponse = try {
            json.decodeFromString<GradesResponseDto>(gradesRaw)
        } catch (e: Exception) {
            throw JournalUnavailable("layout_changed:grades", e)
        }

        if (gradesResponse.grades.isEmpty()) {
            return emptyList()
        }

        val subjects = decodeLookup<SubjectsResponseDto>(ProtocolPaths.API_SUBJECTS_PATH, "subjects", childId)
            .subjects.associateBy { it.id }

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

        val users = decodeLookup<UsersResponseDto>(ProtocolPaths.API_USERS_PATH, "users", childId)
            .users.associateBy { it.id }

        return GradesMapper.map(
            grades = gradesResponse.grades,
            subjects = subjects,
            categories = categories,
            commentsByGradeId = comments,
            users = users,
        )
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

    private class AccessDeniedException : Exception()
}
