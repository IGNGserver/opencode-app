package com.igng.opencode.mobile.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `IslandRegistry` 与适配器品牌路由的纯逻辑测试。厂商探测依赖 Android 系统 API，此处仅覆盖不依赖
 * Context 的常量与列表一致性，真机行为由 `docs/ISLAND_ADAPTATION.md` 的验收清单确认。
 */
class IslandRegistryTest {
  @Test fun adaptersExposeStableVendorIds() {
    val vendors = listOf(XiaomiIslandAdapter.vendor, VivoIslandAdapter.vendor, StandardLiveUpdateAdapter.vendor)
    assertEquals(listOf("xiaomi", "vivo", "android"), vendors)
    assertEquals(vendors.size, vendors.toSet().size)
  }

  @Test fun registryDiagnosticsCoversEveryAdapter() {
    // 不能在此调用 diagnostics(context)（需要 Android Context），但可以断言注册表与适配器集合一致。
    val vendors = IslandRegistry.registeredVendorsForTest()
    assertTrue(vendors.containsAll(listOf("xiaomi", "vivo", "android")))
    assertEquals(3, vendors.size)
  }
}
