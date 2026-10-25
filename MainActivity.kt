package com.multiai.search

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.net.http.SslError
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** byUrl != null: la IA acepta la pregunta en la URL. Si no, se escribe en su cuadro con JavaScript. */
data class Ai(
    val id: String, val name: String, val color: Int, val home: String,
    val byUrl: String?, val input: List<String>, val send: List<String>
)

class Pane(val ai: Ai, val root: LinearLayout, val web: WebView, val status: TextView) {
    var loading = true
}

private val BG = Color.parseColor("#0d0d0f")
private val SURFACE = Color.parseColor("#16161a")
private const val MUTED = 0xFF7A7A82.toInt()
private const val TEXT = 0xFFF0EEE8.toInt()

private val AIS = listOf(
    Ai("c", "Claude", Color.parseColor("#D97757"), "https://claude.ai/new", null,
        listOf("div[contenteditable=\"true\"].ProseMirror", "div[contenteditable=\"true\"]"),
        listOf("button[aria-label*=\"Send\" i]", "button[aria-label*=\"Enviar\" i]")),
    Ai("g", "ChatGPT", Color.parseColor("#10a37f"), "https://chatgpt.com/", "https://chatgpt.com/?q=", listOf(), listOf()),
    Ai("x", "Grok", Color.parseColor("#1DA1F2"), "https://grok.com/", "https://grok.com/?q=", listOf(), listOf()),
    Ai("m", "Gemini", Color.parseColor("#4285F4"), "https://gemini.google.com/app", null,
        listOf("rich-textarea div[contenteditable=\"true\"]", "div.ql-editor[contenteditable=\"true\"]", "div[contenteditable=\"true\"]"),
        listOf("button.send-button", "button[aria-label*=\"Send\" i]", "button[aria-label*=\"Enviar\" i]")),
    Ai("p", "Perplexity", Color.parseColor("#20B8CD"), "https://www.perplexity.ai/", "https://www.perplexity.ai/search?q=", listOf(), listOf()),
    Ai("d", "DeepSeek", Color.parseColor("#4D6BFE"), "https://chat.deepseek.com/", null,
        listOf("textarea#chat-input", "textarea"), listOf())
)

// Mismo script que la extensión: espera el cuadro, escribe, envía y avisa a la app.
private val SEND_JS = """
(function(cfg, text, id){
  var sleep=function(ms){return new Promise(function(r){setTimeout(r,ms)})};
  var visible=function(el){return !!(el.offsetParent||el.getClientRects().length)};
  var find=function(sels){
    for(var i=0;i<sels.length;i++){
      var list=document.querySelectorAll(sels[i]);
      for(var j=0;j<list.length;j++){
        var el=list[j];
        if(visible(el)&&!el.disabled&&el.getAttribute('aria-disabled')!=='true') return el;
      }
    }
    return null;
  };
  var waitFor=async function(fn,timeout){
    var t0=Date.now();
    while(Date.now()-t0<timeout){var r=fn(); if(r) return r; await sleep(300);}
    return null;
  };
  var report=function(s){try{MAI.report(id,s);}catch(e){}};
  var setText=function(el,t){
    el.focus();
    if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){
      var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement:HTMLInputElement;
      Object.getOwnPropertyDescriptor(proto.prototype,'value').set.call(el,t);
      el.dispatchEvent(new Event('input',{bubbles:true}));
    } else {
      document.execCommand('selectAll',false,null);
      document.execCommand('insertText',false,t);
      if(!(el.textContent||'').trim()){
        el.textContent=t;
        el.dispatchEvent(new InputEvent('input',{bubbles:true,data:t,inputType:'insertText'}));
      }
    }
  };
  var pressEnter=function(el){
    var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
    ['keydown','keypress','keyup'].forEach(function(t){el.dispatchEvent(new KeyboardEvent(t,o));});
  };
  (async function(){
    try{
      var input=await waitFor(function(){return find(cfg.input);},20000);
      if(!input){report('noinput');return;}
      setText(input,text);
      await sleep(500);
      var btn=await waitFor(function(){return find(cfg.send);},1500);
      if(btn) btn.click(); else pressEnter(input);
      report('sent');
    }catch(e){report('error');}
  })();
})(__CFG__, __TEXT__, __ID__);
"""

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var box: EditText
    private lateinit var scroll: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var colsPill: TextView
    private val chips = mutableMapOf<String, TextView>()

    private val enabled = mutableMapOf<String, Boolean>()
    private val panes = mutableMapOf<String, Pane>()
    private val pending = mutableMapOf<String, String>()
    private val ready = mutableSetOf<String>()
    private val sentUrl = mutableSetOf<String>()
    private var cols = 0          // 0 = automático
    private var maxId: String? = null
    private var lastRowH = 0

    private val ua: String by lazy {
        WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("mai", Context.MODE_PRIVATE)
        for (a in AIS) enabled[a.id] = prefs.getBoolean("en_" + a.id, true)
        cols = prefs.getInt("cols", 0)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        CookieManager.getInstance().setAcceptCookie(true)
        buildUi()
        sync()
        scroll.post { relayout() }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent) {
        val t: String? = when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!t.isNullOrBlank()) {
            box.setText(t)
            scroll.postDelayed({ send(t) }, 400)
        }
    }

    private fun pill(label: String, fg: Int, fill: Int?, stroke: Int?, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(fg)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                if (fill != null) setColor(fill)
                if (stroke != null) setStroke(dp(1), stroke)
            }
            setOnClickListener { onClick() }
        }

    private fun margin(l: Int = 0, r: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(l), 0, dp(r), 0) }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(8), dp(8), dp(8), 0)
        }

        // Fila de búsqueda
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        box = EditText(this).apply {
            hint = "Escribe tu pregunta…"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(SURFACE)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { send(text.toString()); true } else false
            }
        }
        top.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(pill("Buscar →", BG, TEXT, null) { send(box.text.toString()) }, margin(l = 8))
        // Botones para bajar/subir una fila de paneles (los WebView capturan el dedo, así que el scroll táctil no llega)
        top.addView(pill("▲", TEXT, null, MUTED) { scroll.smoothScrollBy(0, -maxOf(lastRowH, dp(300))) }, margin(l = 6))
        top.addView(pill("▼", TEXT, null, MUTED) { scroll.smoothScrollBy(0, maxOf(lastRowH, dp(300))) }, margin(l = 6))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Fila de interruptores y controles
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (a in AIS) {
            val chip = pill(a.name, a.color, null, a.color) {
                enabled[a.id] = enabled[a.id] != true
                prefs.edit().putBoolean("en_" + a.id, enabled[a.id] == true).apply()
                sync()
            }
            chips[a.id] = chip
            bar.addView(chip, margin(r = 6))
        }
        colsPill = pill("", MUTED, null, MUTED) {
            cols = (cols + 1) % 4
            prefs.edit().putInt("cols", cols).apply()
            updateColsLabel()
            relayout()
        }
        bar.addView(colsPill, margin(l = 8, r = 6))
        bar.addView(pill("↻ Nuevo chat", MUTED, null, MUTED) { reloadAll() }, margin(r = 6))
        bar.addView(pill("Historial", MUTED, null, MUTED) { showHistory() }, margin(r = 6))
        bar.addView(pill("ℹ Info", MUTED, null, MUTED) { showInfo() }, margin())
        val barScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }
        root.addView(barScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(8) })
        updateColsLabel()

        // Zona de paneles
        container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(container) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun updateColsLabel() {
        colsPill.text = "Columnas: " + (if (cols == 0) "auto" else cols.toString())
    }

    // ---------- Paneles ----------

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun makeWeb(a: Ai): WebView {
        val w = WebView(this)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString = ua
        w.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.addJavascriptInterface(Bridge(), "MAI")
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { onLoaded(a) }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    panes[a.id]?.loading = false
                    setStatus(a.id, "⚠ error de red: " + error?.description, false)
                }
            }
            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                    setStatus(a.id, "⚠ HTTP " + errorResponse?.statusCode, false)
                }
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                panes[a.id]?.loading = false
                setStatus(a.id, "⚠ error SSL (revisa fecha y hora)", false)
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (panes[a.id]?.loading == true && newProgress < 100) setStatus(a.id, "cargando " + newProgress + "%")
            }
        }
        w.postDelayed({
            if (panes[a.id]?.loading == true) setStatus(a.id, "⚠ tarda mucho · prueba ↻ o ℹ Info", false)
        }, 25000)
        // El WebView se desplaza solo, sin que lo robe el ScrollView exterior
        w.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        w.loadUrl(a.home)
        return w
    }

    private fun makePane(a: Ai): Pane {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(SURFACE)
            setPadding(dp(10), 0, dp(2), 0)
        }
        val name = TextView(this).apply {
            text = a.name
            textSize = 13f
            setTextColor(a.color)
            setTypeface(null, Typeface.BOLD)
        }
        val status = TextView(this).apply {
            text = "cargando…"
            textSize = 11f
            setTextColor(MUTED)
            maxLines = 1
            setPadding(dp(8), 0, 0, 0)
        }
        val web = makeWeb(a)
        val reloadBtn = TextView(this).apply {
            text = "↻"; textSize = 16f; setTextColor(MUTED); setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { reload(a.id) }
        }
        val maxBtn = TextView(this).apply {
            text = "⛶"; textSize = 16f; setTextColor(MUTED); setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { maxId = if (maxId == a.id) null else a.id; relayout() }
        }
        head.addView(name)
        head.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(reloadBtn)
        head.addView(maxBtn)
        root.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return Pane(a, root, web, status)
    }

    private fun sync() {
        for (a in AIS) {
            val on = enabled[a.id] == true
            if (on && !panes.containsKey(a.id)) panes[a.id] = makePane(a)
            if (!on && panes.containsKey(a.id)) {
                panes.remove(a.id)?.let {
                    (it.root.parent as? ViewGroup)?.removeView(it.root)
                    it.web.destroy()
                }
                pending.remove(a.id); ready.remove(a.id); sentUrl.remove(a.id)
                if (maxId == a.id) maxId = null
            }
            chips[a.id]?.alpha = if (on) 1f else 0.4f
        }
        if (this::scroll.isInitialized) relayout()
    }

    private fun relayout() {
        // Separa los paneles de sus filas (sin destruir los WebView) y reconstruye la cuadrícula
        for (p in panes.values) (p.root.parent as? ViewGroup)?.removeView(p.root)
        container.removeAllViews()

        val shown: List<Pane> = maxId?.let { id -> panes[id]?.let { listOf(it) } }
            ?: AIS.mapNotNull { panes[it.id] }
        if (shown.isEmpty()) return

        val n = shown.size
        val c = if (maxId != null) 1 else if (cols > 0) cols else if (n <= 2) n else 2
        val rows = (n + c - 1) / c
        val areaH = if (scroll.height > 0) scroll.height else resources.displayMetrics.heightPixels - dp(140)
        val rowH = if (maxId != null) areaH else maxOf(areaH / rows, dp(360))
        lastRowH = rowH

        for (r in 0 until rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#26262c"))
            }
            for (k in 0 until c) {
                val idx = r * c + k
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(1, 1, 1, 1) }
                if (idx < n) row.addView(shown[idx].root, lp)
                else row.addView(android.view.View(this), lp)
            }
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH))
        }
    }

    // ---------- Envío ----------

    private fun setStatus(id: String, text: String, ok: Boolean? = null) {
        val p = panes[id] ?: return
        p.status.text = text
        p.status.setTextColor(when (ok) {
            true -> Color.parseColor("#10a37f")
            false -> Color.parseColor("#D97757")
            null -> MUTED
        })
    }

    private fun inject(a: Ai, text: String) {
        val cfg = JSONObject().put("input", JSONArray(a.input)).put("send", JSONArray(a.send)).toString()
        val js = SEND_JS
            .replace("__CFG__", cfg)
            .replace("__TEXT__", JSONObject.quote(text))
            .replace("__ID__", JSONObject.quote(a.id))
        panes[a.id]?.web?.evaluateJavascript(js, null)
    }

    private fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(box.windowToken, 0)
        box.clearFocus()

        val h = loadHistory().toMutableList()
        h.remove(text); h.add(0, text)
        prefs.edit().putString("hist", JSONArray(h.take(10)).toString()).apply()

        // Respaldo: la pregunta queda en el portapapeles
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("pregunta", text))

        for ((id, p) in panes) {
            val a = p.ai
            if (a.byUrl != null) {
                sentUrl.add(id)
                p.loading = true
                setStatus(id, "enviando por URL…")
                p.web.loadUrl(a.byUrl + URLEncoder.encode(text, "UTF-8"))
            } else if (ready.contains(id)) {
                inject(a, text)
                setStatus(id, "enviando…")
            } else {
                pending[id] = text
                setStatus(id, "esperando carga…")
            }
        }
    }

    private fun onLoaded(a: Ai) {
        val p = panes[a.id] ?: return
        ready.add(a.id)
        val text = pending[a.id]
        if (a.byUrl == null && text != null) {
            pending.remove(a.id)
            inject(a, text)
            setStatus(a.id, "enviando…")
            p.loading = false
            return
        }
        if (p.loading) {
            p.loading = false
            if (sentUrl.contains(a.id)) setStatus(a.id, "✓ enviado (por URL)", true) else setStatus(a.id, "listo")
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun report(id: String, state: String) {
            runOnUiThread {
                if (state == "sent") setStatus(id, "✓ enviado", true)
                else setStatus(id, "⚠ no encontré el cuadro · inicia sesión o pega", false)
            }
        }
    }

    private fun reload(id: String) {
        val p = panes[id] ?: return
        sentUrl.remove(id); ready.remove(id); pending.remove(id)
        p.loading = true
        setStatus(id, "cargando…")
        p.web.loadUrl(p.ai.home)
    }

    private fun reloadAll() { for (id in panes.keys.toList()) reload(id) }

    private fun loadHistory(): List<String> {
        val arr = JSONArray(prefs.getString("hist", "[]"))
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun showInfo() {
        val pkg = if (android.os.Build.VERSION.SDK_INT >= 26) WebView.getCurrentWebViewPackage() else null
        val msg = "WebView: " + (pkg?.packageName ?: "?") + " " + (pkg?.versionName ?: "?") +
            "\nAndroid: " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")" +
            "\n\nUser-Agent:\n" + ua
        AlertDialog.Builder(this).setTitle("Diagnóstico").setMessage(msg).show()
    }

    private fun showHistory() {
        val h = loadHistory()
        if (h.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Historial")
            .setItems(h.toTypedArray()) { _, which -> box.setText(h[which]); box.setSelection(box.text.length) }
            .show()
    }

    // ---------- Ciclo de vida ----------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scroll.post { relayout() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (maxId != null) { maxId = null; relayout() } else super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        for (p in panes.values) p.web.destroy()
        super.onDestroy()
    }
}
