package org.oip.opcua

import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType
import java.nio.file.Path
import java.security.KeyPair
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * One OPC UA client connection. The endpoint URL is what discovery calls (GetEndpoints).
 * [connectHost] and [connectPort] rewrite the chosen endpoint URL after discovery so a test
 * proxy can sit in front of a server that advertises its own address.
 *
 * Keep-alives are the client's. Eclipse Milo's session state machine reconnects on its own and
 * backs off in whole seconds (1s, then doubled, capped). This driver does not open a second
 * reconnect loop on top of that.
 *
 * [pkiDir] is the client's PKI root. Milo's file trust list keeps trusted certificates under
 * `trusted/certs` and issuers under `issuer/certs`. Certificates that fail path validation are
 * written under `rejected/certs`. A secure channel requires [pkiDir] unless
 * [trustAllServerCertificates] is set. That flag trusts every server certificate and is only for
 * tests that are not exercising authentication.
 */
data class OpcUaConnectionConfig(
    val name: String,
    val endpointUrl: String,
    val securityPolicy: OpcUaSecurityPolicy = OpcUaSecurityPolicy.NONE,
    val securityMode: OpcUaSecurityMode = OpcUaSecurityMode.NONE,
    val auth: OpcUaAuth = OpcUaAuth.Anonymous,
    val applicationName: String = "ERIC OPC UA Client",
    val applicationUri: String = "urn:oip:driver-opcua-client",
    val requestTimeoutMs: Long = 5_000,
    val sessionTimeoutMs: Long = 60_000,
    val keepAliveIntervalMs: Long = 5_000,
    val keepAliveTimeoutMs: Long = 5_000,
    val keepAliveFailuresAllowed: Int = 1,
    val connectHost: String? = null,
    val connectPort: Int = -1,
    val keyPair: KeyPair? = null,
    val certificate: X509Certificate? = null,
    val pkiDir: Path? = null,
    /**
     * Skip server-certificate authentication and accept any certificate. Loud on purpose: the
     * default is false, and a production connection must not set it.
     */
    val trustAllServerCertificates: Boolean = false,
    val commsLossQuality: QualityCode = QualityCode.Bad_NotConnected,
) {
    init {
        require(name.isNotBlank()) { "connection name is required" }
        require(endpointUrl.startsWith("opc.tcp://")) { "endpoint URL must be opc.tcp://" }
        require(requestTimeoutMs > 0 && sessionTimeoutMs > 0)
        require(keepAliveIntervalMs > 0 && keepAliveTimeoutMs > 0)
        require(keepAliveFailuresAllowed >= 1)
        require(
            commsLossQuality == QualityCode.Bad_NotConnected ||
                commsLossQuality == QualityCode.Uncertain_LastKnownValue ||
                commsLossQuality == QualityCode.Bad_NoCommunication,
        ) { "comms-loss quality must be a connection-loss code" }
        if (securityPolicy == OpcUaSecurityPolicy.NONE || securityMode == OpcUaSecurityMode.NONE) {
            require(securityPolicy == OpcUaSecurityPolicy.NONE && securityMode == OpcUaSecurityMode.NONE) {
                "SecurityPolicy.None requires MessageSecurityMode.None"
            }
        }
        if (!trustAllServerCertificates && securityPolicy != OpcUaSecurityPolicy.NONE) {
            require(pkiDir != null) {
                "pkiDir is required for $securityPolicy unless trustAllServerCertificates is set"
            }
        }
    }
}

enum class OpcUaSecurityPolicy(val uri: String) {
    NONE(SecurityPolicy.None.uri),
    BASIC256SHA256(SecurityPolicy.Basic256Sha256.uri),
    AES128_SHA256_RSAOAEP(SecurityPolicy.Aes128_Sha256_RsaOaep.uri),
    AES256_SHA256_RSAPSS(SecurityPolicy.Aes256_Sha256_RsaPss.uri),
}

enum class OpcUaSecurityMode {
    NONE, SIGN, SIGN_AND_ENCRYPT;

    fun toMilo(): MessageSecurityMode = when (this) {
        NONE -> MessageSecurityMode.None
        SIGN -> MessageSecurityMode.Sign
        SIGN_AND_ENCRYPT -> MessageSecurityMode.SignAndEncrypt
    }
}

sealed class OpcUaAuth {
    data object Anonymous : OpcUaAuth()
    data class Username(val username: String, val password: String) : OpcUaAuth()
    data class Certificate(val certificate: X509Certificate, val privateKey: PrivateKey) : OpcUaAuth()

    fun tokenType(): UserTokenType = when (this) {
        Anonymous -> UserTokenType.Anonymous
        is Username -> UserTokenType.UserName
        is Certificate -> UserTokenType.Certificate
    }
}

/** One endpoint returned by GetEndpoints. */
data class OpcUaEndpoint(
    val endpointUrl: String,
    val securityPolicyUri: String,
    val securityMode: String,
    val userTokenTypes: List<String>,
)
