package com.zlight.sendtosmb

import androidx.test.platform.app.InstrumentationRegistry
import com.zlight.sendtosmb.data.SmbAddress
import com.zlight.sendtosmb.ui.UiProfile
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Explicit opt-in setup for manual device verification. Never connects to or mutates a share. */
class DeviceSetupTest {
    @Test fun prepareRequestedConnection() {
        val args = InstrumentationRegistry.getArguments()
        val rawUrl = args.getString("setupUrl").orEmpty()
        assumeTrue("Manual profile setup not requested", rawUrl.isNotBlank())
        val url = SmbAddress.parse(rawUrl).canonicalUrl
        val profile = UiProfile(UUID.nameUUIDFromBytes(url.toByteArray()).toString(), args.getString("setupName") ?: "SMB 共享", url,
            args.getString("setupUsername").orEmpty(), args.getString("setupPassword").orEmpty())
        val store = ProfileStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val previous = store.read()
        store.write(previous.first.filterNot { it.id == profile.id } + profile, profile.id)
    }
}
