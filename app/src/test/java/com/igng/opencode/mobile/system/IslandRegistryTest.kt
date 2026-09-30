package com.igng.opencode.mobile.system

import com.igng.opencode.mobile.core.ServerProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `IslandRegistry` 与适配器品牌路由的纯逻辑测试。厂商探测依赖 Android 系统 API，此处覆盖不依赖
 * Context 的常量、列表一致性与待合作通道的开关语义；真机行为由 `docs/ISLAND_ADAPTATION.md` 的
 * 验收清单确认。
 */
class IslandRegistryTest {
  @After fun resetVendorConfig() {
    OppoFluidCloud.serviceId = ""
    OppoFluidCloud.transport = NoopOppoFluidCloudTransport
  }

  @Test fun adaptersExposeStableVendorIds() {
    val vendors = listOf(
      XiaomiIslandAdapter.vendor, VivoIslandAdapter.vendor, HonorIslandAdapter.vendor,
      OppoFluidCloudAdapter.vendor, StandardLiveUpdateAdapter.vendor
    )
    assertEquals(listOf("xiaomi", "vivo", "honor", "oppo", "android"), vendors)
    assertEquals(vendors.size, vendors.toSet().size)
  }

  @Test fun registryDiagnosticsCoversEveryAdapter() {
    val vendors = IslandRegistry.registeredVendorsForTest()
    assertTrue(vendors.containsAll(listOf("xiaomi", "vivo", "honor", "oppo", "android")))
    assertEquals(5, vendors.size)
  }

  @Test fun vendorChannelsDefaultOffInProfile() {
    val profile = ServerProfile(id = "id", name = "n", url = "https://x")
    assertFalse(profile.islandHonor)
    assertFalse(profile.islandOppoFluidCloud)
  }

  @Test fun oppoTransportIsInjectable() {
    var published: Pair<String, String>? = null
    OppoFluidCloud.transport = object : OppoFluidCloudTransport {
      override fun publish(context: android.content.Context, serviceId: String, payload: String) {
        published = serviceId to payload
      }
    }
    OppoFluidCloud.serviceId = "999900001"
    assertEquals("999900001", OppoFluidCloud.serviceId)
    // 传输未被 extend 调用时不应有副作用。
    assertEquals(null, published)
  }
}
