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
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingLabel: TextView
    private lateinit var contentContainer: LinearLayout
    private lateinit var loadingContainer: LinearLayout
    private lateinit var inputBar: LinearLayout
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
        showLoadingState()
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
        showLoadingState()
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
                    hideLoadingState()
                    sendButton.isEnabled = true
                    quickActionContainer.visibility = View.VISIBLE
                    startConversation()
                }
            }
        }
    }

    private fun startConversation() {
        appendMessage("Nefes AI", "Merhaba, ben Nefes AI. İnternete bağlı olmadan çalışan afet ve acil durum asistanınızım.")
    }

    private fun handleSend() {
        val text = inputField.text.toString().trim()
        if (text.isEmpty()) return

        quickActionContainer.visibility = View.GONE
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
                    quickActionContainer.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun handleClear() {
        messages.clear()
        adapter.notifyDataSetChanged()

        activityScope.launch(Dispatchers.Default) {
            try {
                SystemManager.shared.resetChatSession()
                val engine = LlamaInferenceEngine.getInstance(context = this@NefesAIChatActivity)
                engine.resetConversation()
            } catch (e: Exception) {
                Log.e(TAG, "Temizleme esnasında hata", e)
            }
        }
        startConversation()
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E8F4F1"))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dpToPx(2).toFloat()
            }
        }
        topBar.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Kapat"
            setTextColor(Color.parseColor("#FF3B30"))
            setOnClickListener { finish() }
        })
        topBar.addView(TextView(this).apply {
            text = "Nefes AI\n(Çevrimdışı / Güvenli Mod)"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1C1C1E"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topBar.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Temizle"
            setTextColor(Color.parseColor("#007AFF"))
            setOnClickListener { handleClear() }
        })
        root.addView(topBar)

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@NefesAIChatActivity).apply {
                stackFromEnd = true
            }
            itemAnimator = null
            setPadding(0, 0, 0, dpToPx(8))
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        adapter = ChatAdapter(messages)
        recyclerView.adapter = adapter
        contentContainer.addView(recyclerView)
        root.addView(contentContainer)

        loadingContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        progressBar = ProgressBar(this)
        loadingLabel = TextView(this).apply {
            text = "Model belleğe yükleniyor, lütfen bekleyin..."
            setPadding(50, dpToPx(16), 50, 0)
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
        }
        loadingContainer.addView(progressBar)
        loadingContainer.addView(loadingLabel)
        root.addView(loadingContainer)

        quickActionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(dpToPx(16), dpToPx(4), dpToPx(16), dpToPx(4))
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
                setTextColor(Color.parseColor("#007AFF"))
                isClickable = true
                isFocusable = true

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(16).toFloat()
                    setColor(Color.parseColor("#E1F0FF"))
                    setStroke(dpToPx(1), Color.parseColor("#B3D7FF"))
                }

                setPadding(dpToPx(12), dpToPx(4), dpToPx(12), dpToPx(4))

                setOnClickListener {
                    inputField.setText(action)
                    handleSend()
                }
            }
            quickActionContainer.addView(actionButton)
        }
        root.addView(quickActionContainer)

        inputBar = LinearLayout(this).apply {
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
                cornerRadius = dpToPx(24).toFloat()
                setColor(Color.parseColor("#E9ECEF"))
            }
            setPadding(dpToPx(18), dpToPx(4), dpToPx(12), dpToPx(4))
        }

        inputField = EditText(this).apply {
            hint = "Nefes AI ile konuşun..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#1C1C1E"))
            setHintTextColor(Color.GRAY)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { handleSend(); true } else false
            }
        }

        sendButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Gönder"
            setTextColor(Color.parseColor("#007AFF"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { handleSend() }
        }
        inputBar.addView(inputField)
        inputBar.addView(sendButton)
        root.addView(inputBar)

        disclaimerLabel = TextView(this).apply {
            text = "Nefes AI çevrimdışı çalışır; acil durumlarda öncelik 112'dedir."
            textSize = 11f
            setTextColor(Color.parseColor("#8E8E93"))
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(4), 0, dpToPx(8))
        }
        root.addView(disclaimerLabel)

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

    private fun showLoadingState() {
        contentContainer.visibility = View.GONE
        loadingContainer.visibility = View.VISIBLE
        quickActionContainer.visibility = View.GONE
        inputBar.visibility = View.GONE
        disclaimerLabel.visibility = View.GONE
    }

    private fun hideLoadingState() {
        contentContainer.visibility = View.VISIBLE
        loadingContainer.visibility = View.GONE
        quickActionContainer.visibility = View.VISIBLE
        inputBar.visibility = View.VISIBLE
        disclaimerLabel.visibility = View.VISIBLE
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}

private class ChatAdapter(private val items: List<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView) {
        init {
            textView.textSize = 16f
            val density = textView.context.resources.displayMetrics.density
            val paddingHorizontal = (16 * density).toInt()
            val paddingVertical = (10 * density).toInt()
            textView.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
            textView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(TextView(parent.context))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = items[position]
        val context = holder.textView.context

        if (message.sender == "Siz") {
            holder.textView.gravity = Gravity.END
            holder.textView.setTextColor(Color.parseColor("#007AFF"))
            holder.textView.text = message.text
        } else {
            holder.textView.gravity = Gravity.START
            holder.textView.setTextColor(Color.parseColor("#1C1C1E"))
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