package com.cyotun.jpq.core

import java.security.*
import kotlin.test.*
import java.util.Base64

class ReleaseManifestTest {
    private fun example(sig: String)="""{"schemaVersion":1,"packageName":"com.cyotun.jpq","versionCode":2,"versionName":"测试:2","minSdk":28,"publishedAt":"2026-10-11T00:00:00Z","notes":"中文\n更新😀","apk":{"key":"releases/2/jpq-${"a".repeat(64)}.apk","sizeBytes":123,"sha256":"${"a".repeat(64)}"},"signature":"$sig"}"""
    @Test fun independentJdkSignerAndNonAsciiPayload() {
        val key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val raw=key.public.encoded.takeLast(32).toByteArray()
        val doc=ReleaseManifest.parse(example(Base64.getEncoder().encodeToString(ByteArray(64))))
        val sig=Signature.getInstance("Ed25519").apply { initSign(key.private); update(doc.payload()) }.sign()
        val signed=ReleaseManifest.parse(example(Base64.getEncoder().encodeToString(sig)))
        assertTrue(signed.verify(raw)); assertFalse(signed.copy(notes=signed.notes+"x").verify(raw))
        assertFalse(signed.verify(ByteArray(32)))
    }
    @Test fun rejectsDuplicateAndExtraAndNonIntegerFields() {
        val raw=example(Base64.getEncoder().encodeToString(ByteArray(64)))
        assertFails { ReleaseManifest.parse(raw.replace("\"versionCode\":2","\"versionCode\":1,\"versionCode\":2")) }
        assertFails { ReleaseManifest.parse(raw.replace("\"minSdk\":28","\"minSdk\":28.0")) }
        assertFails { ReleaseManifest.parse(raw.replace("\"sizeBytes\":123","\"sizeBytes\":0123")) }
        assertFails { ReleaseManifest.parse(raw.replace("\"schemaVersion\":1","\"extra\":1,\"schemaVersion\":1")) }
    }
}
