package school.journal

import org.slf4j.LoggerFactory
import school.model.AuthRejected

internal object ChildSelector {
    private val logger = LoggerFactory.getLogger(ChildSelector::class.java)

    fun select(accounts: List<PortalAccountDto>, configuredChild: String?): PortalAccountDto {
        if (accounts.isEmpty()) {
            logger.warn("No linked child accounts found in e-journal")
            throw AuthRejected("child_not_found")
        }

        val selector = configuredChild?.trim()?.ifEmpty { null }
        if (selector == null) {
            if (accounts.size > 1) {
                logger.warn(
                    "Multiple linked child accounts found ({}); selecting the first account",
                    accounts.size,
                )
            }
            return accounts.first()
        }

        val match = accounts.firstOrNull { account ->
            account.id.toString() == selector ||
                account.accountIdentifier == selector ||
                account.login == selector
        }

        if (match == null) {
            logger.warn(
                "Configured child not found; available child ids: {}",
                accounts.map { it.id },
            )
            throw AuthRejected("child_not_found")
        }

        return match
    }
}
