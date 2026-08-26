package tr.gov.ibb.nefesai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tr.gov.ibb.nefesai.internal.LlamaInferenceEngine

data class ChatMessage(val sender: String, var text: String)

class NefesAIChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODEL_PATH = "model_path"
        private const val TAG = "NefesAIChatActivity"
    }

    private lateinit var modelPath: String
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var bottomContainer: LinearLayout
    private lateinit var disclaimerLabel: TextView

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // RAG tamamen kaldırıldı
        modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: ""

        if (modelPath.isBlank()) {
            modelPath = applicationContext.filesDir.absolutePath + "/nefes.gguf"
        }

        SystemManager.shared.initialize(this)
        setupUI()
        initializeAndLoadLlamaModel()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
        try {
            LlamaInferenceEngine.getInstance(this@NefesAIChatActivity).cleanUp()
        } catch (e: Exception) {
            Log.e(TAG, "Model temizlenirken hata oluştu", e)
        }
    }

    private fun initializeAndLoadLlamaModel() {
        sendButton.isEnabled = true
        activityScope.launch(Dispatchers.Default) {
            try {
                val engine = LlamaInferenceEngine.getInstance(this@NefesAIChatActivity)
                engine.loadModel(modelPath)
                Log.i(TAG, "🔥 Model yüklendi ve hazır!")
            } catch (e: Exception) {
                Log.e(TAG, "Model yüklenirken hata", e)
            } finally {
                withContext(Dispatchers.Main) {
                    sendButton.isEnabled = true
                }
            }
        }
    }

    private fun handleSend() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return

        inputField.text.clear()
        sendButton.isEnabled = false

        appendMessage("Siz", text)
        appendMessage("Nefes AI", "Nefes AI düşünüyor...")
        val targetIndex = messages.size - 1

        activityScope.launch(Dispatchers.Default) {
            try {
                // Etiket, açıklama ve rol tanımını tamamen atıyoruz.
                // Doğrudan cümle tamamlama mantığına zorluyoruz.
                val purePrompt = "Acil durum için ilk yardım: $text ->"

                val responseBuilder = StringBuilder()
                val engine = LlamaInferenceEngine.getInstance(context = this@NefesAIChatActivity)

                engine.sendUserPrompt(pureFormattedPrompt = purePrompt).collect { token ->
                    responseBuilder.append(token)
                    val snapshot = responseBuilder.toString()
                    withContext(Dispatchers.Main) {
                        updateMessage(targetIndex, snapshot, isFinal = false)
                    }
                }

                val finalText = responseBuilder.toString().trim()

                val resolvedText = if (finalText.length < 3 || finalText.contains("Kapasite")) {
                    "Lütfen sakin olun, güvenli bir alana geçin ve 112'yi arayın."
                } else {
                    finalText
                }

                withContext(Dispatchers.Main) {
                    updateMessage(targetIndex, resolvedText, isFinal = true)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Model yanıt üretirken hata", e)
                withContext(Dispatchers.Main) {
                    updateMessage(targetIndex, "Acil durumda lütfen 112'yi arayın.", isFinal = true)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    sendButton.isEnabled = true
                }
            }
        }
    }

    private fun appendMessage(sender: String, text: String) {
        messages.add(ChatMessage(sender, text))
        adapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)
    }

    private fun updateMessage(index: Int, text: String, isFinal: Boolean) {
        if (index >= messages.size) return
        messages[index].text = text
        adapter.notifyItemChanged(index)

        if (isFinal || !recyclerView.canScrollVertically(1)) {
            recyclerView.scrollToPosition(messages.size - 1)
        }
    }

    private fun setupUI() {
        val root = RelativeLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#7FA39A"))
        }

        val watermarkImageView = ImageView(this).apply {
            setImageResource(R.drawable.nefeslogo)
            alpha = 0.15f
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(watermarkImageView)

        val topBar = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#173531"))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
            }
        }

        val headerLeftLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val logoImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            setImageResource(R.drawable.nefeslogo)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val titleTextView = TextView(this).apply {
            text = "Nefes-AI"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            setMarginsLeft(dpToPx(10))
        }

        headerLeftLayout.addView(logoImageView)
        headerLeftLayout.addView(titleTextView)
        topBar.addView(headerLeftLayout)

        val statusLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        statusLayout.addView(TextView(this).apply {
            text = "• Local"
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4ADE80"))
            gravity = Gravity.END
        })
        statusLayout.addView(TextView(this).apply {
            text = "Çevrimdışı"
            textSize = 10f
            setTextColor(Color.parseColor("#D1D5DB"))
            gravity = Gravity.END
        })
        topBar.addView(statusLayout)
        root.addView(topBar)

        bottomContainer = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        }

        val inputBar = RelativeLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                dpToPx(56)
            ).apply {
                setMargins(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(30).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
                setStroke(dpToPx(1), Color.parseColor("#A3C1B8"))
            }
        }

        sendButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            id = View.generateViewId()
            text = "GÖNDER"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#2F3E46"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#A3C1B8"))
            }
            layoutParams = RelativeLayout.LayoutParams(
                dpToPx(90),
                dpToPx(40)
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                addRule(RelativeLayout.CENTER_VERTICAL)
                setMargins(0, 0, dpToPx(8), 0)
            }
            setOnClickListener { handleSend() }
        }
        inputBar.addView(sendButton)

        inputField = EditText(this).apply {
            hint = "Nefes AI ile konuşun..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#2F3E46"))
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 14f
            setPadding(dpToPx(16), 0, dpToPx(12), 0)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).apply {
                addRule(RelativeLayout.LEFT_OF, sendButton.id)
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { handleSend(); true } else false
            }
        }
        inputBar.addView(inputField)
        bottomContainer.addView(inputBar)

        disclaimerLabel = TextView(this).apply {
            text = "Nefes AI çevrimdışı çalışır, acil durumlarda öncelik 112'dedir."
            textSize = 11f
            setTextColor(Color.parseColor("#173531"))
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }
        bottomContainer.addView(disclaimerLabel)
        root.addView(bottomContainer)

        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@NefesAIChatActivity).apply {
                stackFromEnd = true
            }
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            clipToPadding = false
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).apply {
                addRule(RelativeLayout.ABOVE, bottomContainer.id)
                addRule(RelativeLayout.BELOW, topBar.id)
            }
        }
        adapter = ChatAdapter(messages)
        recyclerView.adapter = adapter
        root.addView(recyclerView)

        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val isKeyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val bottomPadding = if (isKeyboardVisible) imeInsets.bottom else navigationBars.bottom
            v.setPadding(0, 0, 0, bottomPadding)
            insets
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
    private fun TextView.setMarginsLeft(left: Int) {
        val p = layoutParams as? ViewGroup.MarginLayoutParams
        p?.setMargins(left, p.topMargin, p.rightMargin, p.bottomMargin)
    }
}

private class ChatAdapter(private val items: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    class ViewHolder(val container: LinearLayout, val avatarView: ImageView, val textView: TextView) :
        RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val context = parent.context
        val density = context.resources.displayMetrics.density

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
        }

        val avatar = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt()).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
            setImageResource(R.drawable.nefeslogo)
        }

        val text = TextView(context).apply {
            textSize = 14f
            maxLines = 100
            val pHoriz = (14 * density).toInt()
            val pVert = (10 * density).toInt()
            setPadding(pHoriz, pVert, pHoriz, pVert)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (16 * density)
            }
        }

        container.addView(avatar)
        container.addView(text)

        return ViewHolder(container, avatar, text)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = items[position]
        val context = holder.container.context
        val drawable = holder.textView.background as GradientDrawable

        holder.textView.setTextColor(Color.parseColor("#2F3E46"))

        if (message.sender == "Siz") {
            holder.container.gravity = Gravity.END
            holder.avatarView.visibility = View.GONE
            drawable.setColor(Color.parseColor("#A3C1B8"))
            holder.textView.text = message.text
        } else {
            holder.container.gravity = Gravity.START
            holder.avatarView.visibility = View.VISIBLE
            drawable.setColor(Color.parseColor("#B4D1C6"))
            holder.textView.text = message.text
            holder.textView.setTextColor(Color.parseColor("#2F3E46"))
        }

        holder.textView.setOnClickListener {
            if (message.text != "Nefes AI düşünüyor...") {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("NefesAiMessage", message.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Kopyalandı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getItemCount(): Int = items.size
}