package com.jarvis.assistant.ai

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException

internal class InvalidProviderEndpoint : IOException("Use HTTPS for remote providers, or a valid local HTTP address.")

/** Where an AI endpoint lives: on this phone (loopback), on the local network, or in the cloud. */
object Endpoints {
    fun host(url: String): String? = parse(url)?.host

    /** True for a server running on this phone, e.g. llama-server in Termux. */
    fun isLoopback(url: String): Boolean = parse(url)?.let(::isLoopback) == true

    /** True for an explicitly configured local address or mDNS name. */
    fun isLocal(url: String): Boolean = parse(url)?.let(::isLocal) == true

    internal fun isLoopback(url: HttpUrl): Boolean = isLoopbackHost(url.host)
    internal fun isLocal(url: HttpUrl): Boolean = isLocalHost(url.host)

    internal fun requireHttpUrl(url: String): HttpUrl = parse(url)?.takeIf {
        it.isHttps || isLocal(it)
    } ?: throw InvalidProviderEndpoint()

    /** Local traffic should not go through a proxy or DNS-resolve to a public address. */
    internal fun OkHttpClient.Builder.configureLocalTransport() = proxy(Proxy.NO_PROXY).dns(LOCAL_DNS)

    private fun parse(url: String): HttpUrl? {
        val value = url.trim()
        if (value.isEmpty()) return null
        val explicit = if ("://" in value) value else "http://$value"
        val uri = runCatching { URI(explicit) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.port > 65535) return null
        val parsed = explicit.trimEnd('/').toHttpUrlOrNull() ?: return null
        // Reject ambiguous authorities that the URI and HTTP parsers interpret differently.
        return parsed.takeIf { it.host == host.removeSurrounding("[", "]").lowercase() || ':' in host }
    }

    private fun isLoopbackHost(host: String): Boolean =
        host == "localhost" || host == "::1" || ipv4(host)?.get(0) == 127

    private fun isLocalHost(host: String): Boolean =
        isLoopbackHost(host) || host.endsWith(".local") ||
            (':' in host && (host.startsWith("fc") || host.startsWith("fd"))) ||
            ipv4(host)?.let(::isPrivateIpv4) == true

    private fun ipv4(host: String): List<Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        return parts.map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } ?: return null }
    }

    private fun isPrivateIpv4(octets: List<Int>): Boolean =
        octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && octets[1] in 16..31)

    private fun InetAddress.isLocalAddress(): Boolean = when (this) {
        is Inet4Address -> isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress
        is Inet6Address -> isLoopbackAddress || isLinkLocalAddress || (address[0].toInt() and 0xfe) == 0xfc
        else -> false
    }

    private val LOCAL_DNS = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            val local = addresses.filter { if (isLoopbackHost(hostname)) it.isLoopbackAddress else it.isLocalAddress() }
            return local.ifEmpty { throw UnknownHostException("The local endpoint did not resolve to a local address.") }
        }
    }

    /** `http://127.0.0.1:8080/v1` -> `http://127.0.0.1:8080`. */
    fun origin(url: String): String? {
        val u = parse(url) ?: return null
        return u.newBuilder().encodedPath("/").build().toString().removeSuffix("/")
    }
}
