package tr.gov.ibb.nefesai

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = RelativeLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#090D10"))
        }

        // 1. KATMAN: Arka Plan Filigranı (Siyah boşluk yok, tam ekran)
        val backgroundWatermark = ImageView(this).apply {
            setImageResource(R.drawable.nefeslogo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.22f
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(backgroundWatermark)

        // 2. KATMAN: Başlık (Bir tık daha yukarı alındı)
        val titleText = TextView(this).apply {
            id = View.generateViewId()
            text = "Nefes-AI"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A3E4D7"))
            gravity = Gravity.CENTER
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.CENTER_HORIZONTAL)
                setMargins(0, dpToPx(28), 0, dpToPx(6)) // Nokta atışı üst boşluk
            }
        }
        rootLayout.addView(titleText)

        // 3. KATMAN: Alt Açıklama Metni (Başlığın hemen altında)
        val subtitleText = TextView(this).apply {
            id = View.generateViewId()
            text = "Nefes-AI, mobil cihazda tamamen offline çalışan bir acil durum asistanı. Temel amacı afet senaryolarında hayat kurtaran rehberlik sağlamak."
            textSize = 13f
            setTextColor(Color.parseColor("#8EAFA6"))
            gravity = Gravity.CENTER
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.CENTER_HORIZONTAL)
                addRule(RelativeLayout.BELOW, titleText.id)
                setMargins(dpToPx(32), 0, dpToPx(32), 0)
            }
        }
        rootLayout.addView(subtitleText)

        // 4. KATMAN: Başlat Butonu (Ellerin hemen altında sabit)
        val startButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "NEFES-AI BAŞLAT"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#090D10"))

            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(30).toFloat()
                setColor(Color.parseColor("#A3E4D7"))
            }

            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                dpToPx(56)
            ).apply {
                addRule(RelativeLayout.CENTER_HORIZONTAL)
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
                setMargins(dpToPx(32), 0, dpToPx(32), dpToPx(90))
            }

            setOnClickListener {
                try {
                    NefesAI.shared.start(this@MainActivity)
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Modül başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
                    e.printStackTrace()
                }
            }
        }
        rootLayout.addView(startButton)

        setContentView(rootLayout)
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}