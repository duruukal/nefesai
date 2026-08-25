package tr.gov.ibb.nefesai

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class NefesAI private constructor() {

    companion object {
        val shared = NefesAI()
        private const val TAG = "NefesAI"
    }

    private var isBusy = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun isDeviceCompatible(context: Context): Boolean {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val physicalMemory = memoryInfo.totalMem
            val safeThresholdInBytes: Long = 1L * 1024L * 1024L * 1024L

            Log.i(TAG, "[NEFESAI] Cihazın Raporladığı Fiziksel Bellek: ${physicalMemory.toDouble() / (1024 * 1024 * 1024)} GB")
            physicalMemory >= safeThresholdInBytes
        } catch (e: Exception) {
            Log.e(TAG, "Cihaz bellek kontrolü yapılamadı!", e)
            true
        }
    }

    fun start(context: Context) {

        if (!isDeviceCompatible(context)) {
            mainHandler.post {
                showIncompatibleDeviceAlert(context)
            }
            return
        }

        if (isBusy) {
            Log.w(TAG, "⚠️ [NefesAI] start() zaten çalışıyor, tekrar çağrı engellendi.")
            return
        }
        isBusy = true

        val modelDef = ModelRegistry.definition(context)

        val filesDir = context.filesDir
        if (filesDir == null) {
            Log.e(TAG, "❌ [NefesAI] Dosya dizinine erişilemedi.")
            return
        }

        val modelFile = File(filesDir, NefesConstants.MODEL_NAME)

        // MARK: - 1. Senaryo: Model Yerelde Hiç Yoksa
        if (!modelFile.exists()) {
            mainHandler.post {
                presentDownloadScreen(context, modelFile)
            }
            return
        }

        // MARK: - 2. Senaryo: Model Yerelde Var (Güncellik Kontrolü)
        thread(start = true) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(modelDef.remoteUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = (NefesConstants.HEAD_REQUEST_TIMEOUT * 1000).toInt()
                connection.readTimeout = (NefesConstants.HEAD_REQUEST_TIMEOUT * 1000).toInt()

                val statusCode = connection.responseCode

                if (statusCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "⚠️ [NefesAI] Geçersiz Sunucu Yanıtı! Status: $statusCode. Çevrimdışı Mod.")
                    fallbackToLocalChat(context, modelFile)
                    return@thread
                }

                val serverFileSize = connection.contentLengthLong
                val minimumValidModelSize: Long = 100 * 1024 * 1024 // 100 MB

                if (serverFileSize < minimumValidModelSize) {
                    Log.w(TAG, "⚠️ [NefesAI] Boyut çok küçük: $serverFileSize bytes. Yerel model korundu.")
                    fallbackToLocalChat(context, modelFile)
                    return@thread
                }

                val localFileSize = modelFile.length()

                if (serverFileSize == localFileSize) {
                    mainHandler.post {
                        navigateToChat(context, modelFile.absolutePath)
                    }
                } else {
                    if (modelFile.exists()) {
                        modelFile.delete()
                    }
                    mainHandler.post {
                        presentDownloadScreen(context, modelFile)
                    }
                }

            } catch (e: IOException) {
                Log.w(TAG, "⚠️ [NefesAI] Sunucuya erişilemedi. Çevrimdışı Mod")
                fallbackToLocalChat(context, modelFile)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun fallbackToLocalChat(context: Context, modelFile: File) {
        mainHandler.post {
            navigateToChat(context, modelFile.absolutePath)
        }
    }

    internal fun navigateToChat(context: Context, modelPath: String) {
        mainHandler.post {
            val intent = Intent(context, NefesAIChatActivity::class.java).apply {
                putExtra(NefesAIChatActivity.EXTRA_MODEL_PATH, modelPath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
            isBusy = false
        }
    }

    private fun presentDownloadScreen(context: Context, modelFile: File) {
        val intent = Intent(context, DavinciDownloadActivity::class.java).apply {
            putExtra("DESTINATION_PATH", modelFile.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun showIncompatibleDeviceAlert(context: Context) {
        if (context !is Activity) {
            return
        }

        context.runOnUiThread {
            try {
                AlertDialog.Builder(context)
                    .setTitle("Cihaz Uyumluluğu")
                    .setMessage("Üzgünüz, NefesAI yapay zeka modeli yüksek performans gerektirdiği için minimum 4 GB RAM'e sahip cihazları desteklemektedir.")
                    .setPositiveButton("Kapat") { dialog, _ ->
                        Log.i(TAG, "Kullanıcı uyarıyı kapattı.")
                        dialog.dismiss()
                    }
                    .setCancelable(false)
                    .create()
                    .show()
            } catch (e: Exception) {
                Log.e(TAG, "Dialog gösterilirken hata oluştu!", e)
            }
        }
    }
}