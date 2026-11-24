package dev.ii.tools.voice

import androidx.compose.runtime.Immutable
import org.json.JSONObject

@Immutable
data class VoiceChannel(val id: String, val name: String)

@Immutable
data class VoiceSelf(
    val id: String = "",
    val mute: Boolean = false,
    val deaf: Boolean = false,
    val mode: String = "",
    val pttShortcut: String = "",
    val video: Boolean = false,
    val canMode: Boolean = false,
    val canUserVoice: Boolean = false,
    val canVideo: Boolean = false,
)

@Immutable
data class VoiceUser(
    val id: String,
    val name: String,
    val avatar: String,
    val mute: Boolean,
    val deaf: Boolean,
    val volume: Int,
    val localMute: Boolean,
)

@Immutable
data class PrivacyUse(val kind: String, val app: String)

@Immutable
data class VoiceEvent(val serial: Long, val kind: String, val id: String, val name: String)

@Immutable
data class VoiceState(
    val channel: VoiceChannel?,
    val since: Long,
    val status: String,
    val backend: String,
    val self: VoiceSelf,
    val users: List<VoiceUser>,
    val privacy: List<PrivacyUse>,
)

@Immutable
data class VoiceSummary(
    val inCall: Boolean,
    val channel: String,
    val mute: Boolean,
    val deaf: Boolean,
)

internal object VoiceTopics {
    private var eventSeq = 0L

    fun state(m: JSONObject): VoiceState {
        val chObj = m.optJSONObject("channel")
        val chStr = if (chObj == null && m.has("channel") && !m.isNull("channel")) m.optString("channel") else ""
        val channel = when {
            chObj != null -> VoiceChannel(chObj.optString("id"), chObj.optString("name"))
            chStr.isNotEmpty() -> VoiceChannel("", chStr)
            else -> null
        }
        val selfObj = m.optJSONObject("self")
        val self = VoiceSelf(
            id = selfObj?.optString("id").orEmpty(),
            mute = selfObj?.optBoolean("mute") == true,
            deaf = selfObj?.optBoolean("deaf") == true,
            mode = selfObj?.optString("mode").orEmpty(),
            pttShortcut = selfObj?.optString("pttShortcut").orEmpty(),
            video = selfObj?.optBoolean("video") == true,
            canMode = selfObj?.optBoolean("canMode") == true,
            canUserVoice = selfObj?.optBoolean("canUserVoice") == true,
            canVideo = selfObj?.optBoolean("canVideo") == true,
        )
        val usersArr = m.optJSONArray("users")
        val users = buildList {
            if (usersArr != null) {
                for (i in 0 until usersArr.length()) {
                    val u = usersArr.optJSONObject(i) ?: continue
                    val id = u.optString("id")
                    if (id.isEmpty()) continue
                    add(
                        VoiceUser(
                            id = id,
                            name = u.optString("name").ifEmpty { "Unknown" },
                            avatar = u.optString("avatar"),
                            mute = u.optBoolean("mute"),
                            deaf = u.optBoolean("deaf"),
                            volume = u.optInt("volume", 100).coerceIn(0, 200),
                            localMute = u.optBoolean("localMute"),
                        ),
                    )
                }
            }
        }
        val privArr = m.optJSONArray("privacy")
        val privacy = buildList {
            if (privArr != null) {
                for (i in 0 until privArr.length()) {
                    val p = privArr.optJSONObject(i) ?: continue
                    val kind = p.optString("kind")
                    if (kind.isEmpty()) continue
                    add(PrivacyUse(kind = kind, app = p.optString("app")))
                }
            }
        }
        return VoiceState(
            channel = channel,
            since = m.optLong("since", 0L),
            status = m.optString("status"),
            backend = m.optString("backend"),
            self = self,
            users = users,
            privacy = privacy,
        )
    }

    fun speak(m: JSONObject): Set<String> {
        val arr = m.optJSONArray("ids") ?: return emptySet()
        return buildSet(arr.length()) {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }

    fun event(m: JSONObject): VoiceEvent? {
        val kind = m.optString("kind")
        val name = m.optString("name")
        if ((kind != "join" && kind != "leave") || name.isEmpty()) return null
        return VoiceEvent(serial = ++eventSeq, kind = kind, id = m.optString("id"), name = name)
    }

    fun summary(m: JSONObject) = VoiceSummary(
        inCall = m.optBoolean("inCall"),
        channel = m.optString("channel"),
        mute = m.optBoolean("mute"),
        deaf = m.optBoolean("deaf"),
    )
}
