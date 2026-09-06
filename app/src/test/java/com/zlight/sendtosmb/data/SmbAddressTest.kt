package com.zlight.sendtosmb.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbAddressTest {
    @Test fun acceptsSingleLeadingSlashFromUserAddress() {
        assertEquals(SmbAddress.parse("smb://192.168.95.55/windisk"), SmbAddress.parse("/192.168.95.55/windisk"))
    }

    @Test fun parsesShareWithOptionalPortAndNavigationRoot() {
        val address = SmbAddress.parse("smb://192.168.1.12:1445/资料/照片/2026")
        assertEquals("192.168.1.12", address.host)
        assertEquals(1445, address.port)
        assertEquals("资料", address.share)
        assertEquals("照片\\2026", address.basePath)
        assertEquals("照片\\2026\\旅行\\相片.jpg", address.resolve("旅行/相片.jpg"))
        assertEquals(address, SmbAddress.parse(address.canonicalUrl))
    }

    @Test fun uncPreservesLiteralPercentHashAndPlus() {
        val address = SmbAddress.parse("\\\\NAS\\Share\\100% 完成#A+B")
        assertEquals("100% 完成#A+B", address.basePath)
        assertEquals(address, SmbAddress.parse(address.canonicalUrl))
    }

    @Test fun percentDecodesUtf8WithoutFormUrlPlusConversion() {
        val address = SmbAddress.parse("smb://nas/Share/%E7%85%A7%E7%89%87/A+B%20C")
        assertEquals("照片\\A+B C", address.basePath)
    }

    @Test fun supportsBracketedIpv6AndCustomPort() {
        val address = SmbAddress.parse("smb://[fd00::12]:1445/shared/folder")
        assertEquals("fd00::12", address.host)
        assertEquals(1445, address.port)
        assertEquals(address, SmbAddress.parse(address.canonicalUrl))
    }

    @Test fun rejectsCredentialsQueryAndMissingShare() {
        listOf("smb://user:secret@nas/share", "smb://nas/share?x=1", "smb://nas/share#folder",
            "smb://nas", "https://nas/share", "smb:///share", "smb://nas:/share", "smb://nas:0/share",
            "smb://nas:65536/share", "smb://nas:notaport/share").forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { SmbAddress.parse(raw) }
        }
    }

    @Test fun rejectsTraversalAndHiddenSeparatorsBeforeResolving() {
        listOf("smb://nas/share/../other", "smb://nas/share/%2e%2e/other", "smb://nas/share/a%2Fb",
            "smb://nas/share/a%5Cb", "smb://nas/share/%C0%AF", "smb://nas/share/%00",
            "smb://nas/share/file:stream", "smb://nas/share/folder. ").forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) { SmbAddress.parse(raw) }
        }
        val address = SmbAddress.parse("smb://nas/share/sandbox")
        listOf("../secret", "a/../../secret", "/absolute", "\\absolute", "C:\\file", "foo:stream", ".. ").forEach { path ->
            assertThrows(path, IllegalArgumentException::class.java) { address.resolve(path) }
        }
    }

    @Test fun navigationStaysRelativeAndRetainsOrdinaryPercentText() {
        val address = SmbAddress.parse("smb://nas/share/base")
        assertEquals("base", address.resolve(""))
        assertEquals("base\\child\\100%25.txt", address.resolve("child//./100%25.txt"))
        assertEquals("one/two/new.txt", SmbAddress.childPath("one\\two", "new.txt"))
        assertEquals("one", SmbAddress.parentPath("one/two"))
        assertEquals("", SmbAddress.parentPath("one"))
        assertThrows(IllegalArgumentException::class.java) { SmbAddress.childPath("safe", "../outside") }
    }
}
