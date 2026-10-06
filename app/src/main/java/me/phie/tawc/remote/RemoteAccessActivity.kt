package me.phie.tawc.remote

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.phie.tawc.R
import me.phie.tawc.Settings
import me.phie.tawc.install.InstallationStore
import me.phie.tawc.install.distro.DistroRegistry
import me.phie.tawc.ui.Scaffold
import me.phie.tawc.ui.buildChildScreen
import me.phie.tawc.ui.destructiveButton
import me.phie.tawc.ui.primaryButton
import me.phie.tawc.ui.tawcCard
import me.phie.tawc.ui.verticalLp
import java.io.IOException

/**
 * ⋮ → Remote access for one install: start/stop the agent and show how
 * to connect. State lives in [RemoteSession], so rotation and leaving the
 * screen change nothing. See notes/remote-access.md.
 */
class RemoteAccessActivity : AppCompatActivity() {

    private val store by lazy { InstallationStore(this) }
    private lateinit var installId: String
    private lateinit var scaffold: Scaffold
    private lateinit var body: LinearLayout
    private var starting = false
    private var shown: RemoteState? = null
    private var uiJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installId = intent?.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        scaffold = buildChildScreen(getString(R.string.title_remote_access))
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // A readable column on wide screens (landscape, tablets).
        val maxWidth = dp(MAX_WIDTH_DP)
        val width = if (resources.displayMetrics.widthPixels > maxWidth) maxWidth else MATCH_PARENT
        scaffold.content.addView(
            ScrollView(this).apply {
                addView(
                    FrameLayout(this@RemoteAccessActivity).apply {
                        addView(body, FrameLayout.LayoutParams(width, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
                    },
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT),
                )
            },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
        )
        setContentView(scaffold.root)
    }

    override fun onStart() {
        super.onStart()
        uiJob = lifecycleScope.launch { RemoteSession.state.collect { render(it) } }
    }

    override fun onStop() {
        uiJob?.cancel()
        uiJob = null
        super.onStop()
    }

    private fun label(id: String): String =
        store.load(id)?.let { DistroRegistry.displayLabel(it) } ?: id

    private fun render(state: RemoteState) {
        if (state == shown) return
        shown = state
        body.removeAllViews()
        when {
            state.running && state.distroId != installId -> renderOther(state)
            state.running -> renderRunning(state.status)
            else -> renderIdle(state)
        }
    }

    /** Choices and drafts are saved to [Settings] as they change, so
     *  re-renders, rotation and the next visit keep them. */
    private fun renderIdle(state: RemoteState) {
        val gap = dp(16)
        body.addView(text(getString(R.string.remote_intro)), verticalLp(MATCH_PARENT, WRAP_CONTENT, dp(4)))

        val relay = field(Settings.remoteRelay, getString(R.string.remote_relay_hint), uri = true) { Settings.remoteRelay = it }
        body.addView(radios(
            listOf(
                Settings.REMOTE_MODE_LOCAL to getString(R.string.remote_mode_local),
                Settings.REMOTE_MODE_RELAY to getString(R.string.remote_mode_relay),
            ),
            Settings.remoteMode,
        ) { mode ->
            Settings.remoteMode = mode
            relay.visibility = if (mode == Settings.REMOTE_MODE_RELAY) View.VISIBLE else View.GONE
        })
        body.addView(relay, verticalLp(MATCH_PARENT, WRAP_CONTENT, dp(4)))
        relay.visibility = if (Settings.remoteMode == Settings.REMOTE_MODE_RELAY) View.VISIBLE else View.GONE

        body.addView(secondary(getString(R.string.remote_login)), verticalLp(MATCH_PARENT, WRAP_CONTENT, 0).also { it.topMargin = dp(12) })
        val user = field(Settings.remoteKeyUser, "", uri = true) { Settings.remoteKeyUser = it.trim() }
        val paste = field(Settings.remotePastedKey, getString(R.string.remote_key_paste_hint), multiline = true) {
            Settings.remotePastedKey = it
        }
        fun showLogin(login: String) {
            val host = KeyHost.fromKey(login)
            user.visibility = if (host != null) View.VISIBLE else View.GONE
            user.hint = host?.let { getString(R.string.remote_key_user_hint, it.label) }
            paste.visibility = if (login == Settings.REMOTE_LOGIN_PASTE) View.VISIBLE else View.GONE
        }
        val logins = buildList {
            add(Settings.REMOTE_LOGIN_SECRET to getString(R.string.remote_login_secret))
            KeyHost.entries.forEach { add(it.key to it.label) }
            add(Settings.REMOTE_LOGIN_PASTE to getString(R.string.remote_login_paste))
        }
        body.addView(radios(logins, Settings.remoteLogin) { login ->
            Settings.remoteLogin = login
            showLogin(login)
        })
        body.addView(user, verticalLp(MATCH_PARENT, WRAP_CONTENT, dp(4)))
        body.addView(paste, verticalLp(MATCH_PARENT, WRAP_CONTENT, dp(4)))
        showLogin(Settings.remoteLogin)

        body.addView(
            CheckBox(this).apply {
                text = getString(R.string.remote_idle_close)
                isChecked = Settings.remoteIdleClose
                setOnCheckedChangeListener { _, checked -> Settings.remoteIdleClose = checked }
            },
            verticalLp(MATCH_PARENT, WRAP_CONTENT, gap).also { it.topMargin = dp(8) },
        )

        // Only failures; a Stop or idle close needs no explanation.
        val last = state.status
        if (state.distroId == installId && last.state == "failed" && last.error.isNotEmpty()) {
            val v = secondary(last.error).apply { setTextColor(getColor(R.color.tawc_danger)) }
            body.addView(v, verticalLp(MATCH_PARENT, WRAP_CONTENT, gap))
        }
        body.addView(
            primaryButton(getString(if (starting) R.string.remote_starting else R.string.remote_start)) { start() }
                .apply { isEnabled = !starting },
            verticalLp(MATCH_PARENT, WRAP_CONTENT),
        )
    }

    /** A radio group over (key, label) pairs; [onPick] also runs for the
     *  initial selection's views via the caller. */
    private fun radios(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): RadioGroup =
        RadioGroup(this).apply {
            for ((key, label) in options) {
                addView(RadioButton(this@RemoteAccessActivity).apply {
                    id = View.generateViewId()
                    text = label
                    isChecked = key == selected
                    setOnClickListener { onPick(key) }
                })
            }
        }

    private fun field(
        value: String,
        hint: String,
        uri: Boolean = false,
        multiline: Boolean = false,
        onChange: (String) -> Unit,
    ): EditText = EditText(this).apply {
        setText(value)
        this.hint = hint
        if (multiline) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
            maxLines = 5
            typeface = Typeface.MONOSPACE
            textSize = 12f
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                (if (uri) InputType.TYPE_TEXT_VARIATION_URI else 0)
            isSingleLine = true
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onChange(s?.toString() ?: "")
        })
    }

    private fun renderOther(state: RemoteState) {
        val other = state.distroId ?: return
        body.addView(text(getString(R.string.remote_running_other, label(other))), verticalLp(MATCH_PARENT, WRAP_CONTENT, dp(16)))
        body.addView(destructiveButton(getString(R.string.remote_stop)) { RemoteSession.stop() }, verticalLp(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun renderRunning(s: RemoteStatus) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (s.state == "ready") {
            row.addView(text(getString(R.string.remote_ready)).apply {
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.tawc_success))
            })
            row.addView(
                text(resources.getQuantityString(R.plurals.remote_connections, s.clients, s.clients)),
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also { it.marginStart = dp(12) },
            )
        } else {
            val msg = if (s.state == "reconnecting") R.string.remote_status_reconnecting else R.string.remote_status_connecting
            row.addView(text(getString(msg)).apply { setTypeface(typeface, Typeface.BOLD) })
        }
        body.addView(row, verticalLp(MATCH_PARENT, WRAP_CONTENT, dp(8)))
        if (s.command.isNotEmpty()) {
            body.addView(commandCard(s.command), verticalLp(MATCH_PARENT, WRAP_CONTENT, dp(8)))
            if (s.keySource.isNotEmpty()) {
                body.addView(secondary(getString(R.string.remote_keys_from, s.keySource)), verticalLp(MATCH_PARENT, WRAP_CONTENT, dp(4)))
            }
            body.addView(secondary(getString(R.string.remote_fingerprint)), verticalLp(MATCH_PARENT, WRAP_CONTENT, dp(8)))
            body.addView(fingerprintLine(s.fingerprint), verticalLp(MATCH_PARENT, WRAP_CONTENT))
        }
        if (s.secretDisabled) {
            body.addView(
                text(getString(R.string.remote_secret_disabled)).apply { setTextColor(getColor(R.color.tawc_danger)) },
                verticalLp(MATCH_PARENT, WRAP_CONTENT, dp(8)),
            )
        }
        if (s.notice.isNotEmpty()) {
            body.addView(secondary(s.notice), verticalLp(MATCH_PARENT, WRAP_CONTENT, dp(8)))
        }
        body.addView(View(this), verticalLp(MATCH_PARENT, dp(8)))
        body.addView(destructiveButton(getString(R.string.remote_stop)) { RemoteSession.stop() }, verticalLp(MATCH_PARENT, WRAP_CONTENT))
    }

    private fun start() {
        if (starting) return
        starting = true
        val idle = if (Settings.remoteIdleClose) Settings.REMOTE_IDLE_SECONDS else 0L
        val mode = Settings.remoteMode
        val relay = Settings.remoteRelay.trim().ifEmpty { Settings.DEFAULT_REMOTE_RELAY }
        val loginKey = Settings.remoteLogin
        val keyUser = Settings.remoteKeyUser
        val pasted = Settings.remotePastedKey
        shown = null
        render(RemoteSession.state.value)
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val transport = if (mode == Settings.REMOTE_MODE_RELAY) {
                        RemoteSession.Transport.Relay(relay)
                    } else {
                        val addrs = LocalNetwork.addresses(applicationContext)
                        if (addrs.isEmpty()) throw IOException(getString(R.string.remote_no_local_network))
                        RemoteSession.Transport.Local(addrs)
                    }
                    val host = KeyHost.fromKey(loginKey)
                    val login = when {
                        host != null -> {
                            if (keyUser.isEmpty()) throw IOException(getString(R.string.remote_no_key_user, host.label))
                            RemoteSession.Login.Keys(host.fetch(keyUser), "${host.domain}/$keyUser")
                        }
                        loginKey == Settings.REMOTE_LOGIN_PASTE -> {
                            if (pasted.isBlank()) throw IOException(getString(R.string.remote_no_pasted_key))
                            RemoteSession.Login.Keys(pasted, "pasted key")
                        }
                        else -> RemoteSession.Login.Secret
                    }
                    val req = RemoteSession.buildRequest(applicationContext, installId, transport, login, idle)
                    RemoteSession.start(installId, req)
                } catch (e: Exception) {
                    // IOException: fetch, spawn prep, no network. Anything
                    // else is a bug, but a message beats a crash.
                    e.message ?: e.toString()
                }
            }
            starting = false
            if (error != null) Toast.makeText(this@RemoteAccessActivity, error, Toast.LENGTH_LONG).show()
            shown = null
            render(RemoteSession.state.value)
        }
    }

    /** Monospace command; if one line doesn't fit, the destination moves
     *  to a `\\`-continued second line, which still scrolls sideways
     *  rather than wrapping. Tap to copy the one-line form (the clip is
     *  marked sensitive: it holds the secret). */
    private fun commandCard(command: String): View {
        val card = tawcCard()
        val split = command.lastIndexOf(' ')
        val wrapped = if (split > 0) "${command.substring(0, split)} \\\n  ${command.substring(split + 1)}" else command
        val tv = TextView(this).apply {
            text = command
            typeface = Typeface.MONOSPACE
            textSize = COMMAND_SP
            setHorizontallyScrolling(true)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { copy(command) }
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tv)
        }
        scroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val fits = tv.paint.measureText(command) <= v.width - tv.totalPaddingLeft - tv.totalPaddingRight
            val want = if (fits) command else wrapped
            if (tv.text.toString() != want) v.post { tv.text = want }
        }
        card.addView(scroll)
        return card
    }

    /** The fingerprint on its own monospace line: base64's `/` and `+`
     *  are break points, so wrapping would split it at random. Scrolls
     *  sideways if it doesn't fit. */
    private fun fingerprintLine(fingerprint: String): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(secondary(fingerprint).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setHorizontallyScrolling(true)
            setTextIsSelectable(true)
        })
    }

    private fun copy(command: String) {
        val clip = ClipData.newPlainText(getString(R.string.title_remote_access), command)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        // Android 13+ shows its own copy confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, R.string.remote_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun text(s: String) = TextView(this).apply {
        text = s
        textSize = 14f
    }

    private fun secondary(s: String) = text(s).apply {
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ID = "id"
        private const val MAX_WIDTH_DP = 600
        private const val COMMAND_SP = 16f
    }
}
