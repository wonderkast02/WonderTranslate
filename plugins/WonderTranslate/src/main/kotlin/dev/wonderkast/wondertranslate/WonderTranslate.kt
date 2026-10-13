package dev.wonderkast.wondertranslate

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import com.lytefast.flexinput.fragment.FlexInputFragment
import com.aliucord.CollectionUtils
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.databinding.WidgetChatListBinding
import com.discord.databinding.WidgetHomeBinding
import com.discord.widgets.home.WidgetHome
import com.discord.widgets.home.WidgetHomeHeaderManager
import com.discord.widgets.home.WidgetHomeModel
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WonderTranslate: manual ON/OFF per channel; automatic translation of messages as
 * Discord loads them. The original Discord message objects are NEVER modified.
 *
 * Third-party disclosure: only after opt-in, message text is transmitted to
 * translate.googleapis.com using an unofficial Google Translate endpoint.
 */
@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class WonderTranslate : Plugin() {
    private data class Translated(val original: String, val language: String, val rendered: String)
    private data class TextKey(val language: String, val content: String)
    private data class RequestKey(val id: Long, val language: String, val original: String)

    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LinkedHashMap<Long, Translated>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Translated>?): Boolean = size > 400
    }
    private val cacheLock = Any()
    // In-memory only: originals stay in the app process, never in diagnostic files.
    private val textCache = object : LinkedHashMap<TextKey, String>(192, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextKey, String>?): Boolean = size > 192
    }
    private val retryAfter = object : LinkedHashMap<RequestKey, Long>(240, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RequestKey, Long>?): Boolean = size > 240
    }
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<RequestKey>()
    // Exactly one worker for gentle rate-limiting. Visible messages enter at the head.
    private val taskQueue = LinkedBlockingDeque<Runnable>(240)
    private fun freshWorker() = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, taskQueue).apply {
        prestartAllCoreThreads()
    }
    private var work = freshWorker()
    private val textReuses = AtomicInteger()
    private val staleDrops = AtomicInteger()
    private val priorityPromotions = AtomicInteger()
    private val queueEvictions = AtomicInteger()
    private val throttledRequests = AtomicInteger()
    private val waitTotalMs = AtomicLong()
    private val waitSamples = AtomicInteger()
    // Only one delayed resume is kept; callbacks are removed when plugin stops.
    private var delayedRateResume: Runnable? = null
    private val configured = AtomicInteger()
    private val configureEntered = AtomicInteger()
    private val configureExceptions = AtomicInteger()
    private val textEntered = AtomicInteger()
    private val textExceptions = AtomicInteger()
    @Volatile private var configureStage = "NEVER"
    @Volatile private var configureError = "NONE"
    @Volatile private var textStage = "NEVER"
    @Volatile private var textError = "NONE"
    @Volatile private var probeError = "NONE"
    private val textHooks = AtomicInteger()
    private val scheduled = AtomicInteger()
    private val startedRequests = AtomicInteger()
    private val successes = AtomicInteger()
    private val failures = AtomicInteger()
    private val displays = AtomicInteger()
    private val redraws = AtomicInteger()
    private val noChat = AtomicInteger()
    // R13: error categories and hook stages are also counters, without content, no content, no channel IDs and no message IDs.
    private val blankContent = AtomicInteger()
    private val oversizeContent = AtomicInteger()
    private val cacheHits = AtomicInteger()
    private val duplicates = AtomicInteger()
    private val eligible = AtomicInteger()
    private val hookBlank = AtomicInteger()
    private val hookNonBlank = AtomicInteger()
    // Automatic probe is executed only after the user has accepted third-party translation.
    private val automaticProbeStarted = AtomicBoolean(false)
    private val probeAttempts = AtomicInteger()
    private val probeSuccesses = AtomicInteger()
    private val probeFailures = AtomicInteger()
    @Volatile private var lastProbe = "NEVER"
    @Volatile private var lastReason = "NONE"
    private var lastDiagWrite = 0L
    private val api = TranslationClient()
    private var chat = WeakReference<WidgetChatList>(null)
    private var toggleButton = WeakReference<TextView>(null)
    // R23: writable channels use the original composer control, read-only channels use floating fallback.
    private var composerButton = WeakReference<TextView>(null)
    private var buttonChannel: Long = -1L
    // R16: The channel control is attached to the chat's visible area, not the composer.
    // A read-only channel has no composer but still has WidgetChatList.
    private var floatingHost: FrameLayout? = null
    private var floatingAnchor: View? = null
    private var floatingChat = WeakReference<WidgetChatList>(null)
    // R28: independently bound to the home header, which also exists in announcement feeds.
    private var homeHeaderAnchor = WeakReference<View>(null)
    private var floatingOnHeader = false
    private var floatingLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var floatingDetachListener: View.OnAttachStateChangeListener? = null
    // Prevent repeated reattachment posts while RecyclerView rebinds rows.
    private val floatingRecoveryQueued = AtomicBoolean(false)
    @Volatile private var running = false
    @Volatile private var blockedUntil = 0L

    private val textBuilderField by lazy {
        SimpleDraweeSpanTextView::class.java.getDeclaredField("mDraweeStringBuilder").apply {
            isAccessible = true
        }
    }

    override fun start(context: Context) {
        if (work.isShutdown) {
            taskQueue.clear()
            pending.clear()
            work = freshWorker()
        } // allow same-session reactivation
        running = true
        automaticProbeStarted.set(false)
        lastReason = "STARTED"
        persistDiagnostic(true)
        patchMessageText()
        patchVisibleMessages()
        patchChatList()
        try { patchHomeHeader() } catch (t: Throwable) { logger.error(t) }
        try { patchComposerButton() } catch (t: Throwable) { logger.error(t) }
        // Float remains available only when the composer is absent or hidden.
        // No chat commands and no manual diagnostics. Previously accepted consent is reused.
        // The probe only uses the public, fixed phrase "Hello world".
        main.post {
            try {
                if (isEnabled(selectedChannel()) && settings.getBool("external_translation_consent", false)) {
                    startAutomaticProbe()
                }
            } catch (t: Throwable) { logger.error(t) }
        }
    }

    override fun stop(context: Context) {
        running = false
        patcher.unpatchAll()
        commands.unregisterAll()
        work.shutdownNow()
        pending.clear()
        main.post {
            delayedRateResume?.let { main.removeCallbacks(it) }
            delayedRateResume = null
            removeFloatingButton()
            homeHeaderAnchor.clear()
            floatingChat.clear()
            composerButton.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
            composerButton.clear()
            redrawLoadedMessages()
        }
        synchronized(cacheLock) { cache.clear(); textCache.clear(); retryAfter.clear() }
        lastReason = "STOPPED"
        persistDiagnostic(true)
    }

    // Only counters, status codes and fixed categories: NEVER message text, user IDs or channel IDs.
    private fun diagnostic(): String = "R20; running=$running; enabled=${isEnabled(selectedChannel())}; consent=${settings.getBool("external_translation_consent", false)}; configured=${configured.get()}; hooks=${textHooks.get()}; hook_blank=${hookBlank.get()}; hook_text=${hookNonBlank.get()}; cfg_enter=${configureEntered.get()}; cfg_errs=${configureExceptions.get()}; cfg_stage=$configureStage; cfg_error=$configureError; txt_enter=${textEntered.get()}; txt_errs=${textExceptions.get()}; txt_stage=$textStage; txt_error=$textError; probe_error=$probeError; eligible=${eligible.get()}; skip_blank=${blankContent.get()}; skip_long=${oversizeContent.get()}; skip_cache=${cacheHits.get()}; skip_pending=${duplicates.get()}; queued=${scheduled.get()}; attempts=${startedRequests.get()}; success=${successes.get()}; failed=${failures.get()}; render=${displays.get()}; redraw=${redraws.get()}; no_chat=${noChat.get()}; probe=${probeAttempts.get()}; probe_ok=${probeSuccesses.get()}; probe_fail=${probeFailures.get()}; probe_last=$lastProbe; r19_queue=${taskQueue.size}; r19_text_reuses=${textReuses.get()}; r19_stale=${staleDrops.get()}; r19_evicted=${queueEvictions.get()}; r19_promoted=${priorityPromotions.get()}; r19_rate_paused=${throttledRequests.get()}; r19_wait_avg_ms=${if (waitSamples.get() > 0) waitTotalMs.get() / waitSamples.get() else 0}; last=$lastReason"

    private fun persistDiagnostic(force: Boolean = false) {
        main.post {
            val now = System.currentTimeMillis()
            if (force || now - lastDiagWrite >= 1800L) {
                lastDiagWrite = now
                try { settings.setString("r13_diagnostic", diagnostic()) } catch (t: Throwable) { logger.error(t) }
            }
        }
    }

    // One privacy-safe connection self-test per plugin activation. No Discord content is used.
    private fun startAutomaticProbe() {
        if (!running || !settings.getBool("external_translation_consent", false)) return
        if (!automaticProbeStarted.compareAndSet(false, true)) return
        try {
            work.execute {
                probeAttempts.incrementAndGet()
                lastProbe = "RUNNING"
                persistDiagnostic(true)
                try {
                    when (val response = api.translate("Hello world", "pt-BR")) {
                        is TranslationClient.Result.Success -> {
                            val empty = response.text.length == 0
                            lastProbe = if (empty) "EMPTY_RESPONSE" else "OK"
                            probeSuccesses.incrementAndGet()
                        }
                        is TranslationClient.Result.Failure -> {
                            probeFailures.incrementAndGet()
                            lastProbe = if (response.code > 0) "HTTP_${response.code}" else response.category
                        }
                    }
                } catch (t: Throwable) {
                    probeFailures.incrementAndGet()
                    probeError = t.javaClass.simpleName.take(52).ifEmpty { "UNKNOWN" }
                    lastProbe = "PROBE_EXCEPTION"
                    logger.error(t)
                } finally {
                    persistDiagnostic(true)
                }
            }
        } catch (_: RejectedExecutionException) {
            probeFailures.incrementAndGet()
            lastProbe = "QUEUE_FULL_OR_SHUTDOWN"
            persistDiagnostic(true)
        }
    }

    private fun reportFailure(reason: String) {
        lastReason = reason // fixed reason/category only, no exception messages
        failures.incrementAndGet()
        persistDiagnostic(true)
        main.post {
            val now = System.currentTimeMillis()
            if (now - lastToastAt >= 30_000L) {
                lastToastAt = now
                Utils.showToast("WonderTranslate: falha $reason. Diagnóstico salvo automaticamente.", true)
            }
        }
    }

    private var lastToastAt = 0L

    private fun selectedChannel() = StoreStream.getChannelsSelected().id
    private fun target() = settings.getString("target_language", "pt-BR") ?: "pt-BR"
    private fun isEnabled(channel: Long) = settings.getBool("enabled_channel_$channel", false)

    private fun requestToggle(channel: Long, context: Context) {
        if (channel <= 0) return
        if (!isEnabled(channel) && !settings.getBool("external_translation_consent", false)) {
            AlertDialog.Builder(context)
                .setTitle("Tradução externa de mensagens")
                .setMessage("Ao ativar, os textos das mensagens deste canal serão enviados ao serviço externo Google Translate (endpoint não oficial). Pode incluir mensagens privadas. Nada será enviado ao Discord pelo plugin. Deseja permitir?")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Permitir") { _, _ ->
                    settings.setBool("external_translation_consent", true)
                    changeChannelState(channel, true)
                }.show()
        } else {
            changeChannelState(channel, !isEnabled(channel))
        }
    }

    private fun changeChannelState(channel: Long, active: Boolean) {
        settings.setBool("enabled_channel_$channel", active)
        lastReason = if (active) "CHANNEL_ENABLED" else "CHANNEL_DISABLED"
        persistDiagnostic(true)
        updateButton()
        redrawLoadedMessages()
        pruneQueuedTasks(if (active) channel else -1L)
        if (active) {
            startAutomaticProbe()
            primeLoadedChannel(channel)
        }
        if (active) {
            Utils.showToast("Tradução automática ligada neste canal")
        } else {
            Utils.showToast("Texto original restaurado neste canal")
        }
    }

    private fun dp(ctx: Context, value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics
    ).toInt()


    // Floating fallback when a writable composer is not visible.
    // The RecyclerView anchor also works in read-only/announcement channels.
    // Reuses the R13 Aliucord Legacy composer hook, verified on writable channels.
    // Never inject into a read-only chat (there is no writable composer toolbar).
    private fun patchComposerButton() {
        patcher.patch(
            FlexInputFragment::class.java.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java),
            Hook { hook ->
                try {
                    val root = hook.args[0] as? LinearLayout ?: return@Hook
                    val outer = root.getChildAt(1) as? RelativeLayout ?: return@Hook
                    val toolbar = outer.getChildAt(0) as? LinearLayout ?: return@Hook
                    val existing = toolbar.findViewWithTag<TextView>(COMPOSER_TAG)
                    val button = existing ?: TextView(toolbar.context).apply {
                        tag = COMPOSER_TAG
                        gravity = Gravity.CENTER
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        setPadding(dp(context, 8f), 0, dp(context, 8f), 0)
                        minWidth = dp(context, 55f)
                        contentDescription = "Tradução do chat; toque para alternar, segure para escolher idioma."
                        setOnClickListener { requestToggle(selectedChannel(), this.context) }
                        setOnLongClickListener { showLanguagePicker(this.context); true }
                    }.also {
                        toolbar.addView(it, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, dp(toolbar.context, 36f),
                        ))
                    }
                    composerButton = WeakReference(button)
                    updateButton()
                    positionFloatingButton()
                } catch (t: Throwable) {
                    logger.error(t)
                    // The floating control remains a fallback; never break Discord's composer.
                }
            },
        )
    }

    // R28: The home header is configured even for read-only announcement feeds
    // with a Follow button; unlike the RecyclerView it does not depend on a
    // message row being rendered. This hook is also used by Aliucord plugins.
    private fun patchHomeHeader() {
        patcher.patch(
            WidgetHomeHeaderManager::class.java,
            "configure",
            arrayOf(WidgetHome::class.java, WidgetHomeModel::class.java, WidgetHomeBinding::class.java),
            Hook { frame ->
                try {
                    val model = frame.args[1] as? WidgetHomeModel ?: return@Hook
                    if (model.channel == null) return@Hook
                    val home = frame.args[0] as? WidgetHome ?: return@Hook
                    val header: View = home.toolbar
                    homeHeaderAnchor = WeakReference(header)
                    main.post {
                        if (running && homeHeaderAnchor.get() === header) {
                            attachFloatingControl(header, true)
                            updateButton()
                        }
                    }
                } catch (t: Throwable) {
                    logger.error(t)
                    // Chat-list fallback remains available if this hook changes.
                }
            },
        )
    }

    private fun attachFloatingButton(chatList: WidgetChatList) {
        if (!running) return
        floatingChat = WeakReference(chatList)
        val header = homeHeaderAnchor.get()
        if (header != null && header.isShown) {
            attachFloatingControl(header, true)
            return
        }
        try {
            val root = chatList.view
            // The root is often more reliable than the recycler in special chat
            // layouts. Only fall back to the recycler if no root is available.
            val anchor: View = root ?: run {
                val accessor = WidgetChatList::class.java.getDeclaredMethod("getBinding")
                    .apply { isAccessible = true }
                val binding = accessor.invoke(chatList) as? WidgetChatListBinding ?: return
                binding.b
            }
            attachFloatingControl(anchor, false)
        } catch (t: Throwable) { logger.error(t) }
    }

    private fun attachFloatingControl(anchor: View, headerMode: Boolean) {
        if (!running) return
        try {
            val host = Utils.appActivity.findViewById<FrameLayout>(android.R.id.content) ?: return
            if (anchor === floatingAnchor && host === floatingHost && toggleButton.get()?.parent === host) {
                positionFloatingButton()
                return
            }
            removeFloatingButton()
            val button = TextView(host.context).apply {
                tag = BUTTON_TAG
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(Color.WHITE)
                minWidth = dp(context, 64f)
                setPadding(dp(context, 10f), 0, dp(context, 10f), 0)
                elevation = dp(context, 4f).toFloat()
                contentDescription = "Tradução do chat. Toque para alternar e segure para escolher idioma."
                setOnClickListener { requestToggle(selectedChannel(), this.context) }
                setOnLongClickListener { showLanguagePicker(this.context); true }
            }
            host.addView(button, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(host.context, 34f),
                Gravity.TOP or Gravity.END,
            ))
            floatingHost = host
            floatingAnchor = anchor
            floatingOnHeader = headerMode
            toggleButton = WeakReference(button)
            buttonChannel = -1L
            floatingLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                positionFloatingButton()
            }.also { host.viewTreeObserver.addOnGlobalLayoutListener(it) }
            floatingDetachListener = object : View.OnAttachStateChangeListener {
                // A RecyclerView may detach/re-attach while the same chat is
                // still alive. Removing the floating button on every detach
                // loses the control until another onViewBoundOrOnResume.
                override fun onViewAttachedToWindow(v: View) {
                    main.post {
                        if (running && floatingAnchor === v) {
                            updateButton()
                            positionFloatingButton()
                        }
                    }
                }
                override fun onViewDetachedFromWindow(v: View) {
                    main.post {
                        if (floatingAnchor === v) {
                            positionFloatingButton()
                            // Reattach is performed by header configure or the
                            // list hook; never show controls on a detached view.
                        }
                    }
                }
            }.also { anchor.addOnAttachStateChangeListener(it) }
            updateButton()
            positionFloatingButton()
        } catch (t: Throwable) {
            logger.error(t)
            removeFloatingButton()
            // A failed UI hook never affects Discord messages or the existing plugin state.
        }
    }

    // An anchor may be detached by Discord without another chat-onResume hook.
    // Repair a missing button on subsequent message binds, once per UI cycle.
    private fun recoverFloatingButtonIfMissing() {
        if (!running) return
        val button = toggleButton.get()
        val header = homeHeaderAnchor.get()
        val needHeader = header != null && header.isShown && header.isAttachedToWindow &&
            floatingAnchor !== header
        if (button != null && button.parent === floatingHost && !needHeader) return
        if (!floatingRecoveryQueued.compareAndSet(false, true)) return
        main.post {
            try {
                if (running) {
                    val header = homeHeaderAnchor.get()
                    if (header != null && header.isShown) attachFloatingControl(header, true)
                    else chat.get()?.let { attachFloatingButton(it) }
                }
            } finally {
                floatingRecoveryQueued.set(false)
            }
        }
    }

    // R24: decide which UI belongs to the selected channel from Discord's actual
    // SEND_MESSAGES permissions; a visible stale composer is NOT sufficient.
    // null means the store has not resolved the channel yet. In that case, use
    // the real composer visibility as a transient fallback (including DMs).
    private fun currentChannelWritable(): Boolean? {
        return try {
            // StoreChannelsSelected exposes this ID; Channel.id itself is private
            // in the Aliucord Legacy Discord SDK and must never be read here.
            val channelId = selectedChannel()
            if (channelId <= 0L) return null
            val channel = StoreStream.getChannelsSelected().selectedChannel
            val permissions = StoreStream.getPermissions().permissionsByChannel[channelId]
                ?: return null
            PermissionUtils.INSTANCE.hasAccessWrite(channel, permissions)
        } catch (_: Throwable) {
            // Do not risk breaking the chat if an Aliucord fork changes its
            // permission API. A missing permission is never logged with IDs.
            null
        }
    }

    private fun positionFloatingButton() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { positionFloatingButton() }
            return
        }
        val host = floatingHost ?: return
        val anchor = floatingAnchor ?: return
        val button = toggleButton.get() ?: return
        // Repair a list-root fallback when the channel header becomes visible,
        // even if no new message row is bound. Schedule outside layout traversal.
        val currentHeader = homeHeaderAnchor.get()
        if (currentHeader != null && currentHeader !== anchor && currentHeader.isShown &&
            currentHeader.isAttachedToWindow && floatingRecoveryQueued.compareAndSet(false, true)) {
            main.post {
                try {
                    if (running && homeHeaderAnchor.get() === currentHeader)
                        attachFloatingControl(currentHeader, true)
                } finally { floatingRecoveryQueued.set(false) }
            }
            return
        }
        val writable = currentChannelWritable()
        val composer = composerButton.get()
        // Hide only OUR injected composer button in read-only channels. Discord's
        // own input and permissions are never modified. Restore it on writable
        // channels, including after fragment reuse and permission changes.
        if (composer != null && composer.parent != null) {
            val next = if (writable == false) View.GONE else View.VISIBLE
            if (composer.visibility != next) composer.visibility = next
        }
        val composerUsable = writable != false && composer?.let {
            it.parent != null && it.isShown && it.visibility == View.VISIBLE &&
                it.getGlobalVisibleRect(Rect())
        } == true
        val visible = try {
            running && anchor.isAttachedToWindow && anchor.isShown && anchor.width > 0 &&
                anchor.height > 0 && anchor.getGlobalVisibleRect(Rect()) && selectedChannel() > 0L &&
                !composerUsable
        } catch (t: Throwable) {
            logger.error(t)
            false
        }
        val nextVisibility = if (visible) View.VISIBLE else View.GONE
        if (button.visibility != nextVisibility) button.visibility = nextVisibility
        if (!visible || host.width <= 0) return
        val anchorXY = IntArray(2)
        val hostXY = IntArray(2)
        anchor.getLocationInWindow(anchorXY)
        host.getLocationInWindow(hostXY)
        val rightMargin = (host.width - (anchorXY[0] - hostXY[0] + anchor.width) +
            dp(host.context, 12f)).coerceAtLeast(dp(host.context, 8f))
        // Header mode positions just BELOW the toolbar, never on top of the
        // channel name/search controls. A chat-root anchor uses its normal top.
        val topMargin = (anchorXY[1] - hostXY[1] +
            (if (floatingOnHeader) anchor.height else 0) + dp(host.context, 8f)).coerceAtLeast(0)
        val params = button.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.rightMargin != rightMargin || params.topMargin != topMargin) {
            params.rightMargin = rightMargin
            params.topMargin = topMargin
            button.layoutParams = params
        }
    }

    private fun removeFloatingButton() {
        val host = floatingHost
        val anchor = floatingAnchor
        floatingLayoutListener?.let { listener ->
            if (host?.viewTreeObserver?.isAlive == true) {
                host.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }
        floatingDetachListener?.let { listener ->
            anchor?.removeOnAttachStateChangeListener(listener)
        }
        toggleButton.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        toggleButton.clear()
        floatingLayoutListener = null
        floatingDetachListener = null
        floatingHost = null
        floatingAnchor = null
        floatingOnHeader = false
        // Retain the last chat-list reference for a safe fallback.
    }

    private fun updateButton() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { updateButton() }
            return
        }
        val channel = selectedChannel()
        val enabled = isEnabled(channel)
        val title = if (enabled) "🌐 ON" else "🌐 OFF"
        if (buttonChannel != channel) pruneQueuedTasks(channel)
        buttonChannel = channel
        // Both buttons reflect the same per-channel setting even across fragment reuse.
        updateButtonVisual(composerButton.get(), title, enabled)
        updateButtonVisual(toggleButton.get(), title, enabled)
        // Re-evaluate permission after a channel switch. On a read-only channel,
        // the previous composer can remain attached to an off-screen fragment.
        positionFloatingButton()
    }

    private fun updateButtonVisual(button: TextView?, title: String, enabled: Boolean) {
        if (button == null || button.parent == null) return
        if (button.text.toString() != title) {
            button.text = title
            button.background = GradientDrawable().apply {
                cornerRadius = dp(button.context, 14f).toFloat()
                setColor(Color.parseColor(if (enabled) "#197E70" else "#394351"))
            }
        }
        button.contentDescription = "Tradução ${if (enabled) "ligada" else "desligada"} para ${target()}. Toque para alternar ou segure para escolher idioma."
    }

    private fun showLanguagePicker(context: Context) {
        val names = arrayOf("Português (Brasil)", "English", "Español", "Русский", "Français", "Deutsch", "Italiano", "日本語", "한국어", "中文", "العربية", "हिन्दी")
        val codes = arrayOf("pt-BR", "en", "es", "ru", "fr", "de", "it", "ja", "ko", "zh-CN", "ar", "hi")
        val current = codes.indexOf(target()).coerceAtLeast(0)
        AlertDialog.Builder(context)
            .setTitle("Idioma de destino")
            .setSingleChoiceItems(names, current) { dialog, which ->
                settings.setString("target_language", codes[which])
                pruneQueuedTasks(selectedChannel())
                persistDiagnostic(true)
                dialog.dismiss()
                redrawLoadedMessages()
                updateButton()
                Utils.showToast("Idioma alterado para ${names[which]}")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun patchChatList() {
        patcher.patch(WidgetChatList::class.java.getDeclaredConstructor(), Hook { frame ->
            chat = WeakReference(frame.thisObject as WidgetChatList)
            lastReason = "CHAT_CONSTRUCTED"
            persistDiagnostic()
        })
        patcher.patch(WidgetChatList::class.java, "onViewBoundOrOnResume", emptyArray<Class<*>>(), Hook { frame ->
            val chatList = frame.thisObject as WidgetChatList
            chat = WeakReference(chatList)
            lastReason = "CHAT_BOUND"
            persistDiagnostic(true)
            main.post { if (running) attachFloatingButton(chatList) }
        })
    }

    private fun patchVisibleMessages() {
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java.getDeclaredMethod(
                "onConfigure", Int::class.javaPrimitiveType, ChatListEntry::class.java,
            ),
            Hook { frame ->
                configureEntered.incrementAndGet()
                try {
                    configureStage = "READ_ENTRY"
                    val message = (frame.args[1] as? MessageEntry)?.message ?: return@Hook
                    configureStage = "BUTTON"
                    recoverFloatingButtonIfMissing()
                    updateButton()
                    positionFloatingButton()
                    configureStage = "LOADING"
                    if (!running || message.isLoading) return@Hook
                    configureStage = "CHANNEL"
                    val channel = message.channelId
                    if (!isEnabled(channel)) return@Hook
                    configureStage = "PROBE"
                    startAutomaticProbe()
                    configured.incrementAndGet()
                    configureStage = "MESSAGE_ID"
                    val id = message.id
                    configureStage = "MESSAGE_CONTENT"
                    val content = message.content ?: ""
                    configureStage = "ENQUEUE"
                    enqueue(id, channel, content, urgent = true)
                    configureStage = "DONE"
                    persistDiagnostic()
                } catch (t: Throwable) {
                    configureExceptions.incrementAndGet()
                    configureError = t.javaClass.simpleName.take(52).ifEmpty { "UNKNOWN" }
                    persistDiagnostic(true)
                    logger.error(t)
                }
            },
        )
    }

    private fun patchMessageText() {
        patcher.patch(
            WidgetChatListAdapterItemMessage::class.java,
            "processMessageText",
            arrayOf(SimpleDraweeSpanTextView::class.java, MessageEntry::class.java),
            Hook { frame ->
                textEntered.incrementAndGet()
                try {
                    textStage = "RUNNING"
                    if (!running) return@Hook
                    textStage = "ENTRY"
                    val message = (frame.args[1] as? MessageEntry)?.message ?: return@Hook
                    textStage = "CHANNEL"
                    if (!isEnabled(message.channelId)) return@Hook
                    textHooks.incrementAndGet()
                    textStage = "CONTENT"
                    val original = message.content.orEmpty()
                    if (original.length == 0) hookBlank.incrementAndGet() else hookNonBlank.incrementAndGet()
                    textStage = "ENQUEUE"
                    enqueue(message.id, message.channelId, original, urgent = true)
                    textStage = "CACHE"
                    val translation = synchronized(cacheLock) { cache[message.id] }
                    if (translation == null || translation.original != original || translation.language != target()) return@Hook
                    if (translation.rendered.length == 0 || translation.rendered == original) return@Hook
                    textStage = "VIEW"
                    val view = frame.args[0] as? SimpleDraweeSpanTextView ?: return@Hook
                    val builder = textBuilderField.get(view) as? DraweeSpanStringBuilder ?: return@Hook
                    val sourceStart = builder.toString().indexOf(original)
                    // Rich Discord text may contain clickable mentions/links or spans.
                    // Replacing its raw buffer can erase click targets, so retain the
                    // original rendering and append a safe translation below it.
                    // Plain messages keep the R19 in-place translation behavior.
                    if (sourceStart >= 0 && !ProtectedMessage.parse(original).structured) {
                        builder.replace(sourceStart, sourceStart + original.length, translation.rendered)
                        val end = builder.length
                        builder.append("  🌐")
                        builder.setSpan(RelativeSizeSpan(0.75f), end, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    } else {
                        builder.append("\n🌐 ").append(translation.rendered)
                    }
                    view.setDraweeSpanStringBuilder(builder)
                    displays.incrementAndGet()
                    textStage = "DONE"
                    lastReason = "RENDERED"
                    persistDiagnostic(true)
                } catch (t: Throwable) {
                    textExceptions.incrementAndGet()
                    textError = t.javaClass.simpleName.take(52).ifEmpty { "UNKNOWN" }
                    persistDiagnostic(true)
                    logger.error(t)
                }
            },
        )
    }

    private fun scheduleRateLimitResume() {
        main.post {
            if (!running) return@post
            delayedRateResume?.let { main.removeCallbacks(it) }
            val resume = Runnable {
                delayedRateResume = null
                if (running && System.currentTimeMillis() >= blockedUntil &&
                    floatingChat.get()?.isResumed == true) {
                    val channel = selectedChannel()
                    if (isEnabled(channel)) primeLoadedChannel(channel)
                }
            }
            delayedRateResume = resume
            main.postDelayed(resume, (blockedUntil - System.currentTimeMillis()).coerceAtLeast(1000L) + 250L)
        }
    }

    // Drop queued jobs that no longer match the active channel and selected language.
    // A currently running HTTP request cannot be cancelled safely here; its result is
    // checked again before being placed in the message cache.
    private fun pruneQueuedTasks(channel: Long) {
        val language = target()
        for (item in taskQueue.toList()) {
            val task = item as? TranslationJob ?: continue
            if (task.channel != channel || task.language != language || !isEnabled(task.channel)) {
                if (taskQueue.remove(item)) {
                    pending.remove(task.key)
                    staleDrops.incrementAndGet()
                }
            }
        }
    }

    private fun promoteQueued(key: RequestKey) {
        for (item in taskQueue.toList()) {
            val task = item as? TranslationJob ?: continue
            if (task.key == key && taskQueue.remove(item)) {
                if (taskQueue.offerFirst(item)) {
                    priorityPromotions.incrementAndGet()
                } else if (!taskQueue.offerLast(item)) {
                    pending.remove(task.key)
                    queueEvictions.incrementAndGet()
                }
                return
            }
        }
    }

    private inner class TranslationJob(
        val key: RequestKey,
        val channel: Long,
        val queuedAt: Long,
    ) : Runnable {
        val language: String get() = key.language
        override fun run() {
            try {
                if (!running || !isEnabled(channel) || selectedChannel() != channel || target() != language) {
                    staleDrops.incrementAndGet()
                    return
                }
                val now = System.currentTimeMillis()
                if (now < blockedUntil) {
                    throttledRequests.incrementAndGet()
                    return // Never block the only worker for 90 seconds.
                }
                waitTotalMs.addAndGet((now - queuedAt).coerceAtLeast(0L))
                waitSamples.incrementAndGet()
                startedRequests.incrementAndGet()
                lastReason = "REQUEST_STARTED"
                persistDiagnostic(true)
                when (val response = api.translate(key.original, language)) {
                    is TranslationClient.Result.Success -> {
                        successes.incrementAndGet()
                        lastReason = "HTTP_OK"
                        persistDiagnostic(true)
                        if (!running || !isEnabled(channel) || target() != language || selectedChannel() != channel) return
                        synchronized(cacheLock) {
                            cache[key.id] = Translated(key.original, language, response.text)
                            textCache[TextKey(language, key.original)] = response.text
                            retryAfter.remove(key)
                        }
                        main.post {
                            if (running && isEnabled(channel) && target() == language && selectedChannel() == channel) {
                                redrawMessage(key.id)
                            }
                        }
                    }
                    is TranslationClient.Result.Failure -> {
                        val isRateLimited = response.code == 429
                        val deadline = System.currentTimeMillis() + if (isRateLimited) 90_000L else 20_000L
                        synchronized(cacheLock) { retryAfter[key] = deadline }
                        if (isRateLimited) {
                            blockedUntil = deadline
                            throttledRequests.incrementAndGet()
                            scheduleRateLimitResume()
                        }
                        reportFailure(if (response.code > 0) "HTTP_${response.code}" else response.category)
                    }
                }
                // Preserve gentle pacing, without a long, uninterruptible retry sleep.
                Thread.sleep(350L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (t: Throwable) {
                reportFailure("WORKER_EXCEPTION")
                logger.error(t)
            } finally {
                pending.remove(key)
            }
        }
    }

    private fun enqueue(id: Long, channel: Long, original: String, urgent: Boolean) {
        if (original.isEmpty()) {
            blankContent.incrementAndGet()
            return
        }
        if (original.length > TranslationClient.MAX_MESSAGE_CHARS) {
            oversizeContent.incrementAndGet()
            return
        }
        if (!running || !isEnabled(channel) || selectedChannel() != channel) return
        eligible.incrementAndGet()
        val language = target()
        val key = RequestKey(id, language, original)
        val now = System.currentTimeMillis()
        val cachedText = synchronized(cacheLock) {
            val existing = cache[id]
            if (existing != null && existing.original == original && existing.language == language) {
                cacheHits.incrementAndGet()
                return
            }
            val cooldown = retryAfter[key]
            if (cooldown != null && cooldown > now) return
            val reuse = textCache[TextKey(language, original)]
            if (reuse != null) cache[id] = Translated(original, language, reuse)
            reuse
        }
        if (cachedText != null) {
            textReuses.incrementAndGet()
            main.post {
                if (running && isEnabled(channel) && target() == language && selectedChannel() == channel) {
                    redrawMessage(id)
                }
            }
            return
        }
        if (now < blockedUntil) {
            throttledRequests.incrementAndGet()
            return
        }
        if (!pending.add(key)) {
            duplicates.incrementAndGet()
            if (urgent) promoteQueued(key)
            return
        }
        if (work.isShutdown) {
            pending.remove(key)
            return
        }
        val job = TranslationJob(key, channel, now)
        var accepted = if (urgent) taskQueue.offerFirst(job) else taskQueue.offerLast(job)
        if (!accepted && urgent) {
            // A visible, recent message replaces the oldest queued work.
            val evicted = taskQueue.pollLast() as? TranslationJob
            if (evicted != null) {
                pending.remove(evicted.key)
                queueEvictions.incrementAndGet()
                accepted = taskQueue.offerFirst(job)
            }
        }
        if (accepted) scheduled.incrementAndGet()
        else {
            pending.remove(key)
            queueEvictions.incrementAndGet()
        }
        persistDiagnostic()
    }

    private fun redrawMessage(id: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { redrawMessage(id) }
            return
        }
        val current = chat.get() ?: run { noChat.incrementAndGet(); persistDiagnostic(true); return }
        val adapter = WidgetChatList.`access$getAdapter$p`(current)
        val index = CollectionUtils.findIndex(adapter.internalData) { entry ->
            entry is MessageEntry && entry.message.id == id
        }
        if (index >= 0) {
            adapter.notifyItemChanged(index)
            redraws.incrementAndGet()
            persistDiagnostic()
        }
    }

    private fun primeLoadedChannel(channel: Long) {
        // Queue everything currently held by Discord's chat adapter, not just
        // the visible rows. Older unloaded history enters on subsequent scrolls.
        val current = chat.get() ?: run { noChat.incrementAndGet(); lastReason = "NO_CHAT_REFERENCE"; persistDiagnostic(true); return }
        try {
            val adapter = WidgetChatList.`access$getAdapter$p`(current)
            for (entry in adapter.internalData) {
                val message = (entry as? MessageEntry)?.message ?: continue
                if (message.channelId == channel && !message.isLoading) {
                    enqueue(message.id, channel, message.content ?: "", urgent = false)
                }
            }
        } catch (t: Throwable) {
            logger.error(t)
        }
    }

    private fun redrawLoadedMessages() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { redrawLoadedMessages() }
            return
        }
        try {
            val current = chat.get() ?: return
            val adapter = WidgetChatList.`access$getAdapter$p`(current)
            adapter.notifyDataSetChanged() // onConfigure will queue newly enabled visible messages
        } catch (t: Throwable) {
            logger.error(t)
        }
    }

    companion object {
        private const val BUTTON_TAG = "wondertranslate:toggle:r16"
        private const val COMPOSER_TAG = "wondertranslate:toggle:r23:composer"
    }
}
