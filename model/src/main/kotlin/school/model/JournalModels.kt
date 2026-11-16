package school.model

import kotlinx.serialization.Serializable

/** Placeholder domain models for `:model`; expanded in T-006 and T-007. */
@Serializable
data class Grade(val sourceId: String)

@Serializable
data class MessageHeader(val sourceId: String)

@Serializable
data class Message(val sourceId: String)
