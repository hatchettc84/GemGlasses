package com.lpecom.gemglasses.glasses

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesCameraSourceTest {

    /** Each cameraFrames() call returns the next scripted stream. */
    private class ScriptedBackend(private val streams: List<Flow<ByteArray>>) : GlassesBackend {
        var opened = 0
        override val registrationState: Flow<RegistrationState> = emptyFlow()
        override val devices: Flow<List<GlassesDevice>> = emptyFlow()
        override fun initialize() = Unit
        override fun startRegistration() = Unit
        override fun startUnregistration() = Unit
        override suspend fun cameraPermission() = CameraPermission.GRANTED
        override suspend fun requestCameraPermission() = CameraPermission.GRANTED
        override fun cameraFrames(): Flow<ByteArray> = streams.getOrElse(opened++) { emptyFlow() }
    }

    private fun frames(n: Int) = flow { repeat(n) { emit(byteArrayOf(it.toByte())) } }

    @Test fun `retries once when the first stream ends with no frames`() = runBlocking {
        val backend = ScriptedBackend(listOf(emptyFlow(), frames(3)))
        val got = GlassesCameraSource(backend).runBurst(maxDurationMs = 10_000, maxFrames = 10) {}
        assertEquals(3, got)
        assertEquals(2, backend.opened)
    }

    @Test fun `does not retry after frames arrived`() = runBlocking {
        val backend = ScriptedBackend(listOf(frames(2), frames(5)))
        val got = GlassesCameraSource(backend).runBurst(maxDurationMs = 10_000, maxFrames = 10) {}
        assertEquals(2, got)
        assertEquals(1, backend.opened)
    }

    @Test fun `gives up after one retry`() = runBlocking {
        val backend = ScriptedBackend(listOf(emptyFlow(), emptyFlow(), frames(4)))
        val got = GlassesCameraSource(backend).runBurst(maxDurationMs = 10_000, maxFrames = 10) {}
        assertEquals(0, got)
        assertEquals(2, backend.opened)
    }

    @Test fun `no retry when too little time is left`() = runBlocking {
        val backend = ScriptedBackend(listOf(emptyFlow(), frames(3)))
        val got = GlassesCameraSource(backend).runBurst(maxDurationMs = 2_000, maxFrames = 10) {}
        assertEquals(0, got)
        assertEquals(1, backend.opened)
    }

    @Test fun `respects the frame cap`() = runBlocking {
        val backend = ScriptedBackend(listOf(flowOf(*Array(20) { byteArrayOf(1) })))
        val got = GlassesCameraSource(backend).runBurst(maxDurationMs = 10_000, maxFrames = 5) {}
        assertEquals(5, got)
    }
}
