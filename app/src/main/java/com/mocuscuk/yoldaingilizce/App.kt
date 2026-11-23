package com.mocuscuk.yoldaingilizce

import android.app.Application
import com.mocuscuk.yoldaingilizce.ai.AiClient
import com.mocuscuk.yoldaingilizce.data.ContentRepository
import com.mocuscuk.yoldaingilizce.data.ProgressStore

class App : Application() {
    lateinit var content: ContentRepository
        private set
    lateinit var progress: ProgressStore
        private set
    lateinit var ai: AiClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        content = ContentRepository(this)
        progress = ProgressStore(this)
        ai = AiClient(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
