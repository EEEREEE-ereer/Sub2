package me.leon

import me.leon.support.*
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object Parser {
    private val REG_SCHEMA_HASH = "(\\w+)://([^ #]+)(?:#([^#]+)?)?".toRegex()
    private val REG_SS = "([^:]+):([^@]+)@([^:]+):(\\d{1,5})".toRegex()
    private val REG_SSR_PARAM = "([^/]+)/\\?(.+)".toRegex()
    private val REG_TROJAN = "([^@]+)@([^:]+):(\\d{1,5})(?:\\?(.+))?".toRegex()

    init {

        System.setProperty("jdk.tls.client.protocols", "TLSv1.2")
        //信任过期证书
        val trustAllCerts: Array<TrustManager> = arrayOf(object : X509TrustManager {
            @Throws(CertificateException::class)
            override fun checkClientTrusted(chain: Array<X509Certificate?>?, authType: String?) {
            }

            @Throws(CertificateException::class)
            override fun checkServerTrusted(chain: Array<X509Certificate?>?, authType: String?) {
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return arrayOf()
            }
        })
        // Install the all-trusting trust manager
        try {
            val sc: SSLContext = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    var debug = false

    fun parse(uri: String): Sub? =
        when (uri.substringBefore(':')) {
            "vmess" -> parseV2ray(uri.trim())
            "ss" -> parseSs(uri.trim())
            "ssr" -> parseSsr(uri.trim())
            "trojan" -> parseTrojan(uri.trim())
            else -> NoSub
        }

    fun parseV2ray(uri: String): V2ray? {
        "parseV2ray ".debug(uri)
        try {
            REG_SCHEMA_HASH.matchEntire(uri)?.run {
                return groupValues[2].b64SafeDecode()
                    .also { "parseV2ray base64 decode: ".debug(it) }
                    .fromJson<V2ray>()
            } ?: return null
        } catch (e: Exception) {
            e.printStackTrace()
            println(uri)
            "parseV2ray err".debug(uri)
            return null
        }

    }

    fun parseSs(uri: String): SS? {
        "parseSs ".debug(uri)
        REG_SCHEMA_HASH.matchEntire(uri)?.run {
            val remark = groupValues[3].urlDecode()
            "parseSs match".debug(groupValues[2])
            var decoded = groupValues[2].takeUnless { it.contains("@") }?.b64Decode()
            //兼容异常
                ?: with(groupValues[2]) {
                    "${substringBefore('@').b64Decode()}${substring(indexOf('@'))}"
                        .also { "parseSs b64 format correct".debug("___$it") }
                }
            decoded.also {
                "parseSs b64 decode".debug(it)
                REG_SS.matchEntire(it)?.run {
                    "parseSs ss match".debug(this.groupValues.toString())
                    return SS(groupValues[1], groupValues[2], groupValues[3], groupValues[4]).apply {
                        this.remark = remark
                    }
                }
            }
        }
        "parseSs failed".debug(uri)
        return null
    }

    fun parseSsr(uri: String): SSR? {
        "parseSsr ".debug(uri)
        REG_SCHEMA_HASH.matchEntire(uri)?.run {
            groupValues[2].b64SafeDecode().split(":").run {
                "parseSsr query".debug(this[5])
                REG_SSR_PARAM.matchEntire(this[5])?.let {
                    "parseSsr query match".debug(it.groupValues[2])
                    val q = it.groupValues[2].queryParamMapB64()
                    "parseSsr query maps".debug(q.toString())
                    return SSR(
                        this[0], this[1], this[2], this[3], this[4],
                        it.groupValues[1].b64SafeDecode(),
                        q["obfsparam"] ?: "",
                        q["protoparam"] ?: "",
                    ).apply {
                        remarks = q["remarks"] ?: ""
                        group = q["group"] ?: ""
                    }
                }
            }
        }
        "parseSsr err not match".debug(uri)
        return null
    }

    fun parseTrojan(uri: String): Trojan? {
        "parseTrojan".debug(uri)
        REG_SCHEMA_HASH.matchEntire(uri)?.run {
            val remark = groupValues[3].urlDecode()
            "parseTrojan remark".debug(remark)
            groupValues[2].also {
                "parseTrojan data".debug(it)
                REG_TROJAN.matchEntire(it)?.run {
                    return Trojan(groupValues[1], groupValues[2], groupValues[3]).apply {
                        this.remark = remark
                        query = groupValues[4]
                    }
                }
            }
        }
        "parseTrojan ".debug("failed")
        return null
    }

    private fun parseFromFileSub(path: String): LinkedHashSet<Sub> {
        "parseFromSub Local".debug(path)
        val data = path.readText()
            .b64SafeDecode()
        return if (data.contains("proxies:"))
            (Yaml(Constructor(Clash::class.java)).load(data.replace("!<[^>]+>".toRegex(), "")) as Clash).proxies
                .asSequence()
                .mapNotNull(Node::node)
                .fold(linkedSetOf()) { acc, sub -> acc.also { acc.add(sub) } }
        else
            data
//                .also { println(it) }
                .split("\r\n|\n".toRegex())
                .asSequence()
                .filter { it.isNotEmpty() }
                .mapNotNull { Pair(it, parse(it)) }
                .filterNot { it.second is NoSub }
                .fold(linkedSetOf()) { acc, sub ->
                    sub.second?.let { acc.add(it) } ?: kotlin.run {
                        println("parseFromFileSub failed: $sub")
                    }
                    acc
                }
    }

    private fun parseFromNetwork(url: String): LinkedHashSet<Sub> {
        "parseFromNetwork".debug(url)
        val data = url.readFromNet()
            .b64SafeDecode()

        return try {
            if (data.contains("proxies:"))
            //移除yaml中的标签
                (Yaml(Constructor(Clash::class.java)).load(
                    data.replace("!<[^>]+>".toRegex(), "").also {
                        it.debug()
                    }) as Clash).proxies
                    .asSequence()
                    .mapNotNull(Node::node)
                    .filterNot { it is NoSub }
                    .fold(linkedSetOf()) { acc, sub -> acc.also { acc.add(sub) } }
            else
                data.also { "parseFromNetwork".debug(it) }
                    .split("\r\n|\n".toRegex())
                    .asSequence()
                    .filter { it.isNotEmpty() }
                    .mapNotNull { parse(it.replace("/#", "#")) }
                    .filterNot { it is NoSub }
                    .fold(linkedSetOf()) { acc, sub -> acc.also { acc.add(sub) } }
        } catch (ex: Exception) {
            println("failed______ $url")
            ex.printStackTrace()
            linkedSetOf()
        }
    }

    fun parseFromSub(uri: String) =
        when {
            uri.startsWith("http") -> parseFromNetwork(uri)
            uri.startsWith("/") -> parseFromFileSub(uri)
            else -> parseFromFileSub(uri)
        }

    fun String.debug(extra: String = "") {
        if (debug) println("$this $extra")
    }
}