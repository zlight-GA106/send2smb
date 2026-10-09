package com.zlight.sendtosmb

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/** Activity and foreground service share the same queue, even if the Activity is destroyed. */
class SendToSmbApplication : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val explorer: ExplorerViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory(this))[ExplorerViewModel::class.java]
    }
}
