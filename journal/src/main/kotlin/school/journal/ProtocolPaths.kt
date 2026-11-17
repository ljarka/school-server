package school.journal

/**
 * Protocol path constants for the e-journal endpoints (ADR 0006, ADR 0011).
 * This is the only file permitted to contain provider-specific path segments.
 */
internal object ProtocolPaths {
    const val PORTAL_LOGIN_PATH = "/konto-librus/login"
    const val PORTAL_LOGIN_ACTION_PATH = "/konto-librus/login/action"
    const val PORTAL_ME_PATH = "/api/v3/Me"
    const val PORTAL_ACCOUNTS_PATH = "/api/v3/SynergiaAccounts"
    const val PORTAL_FRESH_ACCOUNT_PATH = "/api/v3/SynergiaAccounts/fresh"

    const val API_GRADES_PATH = "/Grades"
    const val API_SUBJECTS_PATH = "/Subjects"
    const val API_CATEGORIES_PATH = "/Grades/Categories"
    const val API_COMMENTS_PATH = "/Grades/Comments"
    const val API_DESCRIPTIVE_GRADES_PATH = "/DescriptiveGrades"
    const val API_DESCRIPTIVE_SKILLS_PATH = "/DescriptiveGrades/Skills"
    const val API_DESCRIPTIVE_COMMENTS_PATH = "/DescriptiveGrades/Comments"
    const val API_DESCRIPTIVE_TEXT_GRADES_PATH = "/DescriptiveTextGrades"
    const val API_DESCRIPTIVE_TEXT_SKILLS_PATH = "/DescriptiveTextGrades/Skills"
    const val API_TEXT_GRADES_PATH = "/TextGrades"
    const val API_BASE_TEXT_GRADES_PATH = "/BaseTextGrades"
    const val API_TEXT_GRADE_CATEGORIES_PATH = "/TextGrades/Categories"
    const val API_POINT_GRADES_PATH = "/PointGrades"
    const val API_POINT_GRADE_CATEGORIES_PATH = "/PointGrades/Categories"
    const val API_POINT_GRADE_COMMENTS_PATH = "/PointGrades/Comments"
    const val API_USERS_PATH = "/Users"
    const val API_SCHOOL_NOTICES_PATH = "/SchoolNotices"
    const val API_AUTO_LOGIN_TOKEN_PATH = "/AutoLoginToken"
    const val AUTO_LOGIN_GATEWAY_PREFIX = "/loguj/token/"
    const val AUTO_LOGIN_GATEWAY_SUFFIX = "/przenies/uczen/widok/centrum_powiadomien"
    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 LibrusMobileApp"

    const val MESSAGES_ACTIVATION_PATH = "/wiadomosci3"
    const val MESSAGES_INBOX_PATH = "/api/inbox/messages"
    const val MESSAGES_UNREAD_COUNT_PATH = "/api/inbox/unreadMessagesCount"

    const val GATEWAY_PORTAL_INIT_PATH = "/loguj/portalRodzina"
    const val OAUTH_AUTHORIZATION_PATH = "/OAuth/Authorization?client_id=46"
    const val OAUTH_2FA_PATH = "/OAuth/Authorization/2FA?client_id=46"
    const val GATEWAY_API_PREFIX = "/gateway/api/2.0"
    const val GATEWAY_TOKEN_INFO_PATH = "/gateway/api/2.0/Auth/TokenInfo"
    const val GATEWAY_USER_INFO_PATH = "/gateway/api/2.0/Auth/UserInfo"

    const val SUBDOMAIN_PORTAL = "portal"
    const val SUBDOMAIN_API = "api"
    const val SUBDOMAIN_MESSAGES = "wiadomosci"
    const val SUBDOMAIN_GATEWAY = "synergia"
    const val API_VERSION_PATH = "/3.0"
}
