package com.zlight.sendtosmb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EasyUpdateClientTest {
    @Test fun serverUrlsAreNormalized() {
        assertEquals("http://192.168.95.55:19910", EasyUpdateClient.normalizeServer(" http://192.168.95.55:19910/ "))
        assertEquals("https://updates.example.com/base", EasyUpdateClient.normalizeServer("https://updates.example.com/base"))
    }

    @Test fun invalidServerUrlsAreRejected() {
        listOf("", "192.168.95.55:19910", "ftp://192.168.95.55", "http://", "http://user:pass@host:1",
            "http://host/path?query=1", "http://host/#fragment").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { EasyUpdateClient.normalizeServer(value) }
        }
    }
}
