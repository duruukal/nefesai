package tr.gov.ibb.nefesai

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Temiz ve Compose bağımsız bir LinearLayout oluşturuyoruz
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FAFAFA")) // Loglardaki fffafafa rengiyle eşitledik
        }

        val startButton = Button(this).apply {
            text = "Nefes AI Asistanını Başlat"
            textSize = 16f
            setPadding(50, 30, 50, 30)

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
}