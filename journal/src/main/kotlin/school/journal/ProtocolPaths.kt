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
    const val API_USERS_PATH = "/Users"
    const val API_AUTO_LOGIN_TOKEN_PATH = "/AutoLoginToken"

    const val MESSAGES_ACTIVATION_PATH = "/wiadomosci3"
    const val MESSAGES_INBOX_PATH = "/api/inbox/messages"
    const val MESSAGES_UNREAD_COUNT_PATH = "/api/inbox/unreadMessagesCount"

    const val GATEWAY_PORTAL_INIT_PATH = "/loguj/portalRodzina"
    const val OAUTH_AUTHORIZATION_PATH = "/OAuth/Authorization?client_id=46"
    const val OAUTH_PERFORM_LOGIN_PATH = "/OAuth/Authorization/PerformLogin?client_id=46"
    const val OAUTH_GRANT_PATH = "/OAuth/Authorization/Grant?client_id=46"
    const val GATEWAY_API_PREFIX = "/gateway/api/2.0"
    const val GATEWAY_TOKEN_INFO_PATH = "/gateway/api/2.0/Auth/TokenInfo"
    const val GATEWAY_ORIGIN = "app://librus"
    const val GATEWAY_USER_AGENT =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 LibrusMobileApp"
}
