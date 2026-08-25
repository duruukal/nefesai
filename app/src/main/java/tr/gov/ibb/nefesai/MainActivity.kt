package tr.gov.ibb.nefesai

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Kök layout - Mint Yeşili Arka Plan
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))
            setBackgroundColor(Color.parseColor("#99C7B6"))
        }

        // Uygulama Başlığı
        val titleText = TextView(this).apply {
            text = "Nefes AI"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1C2128"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(8))
        }
        root.addView(titleText)

        // Alt Açıklama
        val subtitleText = TextView(this).apply {
            text = "Çevrimdışı Deprem ve Acil Durum Asistanı"
            textSize = 14f
            setTextColor(Color.parseColor("#25332E"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(32))
        }
        root.addView(subtitleText)

        // Asistanı Başlat Butonu
        val startButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Asistanı Başlat"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))

            // Koyu Füme Arka Plan ve Yuvarlatılmış Köşeler
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#1E242B"))
            }

            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16))

            setOnClickListener {
                try {
                    NefesAI.shared.start(this@MainActivity)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Modül başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
                    e.printStackTrace()
                }
            }
        }
        root.addView(startButton)

        setContentView(root)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}