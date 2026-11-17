package school.journal

import org.slf4j.LoggerFactory
import school.model.AuthRejected
import school.model.JournalUnavailable

internal class TokenProvider {
    private val logger = LoggerFactory.getLogger(TokenProvider::class.java)
    private val accountsById = LinkedHashMap<String, PortalAccountDto>()

    @Synchronized
    fun setAccounts(accounts: List<PortalAccountDto>) {
        accountsById.clear()
        for (account in accounts) {
            accountsById[account.id.toString()] = account
        }
    }

    @Synchronized
    fun listAccounts(): List<PortalAccountDto> = accountsById.values.toList()

    @Synchronized
    fun currentAccount(childId: String? = null): PortalAccountDto? {
        if (childId == null) {
            return accountsById.values.firstOrNull()
        }
        return accountsById[childId]
            ?: accountsById.values.firstOrNull { it.login == childId }
    }

    @Synchronized
    fun currentToken(childId: String? = null): String? = currentAccount(childId)?.accessToken

    @Synchronized
    fun refresh(
        childId: String? = null,
        fetchFresh: (login: String) -> PortalAccountDto?,
        relogin: () -> List<PortalAccountDto>,
    ): String {
        val current = currentAccount(childId)
        if (current != null) {
            val fresh = runCatching { fetchFresh(current.login) }.getOrNull()
            if (fresh != null) {
                logger.debug("e-journal child token refreshed")
                accountsById[fresh.id.toString()] = fresh
                return fresh.accessToken
            }
        }

        val relogged = try {
            relogin()
        } catch (e: AuthRejected) {
            // Credentials were rejected: this must reach SyncService as AuthRejected so it stops
            // logging in (AGENTS.md rule 4). Wrapping it would turn it into a retryable error.
            throw e
        } catch (e: JournalUnavailable) {
            throw e
        } catch (e: Exception) {
            logger.debug("e-journal re-login failed during token refresh")
            throw JournalUnavailable("auth_refresh_failed", e)
        }

        setAccounts(relogged)
        val target = currentAccount(childId)
            ?: throw JournalUnavailable("auth_refresh_failed")
        logger.debug("e-journal session and child token refreshed")
        return target.accessToken
    }
}
