package com.anupshrestha.pairloom

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.anupshrestha.pairloom.session.LinkViewModel

/**
 * Owns the link for the whole process. [LinkViewModel] used to belong to the activity, so the link
 * and any call ended with it. Kept here, it survives the activity being stopped, destroyed and
 * recreated: a call goes on in the background while [LinkService] keeps the process in the
 * foreground. It is cleared only when the process ends.
 */
class PairloomApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    override fun onCreate() {
        super.onCreate()
        LinkService.createChannels(this)
    }

    val link: LinkViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[LinkViewModel::class.java]
    }
}
