package com.cyotun.jpq.updates

import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.net.*
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.cyotun.jpq.*
import com.cyotun.jpq.core.ReleaseManifest
import java.io.*
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

class UpdateManager(private val context: Context) {
    private val candidate = BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE
    private val prefs=context.getSharedPreferences("jpq-updates",Context.MODE_PRIVATE)
    private val folder=File(context.filesDir,"updates").apply { mkdirs() }
    private val apk=File(folder,"verified.apk")
    private val partial=File(folder,"download.part")
    private val manifestFile=File(folder,"verified-manifest.json")
    private val publicKey=context.assets.open("update-key.txt").bufferedReader().use { Base64.getDecoder().decode(it.readText().trim()) }
    fun status()=if(candidate) CANDIDATE_STATUS else prefs.getString("status","尚未检查更新")!!
    private fun status(value: String) { if(!candidate) prefs.edit().putString("status",value).apply() }
    private fun wifi(): Boolean {
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cap=cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private fun currentVersion(): Long=context.packageManager.getPackageInfo(context.packageName,0).longVersionCode
    private fun connection(path: String): HttpsURLConnection=(URL(BASE+path).openConnection() as HttpsURLConnection).apply {
        connectTimeout=15000; readTimeout=20000; instanceFollowRedirects=false; setRequestProperty("Accept-Encoding","identity")
    }
    fun check(manual: Boolean=false): Boolean = synchronized(lock) {
        if(candidate) { status(CANDIDATE_STATUS); return@synchronized false }
        when(UpdateCheckPolicy.next(manual,System.currentTimeMillis(),prefs.getLong("checked",0),hasPendingUpdate())) {
            UpdateCheckPolicy.Action.RESUME_PENDING -> { scheduleDownload(context); return@synchronized false }
            UpdateCheckPolicy.Action.THROTTLED -> return@synchronized false
            UpdateCheckPolicy.Action.FETCH -> Unit
        }
        status("正在检查更新")
        runCatching {
            val c=connection("/v1/releases/latest")
            try {
                if(c.responseCode==204) { status("暂无发布版本"); prefs.edit().putLong("checked",System.currentTimeMillis()).apply(); return@synchronized false }
                require(c.responseCode==200)
                val bytes=c.inputStream.use { bounded(it,32768) }; val text=bytes.toString(Charsets.UTF_8)
                val release=ReleaseManifest.parse(text); require(release.verify(publicKey)) { "Release signature mismatch" }
                require(release.minSdk<=Build.VERSION.SDK_INT)
                if(release.versionCode<=currentVersion()) { status("已是当前版本"); prefs.edit().putLong("checked",System.currentTimeMillis()).apply(); return@synchronized false }
                require(context.packageName==release.packageName)
                val previous=if(manifestFile.exists()) manifestFile.readText() else null
                if(previous!=text) { partial.delete(); apk.delete() }
                File(folder,"manifest.tmp").apply { writeText(text); kotlin.check(renameTo(manifestFile)) }
                prefs.edit().putLong("checked",System.currentTimeMillis()).apply()
                status("发现 ${release.versionName}，等待空闲下载"); scheduleDownload(context); true
            } finally { c.disconnect() }
        }.getOrElse { status("暂时无法检查更新，请稍后重试"); false }
    }
    private fun release(): ReleaseManifest {
        check(!candidate) { CANDIDATE_STATUS }
        require(manifestFile.exists() && manifestFile.length()<=32768)
        val r=ReleaseManifest.parse(manifestFile.readText()); require(r.verify(publicKey)); require(r.packageName==context.packageName && r.versionCode>currentVersion() && r.minSdk<=Build.VERSION.SDK_INT)
        return r
    }
    fun hasPendingUpdate(): Boolean = synchronized(lock) {
        !candidate && runCatching { release(); true }.getOrDefault(false)
    }
    fun download(manual: Boolean=false,shouldCancel: ()->Boolean = { false }): Boolean = synchronized(lock) {
        if(candidate) { status(CANDIDATE_STATUS); return@synchronized false }
        if(AppState.running) { status("记牌停止后再下载更新"); return@synchronized false }
        if(!manual && !wifi()) { status("等待 Wi-Fi 下载更新"); return@synchronized false }
        runCatching {
            val r=release()
            if(apk.exists() && validApk(apk,r)) { status("更新已下载，可安装 ${r.versionName}"); return@synchronized true }
            val space=android.os.StatFs(folder.absolutePath).availableBytes
            require(space>r.sizeBytes*2+16*1024*1024) { "Insufficient storage" }
            if(partial.length()>r.sizeBytes) partial.delete()
            var offset=partial.length()
            if(offset<r.sizeBytes) {
                val c=connection("/v1/releases/${r.versionCode}/download")
                if(offset>0) c.setRequestProperty("Range","bytes=$offset-")
                try {
                    val code=c.responseCode
                    if(code==200) { offset=0; partial.delete() }
                    else {
                        require(code==206 && c.getHeaderField("Content-Range")=="bytes $offset-${r.sizeBytes-1}/${r.sizeBytes}")
                    }
                    require(c.getHeaderField("ETag")=="\"${r.sha256}\"")
                    val total=r.sizeBytes-offset
                    if(c.contentLengthLong>=0) require(c.contentLengthLong==total)
                    c.inputStream.use { source -> FileOutputStream(partial,offset>0).use { dest ->
                        val block=ByteArray(65536); var count=offset
                        while(true) {
                            // Cancellation leaves a resumable partial belonging to this signed manifest.
                            if(AppState.running || shouldCancel()) { status("下载暂停，空闲时继续"); return@synchronized false }
                            val n=source.read(block); if(n<0) break
                            require(count+n<=r.sizeBytes); dest.write(block,0,n); count+=n
                            status("下载更新 ${(count*100/r.sizeBytes)}%")
                        }; dest.fd.sync(); require(count==r.sizeBytes)
                    } }
                } finally { c.disconnect() }
            }
            require(validApk(partial,r)) { "Invalid APK" }
            apk.delete(); kotlin.check(partial.renameTo(apk)); status("更新已下载，可安装 ${r.versionName}")
            if(!AppState.running) notifyReady(r.versionName)
            true
        }.getOrElse { status("下载或校验未完成，可重试"); if(partial.exists() && partial.length()==runCatching { release().sizeBytes }.getOrDefault(-1)) partial.delete(); false }
    }
    @Suppress("DEPRECATION") private fun validApk(file: File,r: ReleaseManifest): Boolean = runCatching {
        require(file.length()==r.sizeBytes)
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b=ByteArray(65536); while(true) { val n=input.read(b); if(n<0) break; digest.update(b,0,n) } }
        require(digest.digest().joinToString("") { "%02x".format(it) }==r.sha256)
        val pm=context.packageManager; val target=pm.getPackageArchiveInfo(file.path,PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Invalid package")
        val own=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        require(target.packageName==r.packageName && target.longVersionCode==r.versionCode && target.versionName==r.versionName)
        require(target.applicationInfo!!.minSdkVersion==r.minSdk.toInt())
        fun signatures(info: android.content.pm.PackageInfo)=info.signingInfo!!.apkContentsSigners.map { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
        require(signatures(target)==signatures(own)); true
    }.getOrDefault(false)
    fun install(activity: Activity,onFinished: ()->Unit = {}) {
        if(candidate) { status(CANDIDATE_STATUS); return }
        if(AppState.running) { status("请先停止记牌，再安装更新"); return }
        var version=""
        var installFile: File?=null
        val installerLaunched=AtomicBoolean(false)
        val coordinator=InstallCoordinator(
            executor=installExecutor,
            updateLock=lock,
            dispatchMain={ action -> activity.runOnUiThread(action) },
            verify={
                if(candidate || AppState.running) false
                else {
                    val r=release()
                    val valid=apk.exists() && validApk(apk,r)
                    if(valid) {
                        version=r.versionName
                        installFile=InstallFileSnapshot.create(apk,folder)
                    }
                    valid
                }
            },
            canInstall={
                when {
                    activity.isFinishing || activity.isDestroyed -> { status("安装已取消"); false }
                    candidate -> false
                    AppState.running -> { status("请先停止记牌，再安装更新"); false }
                    else -> true
                }
            },
            launch={
                if(!context.packageManager.canRequestPackageInstalls()) {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))
                } else {
                    val stableFile=checkNotNull(installFile)
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",stableFile)
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    installerLaunched.set(true)
                    status("请按系统提示完成安装 $version")
                }
            },
            onFailure={
                if(!installerLaunched.get()) installFile?.delete()
                status("安装包尚未准备好或校验失败")
            },
            active=installGate,
        )
        coordinator.request(onStarted={ status("正在后台校验安装包") },onFinished={
            if(!installerLaunched.get()) installFile?.delete()
            onFinished()
        })
    }
    private fun notifyReady(version: String) {
        val nm=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("updates","应用更新",NotificationManager.IMPORTANCE_DEFAULT))
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val open=PendingIntent.getActivity(context,22,Intent(context,MainActivity::class.java).putExtra("page","update"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(22,Notification.Builder(context,"updates").setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("记牌器更新已准备好")
            .setContentText("$version · 点击查看并安装").setAutoCancel(true).setContentIntent(open).build())
    }
    companion object {
        private const val CANDIDATE_STATUS="记牌器BETA测试中；正式版更新独立管理"
        private const val BASE="https://jpq.240438457.workers.dev"
        private val lock=Any()
        private val installGate=AtomicBoolean(false)
        private val installExecutor=Executors.newSingleThreadExecutor { task ->
            Thread(task,"jpq-update-install-check").apply { isDaemon=true }
        }
        fun schedule(context: Context) {
            if(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE) return
            val jobs=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobs.schedule(JobInfo.Builder(401,ComponentName(context,UpdateJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(86400000).setPersisted(true).build())
        }
        fun scheduleDownload(context: Context) {
            if(BuildConfig.SINGLE_APP_CAPTURE_CANDIDATE) return
            val jobs=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobs.schedule(JobInfo.Builder(402,ComponentName(context,UpdateJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setMinimumLatency(1000)
                .setBackoffCriteria(60_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
        private fun bounded(input: InputStream,max: Int): ByteArray {
            val out=ByteArrayOutputStream(); val block=ByteArray(4096)
            while(true) { val n=input.read(block); if(n<0) break; require(out.size()+n<=max); out.write(block,0,n) }; return out.toByteArray()
        }
    }
}
