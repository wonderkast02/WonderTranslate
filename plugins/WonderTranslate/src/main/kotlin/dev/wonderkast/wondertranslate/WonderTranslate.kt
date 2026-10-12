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
import com.aliucord.CollectionUtils
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.stores.StoreStream
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.widgets.chat.list.WidgetChatList
import com.discord.databinding.WidgetChatListBinding
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.span.DraweeSpanStringBuilder
import java.lang.ref.WeakReference
import java.util.LinkedHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LinkedHashMap<Long, Translated>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Translated>?): Boolean = size > 400
    }
    private val cacheLock = Any()
    private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private fun freshWorker() = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(240))
    private var work = freshWorker()
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
    private var buttonChannel: Long = -1L
    // R16: The channel control is attached to the chat's visible area, not the composer.
    // A read-only channel has no composer but still has WidgetChatList.
    private var floatingHost: FrameLayout? = null
    private var floatingAnchor: View? = null
    private var floatingChat = WeakReference<WidgetChatList>(null)
    private var floatingLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var floatingDetachListener: View.OnAttachStateChangeListener? = null
    @Volatile private var running = false
    @Volatile private var blockedUntil = 0L

    private val textBuilderField by lazy {
        SimpleDraweeSpanTextView::class.java.getDeclaredField("mDraweeStringBuilder").apply {
            isAccessible = true
        }
    }

    override fun start(context: Context) {
        if (work.isShutdown) work = freshWorker() // R8: allow same-session reactivation
        running = true
        automaticProbeStarted.set(false)
        lastReason = "STARTED"
        persistDiagnostic(true)
        patchMessageText()
        patchVisibleMessages()
        patchChatList()
        // The toggle no longer depends on FlexInputFragment.
        // WidgetChatList.onViewBoundOrOnResume installs the floating control.
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
            removeFloatingButton()
            redrawLoadedMessages()
        }
        synchronized(cacheLock) { cache.clear() }
        lastReason = "STOPPED"
        persistDiagnostic(true)
    }

    // Only counters, status codes and fixed categories: NEVER message text, user IDs or channel IDs.
    private fun diagnostic(): String = "R13; running=$running; enabled=${isEnabled(selectedChannel())}; consent=${settings.getBool("external_translation_consent", false)}; configured=${configured.get()}; hooks=${textHooks.get()}; hook_blank=${hookBlank.get()}; hook_text=${hookNonBlank.get()}; cfg_enter=${configureEntered.get()}; cfg_errs=${configureExceptions.get()}; cfg_stage=$configureStage; cfg_error=$configureError; txt_enter=${textEntered.get()}; txt_errs=${textExceptions.get()}; txt_stage=$textStage; txt_error=$textError; probe_error=$probeError; eligible=${eligible.get()}; skip_blank=${blankContent.get()}; skip_long=${oversizeContent.get()}; skip_cache=${cacheHits.get()}; skip_pending=${duplicates.get()}; queued=${scheduled.get()}; attempts=${startedRequests.get()}; success=${successes.get()}; failed=${failures.get()}; render=${displays.get()}; redraw=${redraws.get()}; no_chat=${noChat.get()}; probe=${probeAttempts.get()}; probe_ok=${probeSuccesses.get()}; probe_fail=${probeFailures.get()}; probe_last=$lastProbe; last=$lastReason"

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


    // One chat-scoped floating action replaces the old composer button.
    // By using the chat RecyclerView as the position/visibility anchor, this
    // works in read-only, announcement and locked channels as well.
    private fun attachFloatingButton(chatList: WidgetChatList) {
        if (!running) return
        try {
            // This binding/accessor is used in Aliucord's SwipeToReply plugin.
            val accessor = WidgetChatList::class.java.getDeclaredMethod("getBinding")
                .apply { isAccessible = true }
            val binding = accessor.invoke(chatList) as? WidgetChatListBinding ?: return
            val anchor: View = binding.b
            val host = Utils.appActivity.findViewById<FrameLayout>(android.R.id.content) ?: return
            if (anchor === floatingAnchor && host === floatingHost && toggleButton.get() != null) {
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
            floatingChat = WeakReference(chatList)
            toggleButton = WeakReference(button)
            buttonChannel = -1L
            floatingLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
                positionFloatingButton()
            }.also { host.viewTreeObserver.addOnGlobalLayoutListener(it) }
            floatingDetachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    main.post { if (floatingAnchor === v) removeFloatingButton() }
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

    private fun positionFloatingButton() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { positionFloatingButton() }
            return
        }
        val host = floatingHost ?: return
        val anchor = floatingAnchor ?: return
        val button = toggleButton.get() ?: return
        val visible = try {
            running && floatingChat.get()?.isResumed == true && anchor.isShown && anchor.width > 0 &&
                anchor.height > 0 && anchor.getGlobalVisibleRect(Rect()) && selectedChannel() > 0L
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
        val topMargin = (anchorXY[1] - hostXY[1] + dp(host.context, 8f)).coerceAtLeast(0)
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
        floatingChat.clear()
    }

    private fun updateButton() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { updateButton() }
            return
        }
        val button = toggleButton.get() ?: return
        val channel = selectedChannel()
        val enabled = isEnabled(channel)
        if (buttonChannel == channel && button.text.toString() == (if (enabled) "🌐 ON" else "🌐 OFF")) return
        buttonChannel = channel
        button.text = if (enabled) "🌐 ON" else "🌐 OFF"
        button.background = GradientDrawable().apply {
            cornerRadius = dp(button.context, 14f).toFloat()
            setColor(Color.parseColor(if (enabled) "#197E70" else "#394351"))
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
                    enqueue(id, channel, content)
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
                    enqueue(message.id, message.channelId, original)
                    textStage = "CACHE"
                    val translation = synchronized(cacheLock) { cache[message.id] }
                    if (translation == null || translation.original != original || translation.language != target()) return@Hook
                    if (translation.rendered.length == 0 || translation.rendered == original) return@Hook
                    textStage = "VIEW"
                    val view = frame.args[0] as? SimpleDraweeSpanTextView ?: return@Hook
                    val builder = textBuilderField.get(view) as? DraweeSpanStringBuilder ?: return@Hook
                    val sourceStart = builder.toString().indexOf(original)
                    if (sourceStart >= 0) {
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

    private fun enqueue(id: Long, channel: Long, original: String) {
        if (original.length == 0) {
            blankContent.incrementAndGet()
            lastReason = "SKIP_BLANK"
            persistDiagnostic()
            return
        }
        if (original.length > TranslationClient.MAX_MESSAGE_CHARS) {
            oversizeContent.incrementAndGet()
            lastReason = "SKIP_LONG"
            persistDiagnostic()
            return
        }
        eligible.incrementAndGet()
        val language = target()
        val existing = synchronized(cacheLock) { cache[id] }
        if (existing != null && existing.original == original && existing.language == language) {
            cacheHits.incrementAndGet()
            return
        }
        if (!pending.add(id)) {
            duplicates.incrementAndGet()
            return
        }
        scheduled.incrementAndGet()
        persistDiagnostic()
        try {
            work.execute {
                try {
                    if (!running || !isEnabled(channel)) return@execute
                    val pause = blockedUntil - System.currentTimeMillis()
                    if (pause > 0L) Thread.sleep(pause.coerceAtMost(90_000L))
                    if (!running || !isEnabled(channel)) return@execute
                    startedRequests.incrementAndGet()
                    lastReason = "REQUEST_STARTED"
                    persistDiagnostic(true)
                    var response = api.translate(original, language)
                    if (response is TranslationClient.Result.Failure && response.code == 429) {
                        blockedUntil = System.currentTimeMillis() + 90_000L
                        Utils.showToast("Limite de tradução: aguardando antes de retomar", true)
                        Thread.sleep(90_000L)
                        if (!running || !isEnabled(channel)) return@execute
                        startedRequests.incrementAndGet()
                        response = api.translate(original, language) // one controlled retry
                    }
                    when (response) {
                        is TranslationClient.Result.Success -> {
                            successes.incrementAndGet()
                            lastReason = "HTTP_OK"
                            persistDiagnostic(true)
                            if (!running) return@execute
                            synchronized(cacheLock) { cache[id] = Translated(original, language, response.text) }
                            main.post {
                                if (running && isEnabled(channel) && target() == language && selectedChannel() == channel) redrawMessage(id)
                            }
                        }
                        is TranslationClient.Result.Failure -> {
                            reportFailure(if (response.code > 0) "HTTP_${response.code}" else response.category)
                            if (response.code == 429) {
                                blockedUntil = System.currentTimeMillis() + 90_000L
                                Utils.showToast("Tradução limitada pelo servidor; redução automática da frequência", true)
                            }
                        }
                    }
                    Thread.sleep(350L) // one worker, gentle request pacing
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (t: Throwable) {
                    reportFailure("WORKER_EXCEPTION")
                    logger.error(t)
                } finally {
                    pending.remove(id)
                }
            }
        } catch (_: RejectedExecutionException) {
            reportFailure("QUEUE_FULL_OR_SHUTDOWN")
            pending.remove(id) // backpressure: will retry if user scrolls this message again
        }
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
                    enqueue(message.id, channel, message.content ?: "")
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
    }
}
