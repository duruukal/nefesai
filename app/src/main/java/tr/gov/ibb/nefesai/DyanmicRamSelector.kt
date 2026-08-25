package tr.gov.ibb.nefesai

import android.app.ActivityManager
import android.content.Context
import android.util.Log

public enum class DeviceRAMClass {
    LOW,       // 4 GB veya daha az
    STANDARD,  // 6 GB
    HIGH;      // 8 GB ve üzeri

    companion object {
        private const val TAG = "DynamicRamSelector"

        fun getCurrent(context: Context): DeviceRAMClass {
            return try {
                val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)

                val totalBytes = memoryInfo.totalMem
                val totalGigabytes = totalBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)

                Log.d(TAG, "📊 Cihaz Toplam RAM: ${String.format("%.2f", totalGigabytes)} GB")

                when {
                    totalGigabytes <= 1.5 -> LOW
                    totalGigabytes <= 6.5 -> STANDARD
                    else -> HIGH
                }
            } catch (e: Exception) {
                Log.e(TAG, "RAM miktarı okunamadı, default olarak STANDARD seçildi.", e)
                STANDARD
            }
        }
    }
}