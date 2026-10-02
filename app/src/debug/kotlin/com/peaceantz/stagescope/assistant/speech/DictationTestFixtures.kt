package com.peaceantz.stagescope.assistant.speech

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import java.util.concurrent.atomic.AtomicInteger

// Debug-build fixtures for DictationLauncherBindingTest (see app/src/debug/AndroidManifest.xml for why they live here, not in androidTest).

/** A host Activity that does exactly what MainActivity does for dictation -- build the binding while being created -- and nothing else. */
class DictationHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        creations.incrementAndGet()
        val c = checkNotNull(controller)
        DictationLauncherBinding(this, c) { ticket -> intentOverride?.invoke(ticket) ?: Intent(this, FakeRecognizerActivity::class.java).putExtra("method", ticket.method.name) }
    }

    companion object {
        @Volatile var controller: DictationController? = null
        @Volatile var intentOverride: ((LaunchTicket) -> Intent)? = null
        val creations = AtomicInteger(0)
    }
}

/** Stands in for the watch's dictation screen: stays open until the test finishes it, or answers at once if told to. */
class FakeRecognizerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        created.incrementAndGet()
        current = this
        autoResult?.let { (code, words) -> finishWith(code, words) }
    }

    fun finishWith(code: Int, words: List<String>?) {
        setResult(code, words?.let { Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, ArrayList(it)) })
        finish()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        val created = AtomicInteger(0)
        @Volatile var current: FakeRecognizerActivity? = null
        @Volatile var autoResult: Pair<Int, List<String>?>? = null
    }
}
