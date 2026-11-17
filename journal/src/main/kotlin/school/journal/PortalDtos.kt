package school.journal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PortalMeDto(val identifier: Long, val email: String)

@Serializable
internal data class PortalAccountDto(
    val id: Long,
    val accountIdentifier: String,
    val group: String = "",
    val login: String,
    val studentName: String = "",
    val accessToken: String,
    val state: String = "",
)

@Serializable
internal data class PortalAccountsResponseDto(
    val lastModification: Long = 0L,
    val accounts: List<PortalAccountDto> = emptyList(),
)

@Serializable
internal data class GatewayTokenInfoDto(
    @SerialName("UserIdentifier") val UserIdentifier: String = "",
    @SerialName("userIdentifier") val camelUserIdentifier: String = "",
    @SerialName("Identifier") val upperIdentifier: String = "",
    @SerialName("identifier") val lowerIdentifier: String = "",
) {
    fun resolvedUserIdentifier(): String? =
        listOf(UserIdentifier, camelUserIdentifier, upperIdentifier, lowerIdentifier)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
}

@Serializable
internal data class ApiMeAccountDto(
    @SerialName("Id") val id: Long? = null,
    @SerialName("UserId") val userId: Long? = null,
)

@Serializable
internal data class ApiMePayloadDto(
    @SerialName("Account") val account: ApiMeAccountDto? = null,
    @SerialName("User") val user: ApiMeAccountDto? = null,
)

@Serializable
internal data class ApiMeResponseDto(@SerialName("Me") val me: ApiMePayloadDto? = null)
