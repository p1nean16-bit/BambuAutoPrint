package com.example.bambuautoprint

import com.example.bambuautoprint.automation.LayerReading
import com.example.bambuautoprint.automation.LayerSpeedRules
import org.junit.Assert.*
import org.junit.Test

class LayerSpeedRulesTest {
    @Test fun parsesChineseAndEnglishLayerLabels() {
        assertEquals(LayerReading(150, 300), LayerSpeedRules.parse(listOf("当前层：150/300")))
        assertEquals(LayerReading(151, 300), LayerSpeedRules.parse(listOf("层数", "151 / 300")))
        assertEquals(LayerReading(10, 200), LayerSpeedRules.parse(listOf("Layer: 10/200")))
        assertEquals(LayerReading(12, 250), LayerSpeedRules.parse(listOf("12/250层")))
    }
    @Test fun rejectsAmsProgressAndAmbiguousReadings() {
        assertNull(LayerSpeedRules.parse(listOf("AMS", "1/4", "100%")))
        assertNull(LayerSpeedRules.parse(listOf("当前层 501/500")))
        assertNull(LayerSpeedRules.parse(listOf("层数 10/200", "层数 12/200")))
    }
    @Test fun thresholdIncludesSpecifiedLayer() {
        assertEquals("标准", LayerSpeedRules.desired(150, 151, "标准", "静音"))
        assertEquals("静音", LayerSpeedRules.desired(151, 151, "标准", "静音"))
        assertEquals("静音", LayerSpeedRules.desired(160, 151, "标准", "静音"))
    }
    @Test fun onlyRecognizesNamedSpeedModes() {
        assertEquals("运动", LayerSpeedRules.mode("Sport 124%"))
        assertEquals("静音", LayerSpeedRules.mode("静音 (50%)"))
        assertNull(LayerSpeedRules.mode("100%"))
        assertNull(LayerSpeedRules.mode("选择标准耗材"))
    }
    @Test fun requiresSlowerSecondSegmentAndValidLimits() {
        assertTrue(LayerSpeedRules.valid(151, "标准", "静音", 480))
        assertFalse(LayerSpeedRules.valid(0, "标准", "静音", 480))
        assertFalse(LayerSpeedRules.valid(151, "标准", "运动", 480))
        assertFalse(LayerSpeedRules.valid(151, "标准", "静音", 0))
    }
}
