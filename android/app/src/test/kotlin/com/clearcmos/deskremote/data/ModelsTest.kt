package com.clearcmos.deskremote.data

import com.clearcmos.deskremote.Spec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app half of the response-payload contract in spec/wire-payloads.json.
 *
 * These decode the exact payloads the server's tests assert it produces. The
 * field names here are snake_case to match pydantic on the other side; renaming
 * one without renaming the other fails these tests rather than leaving the UI
 * showing defaults.
 */
class ModelsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `status payload decodes`() {
        val status = json.decodeFromString<StatusResponse>(Spec.payload("status_connected"))
        assertFalse(status.muted)
        assertEquals(74, status.volume)
        assertTrue(status.bluetooth_on)
        assertEquals("Soundcore Life Q30", status.bluetooth_connected)
    }

    @Test
    fun `status payload with a null device decodes`() {
        val status = json.decodeFromString<StatusResponse>(Spec.payload("status_bluetooth_off"))
        assertTrue(status.muted)
        assertEquals(0, status.volume)
        assertFalse(status.bluetooth_on)
        assertNull(status.bluetooth_connected)
    }

    @Test
    fun `action payloads decode`() {
        val muted = json.decodeFromString<ActionResponse>(Spec.payload("action_muted"))
        assertTrue(muted.success)
        assertEquals("Muted", muted.message)
        assertEquals(true, muted.new_state)

        val screenOff = json.decodeFromString<ActionResponse>(Spec.payload("action_without_state"))
        assertNull(screenOff.new_state)
    }

    @Test
    fun `volume payload decodes`() {
        val volume = json.decodeFromString<VolumeResponse>(Spec.payload("volume_set"))
        assertTrue(volume.success)
        assertEquals(42, volume.level)
        assertEquals("Volume set to 42%", volume.message)
    }

    @Test
    fun `volume request encodes the shape the server validates`() {
        assertEquals("""{"level":37}""", json.encodeToString(VolumeRequest(37)))
    }

    @Test
    fun `remote state defaults to disconnected`() {
        val state = RemoteState()
        assertEquals(ConnectionState.DISCONNECTED, state.connectionState)
        assertEquals(50, state.volume)
        assertNull(state.error)
    }

    @Test
    fun `every remote action the widget can send is defined`() {
        // WidgetActionReceiver and RemoteWidget switch exhaustively over this;
        // the list is asserted so a removal is a test failure, not a crash.
        assertEquals(
            listOf("MUTE", "BLUETOOTH", "SCREEN_OFF", "REFRESH"),
            RemoteAction.entries.map { it.name },
        )
    }
}
