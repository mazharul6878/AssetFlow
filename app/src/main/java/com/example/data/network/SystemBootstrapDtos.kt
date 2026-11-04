package com.example.data.network

import org.json.JSONArray
import org.json.JSONObject

data class SystemBootstrapResultDto(
    val status: String,
    val role: String,
    val uid: String,
    val email: String,
    val message: String
) {
    companion object {
        fun fromJson(json: JSONObject): SystemBootstrapResultDto {
            return SystemBootstrapResultDto(
                status = json.optString("status", ""),
                role = json.optString("role", ""),
                uid = json.optString("uid", ""),
                email = json.optString("email", ""),
                message = json.optString("message", "")
            )
        }
    }
}

data class UserSessionProfileDto(
    val uid: String,
    val email: String,
    val displayName: String,
    val fullName: String,
    val nickname: String,
    val globalRole: String,
    val accountStatus: String
) {
    companion object {
        fun fromJson(json: JSONObject): UserSessionProfileDto {
            return UserSessionProfileDto(
                uid = json.optString("uid", ""),
                email = json.optString("email", ""),
                displayName = json.optString("displayName", ""),
                fullName = json.optString("fullName", ""),
                nickname = json.optString("nickname", ""),
                globalRole = json.optString("globalRole", "Auditor"),
                accountStatus = json.optString("accountStatus", "Active")
            )
        }
    }
}

data class ClientMembershipDto(
    val clientId: String,
    val role: String,
    val accountStatus: String,
    val hasReviewAuthority: Boolean,
    val canPublish: Boolean,
    val canSyncDrive: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject): ClientMembershipDto {
            return ClientMembershipDto(
                clientId = json.optString("clientId", ""),
                role = json.optString("role", "Auditor"),
                accountStatus = json.optString("accountStatus", "Active"),
                hasReviewAuthority = json.optBoolean("hasReviewAuthority", false),
                canPublish = json.optBoolean("canPublish", false),
                canSyncDrive = json.optBoolean("canSyncDrive", false)
            )
        }
    }
}

data class UserSessionResultDto(
    val status: String,
    val user: UserSessionProfileDto?,
    val memberships: List<ClientMembershipDto>,
    val message: String
) {
    companion object {
        fun fromJson(json: JSONObject): UserSessionResultDto {
            val status = json.optString("status", "UNPROVISIONED")
            val userObj = json.optJSONObject("user")
            val user = userObj?.let { UserSessionProfileDto.fromJson(it) }
            val membershipsArr = json.optJSONArray("memberships") ?: JSONArray()
            val memberships = mutableListOf<ClientMembershipDto>()
            for (i in 0 until membershipsArr.length()) {
                val m = membershipsArr.optJSONObject(i)
                if (m != null) {
                    memberships.add(ClientMembershipDto.fromJson(m))
                }
            }
            return UserSessionResultDto(
                status = status,
                user = user,
                memberships = memberships,
                message = json.optString("message", "")
            )
        }
    }
}
