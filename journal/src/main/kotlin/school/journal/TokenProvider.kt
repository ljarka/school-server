package school.journal

import org.slf4j.LoggerFactory
import school.model.JournalUnavailable

internal class TokenProvider {
    private val logger = LoggerFactory.getLogger(TokenProvider::class.java)
    private var currentAccount: PortalAccountDto? = null

    @Synchronized
    fun setAccount(account: PortalAccountDto) {
        currentAccount = account
    }

    @Synchronized
    fun currentToken(): String? = currentAccount?.accessToken

    @Synchronized
    fun currentLogin(): String? = currentAccount?.login

    @Synchronized
    fun refresh(fetchFresh: (login: String) -> PortalAccountDto?, relogin: () -> PortalAccountDto): String {
        val login = currentAccount?.login
        if (login != null) {
            val fresh = runCatching { fetchFresh(login) }.getOrNull()
            if (fresh != null) {
                logger.debug("e-journal child token refreshed")
                currentAccount = fresh
                return fresh.accessToken
            }
        }

        val relogged = try {
            relogin()
        } catch (e: Throwable) {
            logger.debug("e-journal re-login failed during token refresh")
            throw JournalUnavailable("auth_refresh_failed", e)
        }

        currentAccount = relogged
        logger.debug("e-journal session and child token refreshed")
        return relogged.accessToken
    }
}
