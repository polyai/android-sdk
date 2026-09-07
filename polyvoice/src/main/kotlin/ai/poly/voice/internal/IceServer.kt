// Copyright PolyAI Limited

package ai.poly.voice.internal

import org.json.JSONArray

/**
 * A STUN/TURN server, decoupled from `org.webrtc.PeerConnection.IceServer` so the port layer and
 * tests never touch the native type. The `AndroidWebRtcPeer` adapter maps these to the libwebrtc form.
 */
internal data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
) {
    internal companion object {
        /** The public-STUN fallback used when the gateway's ice-servers endpoint is unavailable. */
        val DEFAULT: List<IceServer> = listOf(IceServer(urls = listOf("stun:stun.l.google.com:19302")))

        /**
         * STUN fallback for the `webrtc-bridge` path. Media terminates at Cloudflare's edge there,
         * so Cloudflare's own STUN endpoint is the supported one — never the gateway's servers.
         * Used only until the bridge returns an authoritative `iceServers` list in its provision
         * response (RUN-1780), which will carry TURN for calls that need a relay.
         */
        val BRIDGE_DEFAULT: List<IceServer> = listOf(IceServer(urls = listOf("stun:stun.cloudflare.com:3478")))

        /**
         * Parse an `iceServers` array. Shared by the gateway's dedicated endpoint and the bridge,
         * which carries the same objects inline in its provision response.
         */
        fun parseList(arr: JSONArray?): List<IceServer> {
            if (arr == null) return emptyList()
            val out = ArrayList<IceServer>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val urls = when {
                    obj.optJSONArray("urls") != null -> {
                        val u = obj.getJSONArray("urls")
                        (0 until u.length()).mapNotNull { u.optString(it).takeIf { s -> s.isNotEmpty() } }
                    }
                    obj.optString("urls").isNotEmpty() -> listOf(obj.getString("urls"))
                    else -> emptyList()
                }
                if (urls.isEmpty()) continue
                out += IceServer(
                    urls = urls,
                    username = obj.optString("username").takeIf { it.isNotEmpty() },
                    credential = obj.optString("credential").takeIf { it.isNotEmpty() },
                )
            }
            return out
        }
    }
}
