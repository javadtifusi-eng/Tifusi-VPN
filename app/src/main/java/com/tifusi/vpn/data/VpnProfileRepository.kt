package com.tifusi.vpn.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tifusi.vpn.vpn.Ikev2AuthType
import com.tifusi.vpn.vpn.VpnProfile
import com.tifusi.vpn.vpn.VpnProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "tifusi_vpn_profiles")

class VpnProfileRepository(private val context: Context) {

    private val profilesKey = stringPreferencesKey("profiles")
    private val selectedProfileIdKey = stringPreferencesKey("selected_profile_id")
    private val subscriptionUrlKey = stringPreferencesKey("subscription_url")
    private val subscriptionInfoKey = stringPreferencesKey("subscription_info")

    val subscriptionUrl: Flow<String?> = context.dataStore.data.map { it[subscriptionUrlKey] }

    /** Days and data left on the account, as last reported by the panel. */
    val subscriptionInfo: Flow<SubscriptionInfo?> =
        context.dataStore.data.map { prefs -> prefs[subscriptionInfoKey]?.let(SubscriptionInfo::fromJson) }

    val profiles: Flow<List<VpnProfile>> = context.dataStore.data.map { prefs ->
        prefs[profilesKey]?.let { decodeProfiles(it) } ?: emptyList()
    }

    val selectedProfileId: Flow<String?> = context.dataStore.data.map { it[selectedProfileIdKey] }

    suspend fun saveProfile(profile: VpnProfile) {
        context.dataStore.edit { prefs ->
            val current = prefs[profilesKey]?.let { decodeProfiles(it) } ?: emptyList()
            val updated = current.filterNot { it.id == profile.id } + profile
            prefs[profilesKey] = encodeProfiles(updated)
        }
    }

    suspend fun deleteProfile(profileId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[profilesKey]?.let { decodeProfiles(it) } ?: emptyList()
            prefs[profilesKey] = encodeProfiles(current.filterNot { it.id == profileId })
        }
    }

    suspend fun setSelectedProfile(profileId: String) {
        context.dataStore.edit { prefs -> prefs[selectedProfileIdKey] = profileId }
    }

    /** Forgets the subscription: its servers, link and usage; manual profiles stay. */
    suspend fun removeSubscription() {
        context.dataStore.edit { prefs ->
            val current = prefs[profilesKey]?.let { decodeProfiles(it) } ?: emptyList()
            prefs[profilesKey] = encodeProfiles(current.filterNot { it.id.startsWith(SubscriptionClient.ID_PREFIX) })
            if (prefs[selectedProfileIdKey]?.startsWith(SubscriptionClient.ID_PREFIX) == true) prefs.remove(selectedProfileIdKey)
            prefs.remove(subscriptionUrlKey)
            prefs.remove(subscriptionInfoKey)
        }
    }

    /** Swaps in a subscription's current servers, keeping manually added profiles untouched. */
    suspend fun replaceSubscriptionProfiles(url: String, result: SubscriptionResult) {
        context.dataStore.edit { prefs ->
            val current = prefs[profilesKey]?.let { decodeProfiles(it) } ?: emptyList()
            val manual = current.filterNot { it.id.startsWith(SubscriptionClient.ID_PREFIX) }
            // Another panel's page supplies IKEv2 separately; a refresh that could not read it
            // (a slow link, a timeout) keeps the IKEv2 already saved instead of dropping it.
            val keptIkev2 = if (result.profiles.none { it.protocol == VpnProtocol.IKEV2 }) {
                current.filter { it.id.startsWith(SubscriptionClient.ID_PREFIX) && it.protocol == VpnProtocol.IKEV2 }
            } else {
                emptyList()
            }
            prefs[profilesKey] = encodeProfiles(manual + keptIkev2 + result.profiles)
            prefs[subscriptionUrlKey] = url
            prefs[subscriptionInfoKey] = result.info.toJson()
        }
    }

    suspend fun getProfile(profileId: String): VpnProfile? =
        profiles.first().find { it.id == profileId }

    private fun encodeProfiles(profiles: List<VpnProfile>): String {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun decodeProfiles(raw: String): List<VpnProfile> {
        val array = JSONArray(raw)
        // One profile at a time, so a single entry this build cannot read costs that entry and
        // nothing else. It matters on update: WireGuard, L2TP and PPTP were removed, and a saved
        // profile of one of those used to throw here and take every other profile down with it —
        // a working IKEv2 server included.
        return (0 until array.length()).mapNotNull { i -> array.getJSONObject(i).toVpnProfileOrNull() }
    }
}

fun VpnProfile.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("protocol", protocol.name)
    put("serverAddress", serverAddress)
    put("countryName", countryName)
    put("countryFlagEmoji", countryFlagEmoji)
    put("ikev2AuthType", ikev2AuthType.name)
    put("remoteIdentifier", remoteIdentifier)
    put("localIdentifier", localIdentifier)
    put("presharedKey", presharedKey)
    put("serverRootCaCertPem", serverRootCaCertPem)
    put("userCertPem", userCertPem)
    put("userPrivateKeyPem", userPrivateKeyPem)
    put("pkcs12Base64", pkcs12Base64)
    put("pkcs12Password", pkcs12Password)
    put("username", username)
    put("password", password)
    put("vlessLink", vlessLink)
    put("hysteria2Link", hysteria2Link)
}

/** Null when this build has no protocol of that name any more, instead of throwing. */
fun JSONObject.toVpnProfileOrNull(): VpnProfile? {
    val protocol = VpnProtocol.entries.firstOrNull { it.name == optString("protocol") } ?: return null
    return runCatching { toVpnProfile(protocol) }.getOrNull()
}

fun JSONObject.toVpnProfile(protocol: VpnProtocol = VpnProtocol.valueOf(getString("protocol"))): VpnProfile = VpnProfile(
    id = getString("id"),
    name = getString("name"),
    protocol = protocol,
    serverAddress = getString("serverAddress"),
    countryName = optStringOrNull("countryName"),
    countryFlagEmoji = optStringOrNull("countryFlagEmoji"),
    ikev2AuthType = optStringOrNull("ikev2AuthType")?.let { Ikev2AuthType.valueOf(it) } ?: Ikev2AuthType.PSK,
    remoteIdentifier = optStringOrNull("remoteIdentifier"),
    localIdentifier = optStringOrNull("localIdentifier"),
    presharedKey = optStringOrNull("presharedKey"),
    serverRootCaCertPem = optStringOrNull("serverRootCaCertPem"),
    userCertPem = optStringOrNull("userCertPem"),
    userPrivateKeyPem = optStringOrNull("userPrivateKeyPem"),
    pkcs12Base64 = optStringOrNull("pkcs12Base64"),
    pkcs12Password = optStringOrNull("pkcs12Password"),
    username = optStringOrNull("username"),
    password = optStringOrNull("password"),
    // Absent in profiles saved before VLESS existed.
    vlessLink = optStringOrNull("vlessLink"),
    hysteria2Link = optStringOrNull("hysteria2Link"),
)

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null
