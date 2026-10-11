package com.cyotun.jpq.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.math.BigInteger
import net.i2p.crypto.eddsa.*
import net.i2p.crypto.eddsa.spec.*

/** Deliberately narrow parser: signed protocol has objects, strings and integers only. */
private class ReleaseJson(private val text: String) {
    private var i=0
    fun parse(): Map<String,Any> { val value=obj(0); ws(); require(i==text.length); return value }
    private fun ws() { while(i<text.length && text[i] in " \t\r\n") i++ }
    private fun expect(c: Char) { ws(); require(i<text.length && text[i++]==c) }
    private fun obj(depth: Int): Map<String,Any> {
        require(depth<=2); expect('{'); val map=linkedMapOf<String,Any>(); ws()
        if(i<text.length && text[i]=='}') { i++; return map }
        while(true) {
            val key=string(); require(key !in map); expect(':'); ws(); require(i<text.length)
            map[key]=when(text[i]) { '{' -> obj(depth+1); '"' -> string(); else -> integer() }
            ws(); require(i<text.length); val c=text[i++]; if(c=='}') break; require(c==',')
        }; return map
    }
    private fun integer(): Long {
        val start=i
        while(i<text.length && text[i] in '0'..'9') i++
        val s=text.substring(start,i); require(s.isNotEmpty() && (s=="0" || s[0]!='0'))
        return s.toLong()
    }
    private fun string(): String {
        expect('"'); val s=StringBuilder()
        while(i<text.length) {
            val c=text[i++]
            if(c=='"') {
                val value=s.toString(); var j=0
                while(j<value.length) { val ch=value[j++]; if(ch.isHighSurrogate()) require(j<value.length && value[j++].isLowSurrogate()) else require(!ch.isLowSurrogate()) }
                return value
            }
            require(c.code>=32)
            if(c!='\\') { s.append(c); continue }
            require(i<text.length)
            s.append(when(val e=text[i++]) {
                '"','\\','/' -> e; 'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                'u' -> { require(i+4<=text.length); val ch=text.substring(i,i+4).toInt(16).toChar(); i+=4; ch }
                else -> error("Invalid string escape")
            })
        }; error("Unterminated string")
    }
}
data class ReleaseManifest(
    val packageName: String,val versionCode: Long,val versionName: String,val minSdk: Long,
    val publishedAt: String,val notes: String,val key: String,val sizeBytes: Long,val sha256: String,val signature: ByteArray,
) {
    fun payload(): ByteArray {
        val out=ByteArrayOutputStream(); out.write("JPQ-RELEASE-V1\n".toByteArray())
        listOf("1",packageName,versionCode.toString(),versionName,minSdk.toString(),publishedAt,notes,key,sizeBytes.toString(),sha256).forEach {
            val b=it.toByteArray(Charsets.UTF_8); out.write("${b.size}:".toByteArray()); out.write(b)
        }; return out.toByteArray()
    }
    fun verify(publicKey: ByteArray): Boolean = runCatching {
        require(publicKey.size==32 && signature.size==64)
        val order=BigInteger("1000000000000000000000000000000014def9dea2f79cd65812631a5cf5d3ed",16)
        require(BigInteger(1,signature.copyOfRange(32,64).reversedArray())<order)
        val spec=EdDSAPublicKeySpec(publicKey,EdDSANamedCurveTable.getByName("Ed25519"))
        EdDSAEngine().apply { initVerify(EdDSAPublicKey(spec)); update(payload()) }.verify(signature)
    }.getOrDefault(false)
    companion object {
        fun parse(text: String): ReleaseManifest {
            require(text.toByteArray().size<=32768)
            val j=ReleaseJson(text).parse()
            require(j.keys==setOf("schemaVersion","packageName","versionCode","versionName","minSdk","publishedAt","notes","apk","signature"))
            require(j["schemaVersion"]==1L)
            @Suppress("UNCHECKED_CAST") val a=j["apk"] as Map<String,Any>
            require(a.keys==setOf("key","sizeBytes","sha256"))
            fun s(k: String)=j[k] as String
            val version=j["versionCode"] as Long; val sdk=j["minSdk"] as Long
            val size=a["sizeBytes"] as Long; val sha=a["sha256"] as String; val key=a["key"] as String
            require(s("packageName")=="com.cyotun.jpq" && version>0 && sdk in 21..100 && size in 1..1073741824)
            require(s("versionName").length in 1..80 && s("notes").length<=4000)
            require(Regex("[a-f0-9]{64}").matches(sha) && key=="releases/$version/jpq-$sha.apk")
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T.*Z").matches(s("publishedAt")))
            java.time.Instant.parse(s("publishedAt"))
            require(Regex("[A-Za-z0-9+/]{86}==").matches(s("signature")))
            return ReleaseManifest(s("packageName"),version,s("versionName"),sdk,s("publishedAt"),s("notes"),key,size,sha,Base64.getDecoder().decode(s("signature")))
        }
    }
}
