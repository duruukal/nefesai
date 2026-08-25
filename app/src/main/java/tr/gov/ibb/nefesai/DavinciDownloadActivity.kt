package tr.gov.ibb.nefesai

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class DavinciDownloadActivity : AppCompatActivity() {

    private lateinit var destinationFile: File
    private var isNavigatingToChat = false
    @Volatile private var isCancelled = false
    private var connection: HttpURLConnection? = null
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var topBar: ConstraintLayout
    private lateinit var closeButton: Button
    private lateinit var headerTitleLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var downloadButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var percentLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra("DESTINATION_PATH") ?: return finish()
        destinationFile = File(path)

        setupLayout()
        setupActions()
    }

    private fun setupLayout() {
        rootLayout = ConstraintLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#F2F2F7"))
        }

        topBar = ConstraintLayout(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.WHITE)
        }
        val topBarParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            dpToPx(56)
        ).apply {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        }
        rootLayout.addView(topBar, topBarParams)

        closeButton = Button(this).apply {
            id = View.generateViewId()
            text = "Kapat"
            setTextColor(Color.parseColor("#FF3B30"))
            setBackgroundColor(Color.TRANSPARENT)
            isAllCaps = false
            textSize = 17f
        }
        val closeBtnParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ConstraintLayout.LayoutParams.MATCH_PARENT
        ).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            marginStart = dpToPx(16)
        }
        topBar.addView(closeButton, closeBtnParams)

        headerTitleLabel = TextView(this).apply {
            id = View.generateViewId()
            text = "Modül Yönetimi"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
        }
        val headerParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        }
        topBar.addView(headerTitleLabel, headerParams)

        val containerStack = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        titleLabel = TextView(this).apply {
            text = "Yapay Zeka modülü cihazınızda yok veya güncel değil,\nyüklemek ister misiniz?"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dpToPx(24))
        }
        containerStack.addView(titleLabel)

        downloadButton = Button(this).apply {
            text = "Modeli İndir"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#007AFF"))
            isAllCaps = false
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        containerStack.addView(downloadButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(50)))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
        }
        containerStack.addView(progressBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(8)).apply {
            topMargin = dpToPx(24)
        })

        // Yüzde Metni
        percentLabel = TextView(this).apply {
            text = "%0"
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            visibility = View.GONE
        }
        containerStack.addView(percentLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpToPx(24)
        })

        val containerParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topToBottom = topBar.id
            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            marginStart = dpToPx(32)
            marginEnd = dpToPx(32)
        }
        rootLayout.addView(containerStack, containerParams)

        setContentView(rootLayout)
    }

    private fun setupActions() {
        closeButton.setOnClickListener {
            isCancelled = true
            thread {
                try { connection?.disconnect() } catch (e: Exception) {}
            }
            finish()
        }

        downloadButton.setOnClickListener {
            downloadButton.visibility = View.GONE
            progressBar.visibility = View.VISIBLE
            percentLabel.visibility = View.VISIBLE
            titleLabel.text = "Yapay Zeka modeli indiriliyor..."
            startDownload()
        }
    }

    private fun startDownload() {
        isCancelled = false

        thread(start = true) {
            val tempFile = File(cacheDir, "temp_model.gguf")
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                val url = URL(ModelRegistry.definition(this).remoteUrl)
                connection = url.openConnection() as HttpURLConnection
                connection?.connectTimeout = (NefesConstants.DOWNLOAD_REQUEST_TIMEOUT * 1000).toInt()
                connection?.readTimeout = (NefesConstants.DOWNLOAD_REQUEST_TIMEOUT * 1000).toInt()

                connection?.connect()

                val responseCode = connection?.responseCode ?: -1
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    runOnUiThread { showError("❌ Sunucudan geçersiz yanıt döndü. Status: $responseCode") }
                    return@thread
                }

                val expectedLength = connection?.contentLengthLong ?: -1L
                if (expectedLength <= 0) {
                    runOnUiThread { showError("❌ Sunucudan geçersiz dosya boyutu döndü.") }
                    return@thread
                }

                inputStream = connection?.inputStream
                outputStream = FileOutputStream(tempFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesWritten = 0L

                while (inputStream!!.read(buffer).also { bytesRead = it } != -1) {
                    if (isCancelled) {
                        tempFile.delete()
                        return@thread
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesWritten += bytesRead

                    val progress = totalBytesWritten.toFloat() / expectedLength.toFloat()
                    val percentage = (progress * 100).toInt()

                    runOnUiThread {
                        progressBar.progress = percentage
                        percentLabel.text = "%$percentage"
                        if (percentage < 100) {
                            titleLabel.text = "Yapay Zeka modeli indiriliyor..."
                        } else {
                            titleLabel.text = "Dosya doğrulanıyor, lütfen bekleyin..."
                        }
                    }
                }

                outputStream.flush()

                val downloadedSize = tempFile.length()
                if (downloadedSize != expectedLength) {
                    runOnUiThread { showError("❌ İndirilen dosya doğrulanamadı (Eksik veri).") }
                    return@thread
                }

                if (isNavigatingToChat) return@thread
                isNavigatingToChat = true

                if (destinationFile.exists()) {
                    destinationFile.delete()
                }
                destinationFile.parentFile?.let {
                    if (!it.exists()) it.mkdirs()
                }

                if (tempFile.renameTo(destinationFile)) {
                    runOnUiThread {
                        finish()
                        NefesAI.shared.navigateToChat(this@DavinciDownloadActivity, destinationFile.absolutePath)
                    }
                } else {
                    throw IOException("Dosya nihai dizine taşınamadı.")
                }

            } catch (e: Exception) {
                if (!isCancelled) {
                    runOnUiThread {
                        isNavigatingToChat = false
                        showError("❌ İndirme başarısız oldu: ${e.message}")
                    }
                }
            } finally {
                try { inputStream?.close() } catch (e: Exception) {}
                try { outputStream?.close() } catch (e: Exception) {}
                connection?.disconnect()
            }
        }
    }

    private fun showError(message: String) {
        titleLabel.text = message
        downloadButton.visibility = View.VISIBLE
        progressBar.visibility = View.GONE
        percentLabel.visibility = View.GONE
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}