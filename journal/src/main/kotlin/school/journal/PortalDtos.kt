package school.journal

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
internal data class GatewayTokenInfoDto(val UserIdentifier: String = "")
