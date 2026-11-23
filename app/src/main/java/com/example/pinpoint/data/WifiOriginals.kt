package com.example.pinpoint.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The network-bound transport. Production binds sockets and DNS to ONE actual unmetered Wi-Fi network; a test double can
 *  model a resumable-upload server, but every URL, host, port and token rule below runs in front of it on both paths. */
interface OriginalLink {
    /** Throws IOException when the pinned network is no longer an unmetered Wi-Fi network. */
    fun ensure()
    suspend fun execute(request: Request): Response
}

/** Sockets and DNS stay on one actual Wi-Fi network, including during handoff.
 * Losing that network fails the call; OkHttp cannot silently use cellular. */
internal class WifiLink(context: Context): OriginalLink {
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    private val network: Network=connectivity.allNetworks.firstOrNull { wifi(it) }
        ?: throw IOException("Waiting for unmetered Wi-Fi to transfer the original photo.")
    private val http=OkHttpClient.Builder().socketFactory(network.socketFactory)
        .dns(object: Dns { override fun lookup(hostname: String)=network.getAllByName(hostname).toList() })
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS)
        .callTimeout(90,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private fun wifi(candidate: Network): Boolean = connectivity.getNetworkCapabilities(candidate)?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }==true
    override fun ensure() { if(!wifi(network)) throw IOException("Wi-Fi changed. The original will resume on Wi-Fi.") }
    override suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call=http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call,response: Response) {
                if(continuation.isActive) continuation.resume(response) { _,value,_ -> value.close() } else response.close()
            }
        })
    }
}

class WifiOriginals private constructor(private val backend: SupabaseBackend,private val project: HttpUrl,private val link: OriginalLink) {
    constructor(context: Context, backend: SupabaseBackend): this(backend,backend.url.toHttpUrl(),WifiLink(context))
    private val uploadHost=project.host.removeSuffix(".supabase.co")+".storage.supabase.co"
    private fun ensureWifi() = link.ensure()

    private suspend fun call(url: HttpUrl,method: String,owner: String,headers: Map<String,String> = emptyMap(),body: RequestBody?=null): Response {
        ensureWifi()
        require(url.isHttps && url.host in setOf(project.host,uploadHost) && url.port==443) { "Unexpected upload server." }
        var token=backend.auth.accessToken(owner)
        for(attempt in 0..1) {
            val request=Request.Builder().url(url).header("apikey",backend.publishableKey)
                .header("Authorization","Bearer $token").apply { headers.forEach { (name,value)->header(name,value) } }
                .method(method,body).build()
            val response=link.execute(request)
            // A token the server just refused (e.g. device clock skew) is refreshed once and the same request repeated:
            // a 401 is rejected before execution, so this is safe for POST and PATCH too (same rule as SupabaseBackend).
            if(response.code==401 && attempt==0) { response.close(); token=backend.auth.accessToken(owner,token); continue }
            return response
        }
        error("unreachable")
    }

    suspend fun upload(item: QueuedReport,onProgress: (Long)->Unit = {},saveSession: (String?)->Unit) = withContext(Dispatchers.IO) {
        val file=File(requireNotNull(item.originalFile))
        check(file.isFile && file.length() in 1..10485760) { "Saved original is missing or exceeds 10 MB." }
        val path="${item.ownerId}/${item.id}/original"
        var session=item.uploadUrl?.toHttpUrl()
        var offset=0L
        if(session!=null) {
            call(session,"HEAD",item.ownerId,mapOf("Tus-Resumable" to "1.0.0")).use { response ->
                when(response.code) {
                    200,204 -> offset=response.header("Upload-Offset")?.toLongOrNull() ?: throw IOException("Missing resumable upload offset.")
                    404,410 -> {session=null;saveSession(null)}
                    else -> fail(response)
                }
            }
        }
        if(session==null) {
            // A completed upload may have lost its final acknowledgment. Verify
            // the immutable object rather than replacing it or uploading twice.
            val existing=(project.toString().trimEnd('/')+"/storage/v1/object/authenticated/report-originals/$path").toHttpUrl()
            var complete=false
            call(existing,"GET",item.ownerId).use { response ->
                if(response.isSuccessful) {
                    check(response.body?.bytes()?.contentEquals(file.readBytes())==true) { "An original with different content already exists." }
                    complete=true
                } else if(response.code !in listOf(400,404)) fail(response)
            }
            if(complete) return@withContext
            fun metadata(value: String)=Base64.encodeToString(value.toByteArray(),Base64.NO_WRAP)
            val endpoint="https://$uploadHost/storage/v1/upload/resumable".toHttpUrl()
            call(endpoint,"POST",item.ownerId,mapOf("Tus-Resumable" to "1.0.0","Upload-Length" to file.length().toString(),
                "Upload-Metadata" to "bucketName ${metadata("report-originals")},objectName ${metadata(path)},contentType ${metadata(requireNotNull(item.originalMime))},cacheControl ${metadata("3600")}"),
                ByteArray(0).toRequestBody()).use { response ->
                if(response.code!=201) fail(response)
                session=endpoint.resolve(response.header("Location") ?: throw IOException("Missing upload session."))
                val valid=requireNotNull(session)
                require(valid.isHttps && valid.host in setOf(project.host,uploadHost) && valid.port==443 &&
                    valid.encodedPath.startsWith("/storage/v1/upload/resumable/")) { "Unexpected upload session." }
                saveSession(valid.toString())
                offset=0
            }
        }
        require(offset in 0..file.length()) { "Invalid upload offset." }
        while(offset<file.length()) {
            ensureWifi()
            val start=offset
            val length=minOf(6L*1024*1024,file.length()-offset)
            val body=object: RequestBody() {
                override fun contentType()="application/offset+octet-stream".toMediaType()
                override fun contentLength()=length
                override fun writeTo(sink: BufferedSink) {
                    RandomAccessFile(file,"r").use { input ->
                        input.seek(start)
                        val buffer=ByteArray(32768)
                        var remaining=length
                        while(remaining>0) {
                            ensureWifi()
                            val count=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt())
                            check(count>0) { "Saved original ended unexpectedly." }
                            sink.write(buffer,0,count);remaining-=count
                        }
                    }
                }
            }
            call(requireNotNull(session),"PATCH",item.ownerId,mapOf("Tus-Resumable" to "1.0.0","Upload-Offset" to offset.toString()),body).use { response ->
                if(response.code!=204) fail(response)
                val next=response.header("Upload-Offset")?.toLongOrNull()
                if(next!=offset+length) throw IOException("Unexpected resumable upload offset.")
                offset=next
                onProgress(offset)
            }
        }
    }

    suspend fun download(path: String): ByteArray = withContext(Dispatchers.IO) {
        require(path.matches(Regex("[0-9a-f-]{36}/[0-9a-f-]{36}/original")))
        val owner=backend.auth.currentUser?.id ?: error("Sign in first.")
        call((project.toString().trimEnd('/')+"/storage/v1/object/authenticated/report-originals/$path").toHttpUrl(),"GET",owner).use {
            if(!it.isSuccessful) fail(it)
            it.body?.bytes() ?: throw IOException("Original photo is unavailable.")
        }
    }
    private fun fail(response: Response): Nothing {
        // Upload URLs can contain authorization material. Keep them out of errors.
        throw BackendException(response.code,"UPLOAD_${response.code}","Original photo transfer failed (${response.code}).")
    }

    companion object {
        class TestSetup(val project: HttpUrl,val link: OriginalLink)
        /** Debug builds only: lets instrumented tests put a resumable-upload server model BEHIND the unchanged URL/host/port/token
         *  rules. The project must still be https on *.supabase.co. Real Wi-Fi/cellular behavior is NOT tested through this seam. */
        @Volatile var testSetup: (() -> TestSetup)?=null
            private set
        fun installForTesting(setup: () -> TestSetup) {
            check(com.example.pinpoint.BuildConfig.DEBUG) { "Test transport is available in debug builds only." }
            testSetup=setup
        }
        fun resetForTesting() { testSetup=null }
        /** Entry point for the worker and viewer: the real pinned Wi-Fi transport unless a debug test double is installed. */
        fun create(context: Context,backend: SupabaseBackend): WifiOriginals {
            val setup=if(com.example.pinpoint.BuildConfig.DEBUG) testSetup?.invoke() else null
            if(setup!=null) {
                require(setup.project.isHttps && setup.project.host.endsWith(".supabase.co")) { "Test project must be https on supabase.co." }
                return WifiOriginals(backend,setup.project,setup.link)
            }
            return WifiOriginals(context,backend)
        }
    }
}
