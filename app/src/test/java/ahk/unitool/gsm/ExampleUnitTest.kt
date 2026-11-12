package ahk.unitool.gsm

import ahk.unitool.gsm.model.DeviceProtocolMode
import ahk.unitool.gsm.model.MtkEngineVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testProtocolModes() {
        assertEquals("MTK", DeviceProtocolMode.MTK.tag)
        assertEquals("SPD", DeviceProtocolMode.SPD.tag)
        assertEquals("QDL", DeviceProtocolMode.QUALCOMM.tag)
        assertEquals("FB", DeviceProtocolMode.FASTBOOT.tag)
        assertEquals("ADB", DeviceProtocolMode.ADB.tag)
    }

    @Test
    fun testMtkEngineVersions() {
        val v1 = MtkEngineVersion.MTK_V1_MTKCLIENT
        val v2 = MtkEngineVersion.MTK_V2_ANTUMBRA

        assertEquals("mtkclient", v1.commandPrefix)
        assertEquals("mtk", v2.commandPrefix)
        assertTrue(v1.title.contains("mtkclient"))
        assertTrue(v2.title.contains("mtk") || v2.title.contains("umbrage"))
    }
}
