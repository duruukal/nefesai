package tr.gov.ibb.nefesai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
        private const val UI_UPDATE_THROTTLE_MS = 100L
    }

    private lateinit var modelPath: String
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var bottomContainer: LinearLayout
    private lateinit var quickActionContainer: LinearLayout
    private lateinit var disclaimerLabel: TextView

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        modelPath = intent.getStringExtra(EXTRA_MODEL_PATH) ?: ""

        if (modelPath.isBlank()) {
            modelPath = applicationContext.filesDir.absolutePath + "/nefes.gguf"
            Log.w(TAG, "⚠️ model_path boş geldi, varsayılan yol deniniyor: $modelPath")
        }

        SystemManager.shared.initialize(this)

        setupUI()

        // Karşılama mesajını ve arayüzü doğrudan görünür başlatıyoruz
        startConversation()

        // Modeli arka planda yükleyip hazır hale getiriyoruz
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
        sendButton.isEnabled = false

        activityScope.launch(Dispatchers.Default) {
            try {
                val engine = LlamaInferenceEngine.getInstance(this@NefesAIChatActivity)
                engine.loadModel(modelPath)
                Log.i(TAG, "🔥 Model yüklendi, warm-up başlatılıyor...")

                val warmUpText = "Merhaba"
                val decision = SystemManager.shared.prepareResponse(warmUpText)

                if (decision is ChatDecision.Generate) {
                    val purePrompt = decision.prompt
                    val responseBuilder = StringBuilder()
                    engine.sendUserPrompt(pureFormattedPrompt = purePrompt).collect { token ->
                        responseBuilder.append(token)
                    }
                    Log.i(TAG, "✅ [WARM-UP DONE]")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Warm-up sırasında hata oluştu", e)
            } finally {
                withContext(Dispatchers.Main) {
                    sendButton.isEnabled = true
                }
            }
        }
    }

    private fun startConversation() {
        if (messages.isEmpty()) {
            appendMessage("Nefes AI", "Merhaba, ben Nefes AI. Tamamen offline çalışan bir acil durum asistanıyım. Temel amaç afet senaryolarında hayat kurtarıcı rehberlik sağlamak.")
        }
    }

    private fun handleSend() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return

        inputField.text.clear()
        sendButton.isEnabled = false

        appendMessage("Siz", text)
        appendMessage("Nefes AI", "...")
        val targetIndex = messages.size - 1

        activityScope.launch(Dispatchers.Default) {
            try {
                val decision = SystemManager.shared.prepareResponse(text)

                when (decision) {
                    is ChatDecision.ShowSelectionOptions -> {
                        withContext(Dispatchers.Main) {
                            updateMessage(targetIndex, decision.message, isFinal = true)
                        }
                        return@launch
                    }
                    is ChatDecision.DirectResponse -> {
                        withContext(Dispatchers.Main) {
                            updateMessage(targetIndex, decision.message, isFinal = true)
                        }
                        return@launch
                    }
                    is ChatDecision.Generate -> {
                        val purePrompt = decision.prompt
                        val responseBuilder = StringBuilder()
                        var lastUiUpdate = 0L

                        val engine = LlamaInferenceEngine.getInstance(context = this@NefesAIChatActivity)

                        engine.sendUserPrompt(pureFormattedPrompt = purePrompt).collect { token ->
                            responseBuilder.append(token)
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= UI_UPDATE_THROTTLE_MS) {
                                lastUiUpdate = now
                                val snapshot = responseBuilder.toString()
                                withContext(Dispatchers.Main) {
                                    updateMessage(targetIndex, snapshot, isFinal = false)
                                }
                            }
                        }

                        val finalText = responseBuilder.toString()
                        withContext(Dispatchers.Main) {
                            updateMessage(targetIndex, finalText, isFinal = true)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    updateMessage(targetIndex, "Bir hata oluştu: ${e.localizedMessage}", isFinal = true)
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
        adapter.fastUpdate(recyclerView, index)

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
            setBackgroundColor(Color.parseColor("#93B8A3"))
        }

        // 1. KATMAN: Arka Plan Tam Ekran Filigran Logo
        val watermarkImageView = ImageView(this).apply {
            setImageResource(R.drawable.nefes_logo)
            alpha = 0.10f
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(watermarkImageView)

        // 2. KATMAN: Üst Bar / Header
        val topBar = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1B2A22"))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dpToPx(4).toFloat()
            }
        }

        val headerLeftLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val logoImageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            setImageResource(R.drawable.nefes_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                clipToOutline = true
            }
        }

        val titleTextView = TextView(this).apply {
            text = "Nefes-AI"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dpToPx(10), 0, 0, 0)
            }
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

        // 3. KATMAN: Alt Alan (Hızlı Eylem Butonları, Giriş Çubuğu ve Uyarı)
        bottomContainer = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
            }
        }

        quickActionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val actions = listOf(
            "Deprem anında ne yapmalıyım?",
            "Depremden sonra ne yapmalıyım?",
            "Gaz kokusu alıyorum",
            "Yangın var",
            "Acil çanta listesi",
            "112'yi ara"
        )

        for (action in actions) {
            val actionButton = TextView(this).apply {
                text = action
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.parseColor("#1E242B"))
                isClickable = true
                isFocusable = true

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(16).toFloat()
                    setColor(Color.parseColor("#EAF5F0"))
                    setStroke(dpToPx(1), Color.parseColor("#B8D9CC"))
                }

                setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, dpToPx(8), 0)
                }

                setOnClickListener {
                    inputField.setText(action)
                    handleSend()
                }
            }
            quickActionContainer.addView(actionButton)
        }
        bottomContainer.addView(quickActionContainer)

        // Oval Mesaj Giriş Çubuğu
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dpToPx(16), dpToPx(4), dpToPx(16), dpToPx(4))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(28).toFloat()
                setColor(Color.parseColor("#F4F7F5"))
                setStroke(dpToPx(1), Color.parseColor("#C5E2D5"))
            }
            setPadding(dpToPx(16), dpToPx(4), dpToPx(8), dpToPx(4))
        }

        inputField = EditText(this).apply {
            hint = "Nefes AI ile konuşun..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#1C2128"))
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { handleSend(); true } else false
            }
        }

        sendButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "GÖNDER"
            setTextColor(Color.parseColor("#1E242B"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { handleSend() }
        }
        inputBar.addView(inputField)
        inputBar.addView(sendButton)
        bottomContainer.addView(inputBar)

        disclaimerLabel = TextView(this).apply {
            text = "Nefes AI çevrimdışı çalışır, acil durumlarda öncelik 112'dedir."
            textSize = 11f
            setTextColor(Color.parseColor("#2E4F42"))
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }
        bottomContainer.addView(disclaimerLabel)
        root.addView(bottomContainer)

        // 4. KATMAN: Sohbet Mesaj Listesi (RecyclerView)
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@NefesAIChatActivity).apply {
                stackFromEnd = true
            }
            itemAnimator = null
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

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}

private class ChatAdapter(private val items: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView) {
        init {
            textView.textSize = 15f
            val density = textView.context.resources.displayMetrics.density
            val paddingHorizontal = (16 * density).toInt()
            val paddingVertical = (12 * density).toInt()
            textView.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)

            textView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(textView.context, 16).toFloat()
            }

            val layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams.setMargins(0, (6 * density).toInt(), 0, (6 * density).toInt())
            textView.layoutParams = layoutParams
        }

        private fun dpToPx(context: Context, dp: Int): Int {
            return (dp * context.resources.displayMetrics.density).toInt()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(TextView(parent.context))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = items[position]
        val context = holder.textView.context
        val drawable = holder.textView.background as GradientDrawable

        if (message.sender == "Siz") {
            holder.textView.gravity = Gravity.END
            holder.textView.setTextColor(Color.parseColor("#1C2128"))
            drawable.setColor(Color.parseColor("#F4F2EB"))
            holder.textView.text = message.text
        } else {
            holder.textView.gravity = Gravity.START
            holder.textView.setTextColor(Color.parseColor("#1C2128"))
            drawable.setColor(Color.parseColor("#F7F5EE"))

            if (message.text == "...") {
                holder.textView.text = "Nefes AI düşünüyor..."
                holder.textView.setTextColor(Color.GRAY)
            } else {
                holder.textView.text = "${message.sender}: ${message.text}"
            }
        }

        holder.textView.setOnClickListener {
            if (message.text != "...") {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("NefesAiMessage", message.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Kopyalandı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun fastUpdate(recyclerView: RecyclerView, position: Int) {
        val holder = recyclerView.findViewHolderForAdapterPosition(position) as? ViewHolder
        if (holder != null) {
            val message = items[position]
            if (message.sender != "Siz" && message.text != "...") {
                holder.textView.text = "${message.sender}: ${message.text}"
            } else {
                holder.textView.text = message.text
            }
        } else {
            notifyItemChanged(position)
        }
    }

    override fun getItemCount(): Int = items.size
}