package com.vietmediaf.cloudstream

import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Fshare API client — ported from vietmediaf/main.js.
 * Handles login, token management, folder browsing, and link resolution.
 */
object FshareApi {
    private const val API_BASE = "https://api.fshare.vn/api"
    private const val APP_KEY = "dMnqMMZMUnN5YpvKENaEhdQQ5jxDqddt"
    private const val FS_UA = "kodivietmediaf-K58W6U"

    // ── Session state (in-memory, repopulated on login) ──
    private var token: String? = null
    private var sessionId: String? = null
    private var email: String? = null
    private var password: String? = null

    val isLoggedIn: Boolean get() = token != null && sessionId != null

    // ── Data Classes ──

    data class LoginResponse(
        @JsonProperty("token") val token: String? = null,
        @JsonProperty("session_id") val sessionId: String? = null,
        @JsonProperty("msg") val msg: String? = null,
    )

    data class ResolveResponse(
        @JsonProperty("location") val location: String? = null,
        @JsonProperty("msg") val msg: String? = null,
    )

    data class FolderItem(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("linkcode") val linkcode: String? = null,
        @JsonProperty("type") val type: String? = null,  // "0" = folder, "1" = file
        @JsonProperty("ftype") val ftype: String? = null,
        @JsonProperty("size") val size: Long? = null,
        @JsonProperty("mimetype") val mimetype: String? = null,
    ) {
        fun isFolder(): Boolean = (type ?: ftype) == "0"

        fun isVideo(): Boolean {
            val n = name?.lowercase() ?: return false
            return VIDEO_EXTENSIONS.any { n.endsWith(it) }
        }

        fun effectiveLinkcode(): String = linkcode ?: id ?: ""
    }

    private val VIDEO_EXTENSIONS = listOf(
        ".mp4", ".mkv", ".avi", ".mov", ".wmv",
        ".flv", ".webm", ".m4v", ".ts", ".mpg", ".mpeg"
    )

    // ── Public Methods ──

    /**
     * Login to Fshare. Stores credentials in memory for auto-relogin.
     * Returns error message on failure, null on success.
     */
    suspend fun login(userEmail: String, userPassword: String): String? {
        return try {
            val res = app.post(
                "$API_BASE/user/login/",
                headers = mapOf(
                    "User-Agent" to FS_UA,
                    "Cache-Control" to "no-cache",
                ),
                data = mapOf(
                    "app_key" to APP_KEY,
                    "user_email" to userEmail,
                    "password" to userPassword,
                ),
            )

            val data = res.parsed<LoginResponse>()
            if (res.code == 200 && data.token != null && data.sessionId != null) {
                token = data.token
                sessionId = data.sessionId
                email = userEmail
                password = userPassword
                null // success
            } else {
                data.msg ?: "Email hoặc mật khẩu không đúng"
            }
        } catch (e: Exception) {
            "Lỗi kết nối: ${e.message}"
        }
    }

    /**
     * Clears the current session.
     */
    fun logout() {
        token = null
        sessionId = null
        email = null
        password = null
    }

    /**
     * Resolve a Fshare file linkcode to a direct download/stream URL.
     * Uses withRetry for automatic re-login on auth errors.
     */
    suspend fun resolve(linkcode: String): String? {
        return withRetry { tok, sid ->
            val res = app.post(
                "$API_BASE/session/download",
                headers = mapOf(
                    "User-Agent" to FS_UA,
                    "Cookie" to "session_id=$sid",
                ),
                data = mapOf(
                    "zipflag" to "0",
                    "url" to "https://www.fshare.vn/file/$linkcode",
                    "token" to tok,
                ),
            )
            ApiResult(res.code, res.parsed<ResolveResponse>().location)
        }
    }

    /**
     * List contents of a Fshare folder. Returns (folders, files).
     */
    suspend fun listFolder(linkcode: String): Pair<List<FolderItem>, List<FolderItem>>? {
        val rawItems = withRetryList(linkcode)  ?: return null

        val folders = mutableListOf<FolderItem>()
        val files = mutableListOf<FolderItem>()

        for (item in rawItems) {
            if (item.isFolder()) {
                folders.add(item)
            } else if (item.isVideo()) {
                files.add(item)
            }
        }

        return Pair(folders, files)
    }

    // ── Internal: withRetry ──
    // Port of main.js L605-640: auto re-login on 201/401/403

    private data class ApiResult<T>(val status: Int, val data: T?)

    private suspend fun <T> withRetry(
        maxRetries: Int = 2,
        operation: suspend (token: String, sessionId: String) -> ApiResult<T>
    ): T? {
        for (i in 0..maxRetries) {
            val tok = token ?: return null
            val sid = sessionId ?: return null

            val result = try {
                operation(tok, sid)
            } catch (e: Exception) {
                if (i < maxRetries) continue else return null
            }

            // Auth error → re-login and retry
            if (result.status in listOf(201, 401, 403)) {
                val em = email ?: return null
                val pw = password ?: return null
                val loginError = login(em, pw)
                if (loginError != null) return null
                continue
            }

            if (result.status == 200) {
                return result.data
            }

            // Server error → retry
            if (result.status >= 500 && i < maxRetries) {
                continue
            }

            return result.data
        }
        return null
    }

    /**
     * Folder listing with retry — returns raw list of FolderItem.
     */
    private suspend fun withRetryList(linkcode: String): List<FolderItem>? {
        for (i in 0..2) {
            val tok = token ?: return null
            val sid = sessionId ?: return null

            try {
                val res = app.post(
                    "$API_BASE/fileops/getFolderList",
                    headers = mapOf(
                        "User-Agent" to FS_UA,
                        "Cookie" to "session_id=$sid",
                    ),
                    data = mapOf(
                        "token" to tok,
                        "url" to "https://www.fshare.vn/folder/$linkcode",
                        "dirOnly" to "0",
                        "pageIndex" to "0",
                        "limit" to "99999",
                    ),
                )

                if (res.code in listOf(201, 401, 403)) {
                    val em = email ?: return null
                    val pw = password ?: return null
                    if (login(em, pw) != null) return null
                    continue
                }

                if (res.code != 200) {
                    if (i < 2) continue else return null
                }

                // Response can be a JSON array or { items: [...] }
                return try {
                    res.parsed<List<FolderItem>>()
                } catch (_: Exception) {
                    try {
                        val wrapper = res.parsed<FolderListWrapper>()
                        wrapper.items ?: emptyList()
                    } catch (_: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                if (i < 2) continue else return null
            }
        }
        return null
    }

    private data class FolderListWrapper(
        @JsonProperty("items") val items: List<FolderItem>? = null
    )
}
