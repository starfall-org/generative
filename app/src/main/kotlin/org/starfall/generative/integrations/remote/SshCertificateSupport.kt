package org.starfall.generative.integrations.remote

import com.jcraft.jsch.JSch
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64

/** Accepts an OpenSSH *user* certificate (.pub), not an X.509 TLS certificate or plain SSH public key. */
fun normalizeOpenSshUserCertificate(text: String): String {
    val parts = text.trim().split(Regex("\\s+"), limit = 3)
    val algorithm = parts.firstOrNull().orEmpty()
    require(parts.size >= 2 && algorithm.endsWith("-cert-v01@openssh.com")) {
        "Select an OpenSSH user certificate (*-cert.pub), not a private key or TLS certificate."
    }
    val encoded = parts[1]
    require(encoded.length <= 48_000) { "SSH certificate file is too large." }
    val blob = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
        ?: throw IllegalArgumentException("SSH certificate contains invalid Base64 data.")
    require(blob.size in 8..32_768) { "SSH certificate has an invalid size." }
    val typeLength = ByteBuffer.wrap(blob, 0, 4).int
    require(typeLength in 1..128 && blob.size >= typeLength + 4 &&
        String(blob, 4, typeLength, Charsets.US_ASCII) == algorithm
    ) { "SSH certificate key type does not match its content." }
    return "$algorithm ${Base64.getEncoder().encodeToString(blob)}"
}

/** Explicit certificate bytes are passed to JSch; no temporary files or persisted SAF URI are needed. */
internal fun addMachineSshIdentity(jsch: JSch, identity: File, passphrase: String, certificate: String) {
    val passphraseBytes = passphrase.takeIf(String::isNotBlank)?.toByteArray(Charsets.UTF_8)
    if (certificate.isBlank()) {
        if (passphraseBytes == null) jsch.addIdentity(identity.absolutePath)
        else jsch.addIdentity(identity.absolutePath, passphraseBytes)
    } else {
        val publicCertificate = normalizeOpenSshUserCertificate(certificate).toByteArray(Charsets.US_ASCII)
        jsch.addIdentity(identity.name, identity.readBytes(), publicCertificate, passphraseBytes)
    }
}
