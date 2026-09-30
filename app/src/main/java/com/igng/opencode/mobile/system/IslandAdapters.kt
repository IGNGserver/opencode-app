package com.igng.opencode.mobile.system

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.igng.opencode.mobile.R
import org.json.JSONObject

/** 灵动岛 / 实时活动在某一设备上的可用状态，用于设置页展示与诊断。 */
data class IslandSupport(
  val vendor: String,
  val label: String,
  val supported: Boolean,
  val granted: Boolean,
  val note: String
)

/**
 * 一个厂商（或标准通道）的灵动岛扩展。约定按“能力探测 / 品牌兜底”路由，而不是把所有逻辑
 * 硬编码进通知构建：不支持时不产生任何副作用。
 */
internal interface IslandAdapter {
  val vendor: String
  /** 当前设备是否支持该通道，以及权限是否就绪。 */
  fun support(context: Context): IslandSupport
  /** 在不改变通知语义的前提下，向 [notification] 附加该通道所需的 extras。 */
  fun extend(context: Context, notification: Notification, title: String, detail: String, running: Boolean)
}

/**
 * 需要厂商授权 / 合作的灵动岛通道开关。默认全部关闭；获得厂商权限后只需在此打开并填入配对参数，
 * 无需改动通知构建逻辑。关闭时对应适配器完全不产生副作用。
 */
internal object IslandVendorConfig {
  /** 荣耀灵动胶囊：需荣耀开发者企业认证与白名单，非运行时通知 extras 通道。 */
  var honorCapsuleEnabled = false
  /** OPPO ColorOS 15 流体云（意图共享）：需开放平台分配 serviceId。 */
  var oppoFluidCloudEnabled = false
  /** OPPO 流体云卡片 ID，申请后填入。 */
  var oppoFluidCloudServiceId = ""
  /** OPPO 流体云传输实现；默认空实现，接入厂商 SDK / ContentProviderClient 后注入。 */
  var oppoFluidCloudTransport: OppoFluidCloudTransport = NoopOppoFluidCloudTransport
}

/** 把构建好的流体云意图交给 OPPO 通道。默认空实现，便于在不依赖厂商环境时构建与测试。 */
internal interface OppoFluidCloudTransport {
  fun publish(context: Context, serviceId: String, payload: String)
}

internal object NoopOppoFluidCloudTransport : OppoFluidCloudTransport {
  override fun publish(context: Context, serviceId: String, payload: String) = Unit
}

internal fun brandKey(): String = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()

internal object IslandRegistry {
  private val adapters: List<IslandAdapter> = listOf(
    XiaomiIslandAdapter, VivoIslandAdapter, HonorIslandAdapter, OppoFluidCloudAdapter, StandardLiveUpdateAdapter
  )

  fun extendAll(context: Context, notification: Notification, title: String, detail: String, running: Boolean) {
    adapters.forEach { runCatching { it.extend(context, notification, title, detail, running) } }
  }

  /** 全部通道的可用状态；[context] 用于读取厂商协议版本与标准通道权限。可能较慢，请在 IO 线程调用。 */
  fun diagnostics(context: Context): List<IslandSupport> = adapters.map { adapter ->
    runCatching { adapter.support(context) }
      .getOrElse { IslandSupport(adapter.vendor, "检测失败", false, false, it.message.orEmpty()) }
  }

  /** 测试用：已注册通道的 vendor 列表。 */
  internal fun registeredVendorsForTest(): List<String> = adapters.map { it.vendor }
}

/**
 * 小米 HyperOS 3 超级岛 / 焦点通知。协议版本来自系统设置，版本 < 3 时完全不写入 extras；
 * 焦点通知权限需向小米申请，`canShowFocus` 仅用于诊断展示，不阻塞参数写入。
 */
internal object XiaomiIslandAdapter : IslandAdapter {
  override val vendor = "xiaomi"

  private fun protocolVersion(context: Context): Int = runCatching {
    Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
  }.getOrDefault(0)

  private fun focusGranted(context: Context): Boolean = runCatching {
    val uri = Uri.parse("content://miui.statusbar.notification.public")
    val extras = Bundle().apply { putString("package", context.packageName) }
    context.contentResolver.call(uri, "canShowFocus", null, extras)?.getBoolean("canShowFocus", false) == true
  }.getOrDefault(false)

  override fun support(context: Context): IslandSupport {
    val version = protocolVersion(context)
    return IslandSupport(
      vendor = vendor,
      label = "小米超级岛（焦点通知）",
      supported = version >= 3,
      granted = if (version >= 3) focusGranted(context) else false,
      note = when {
        version >= 3 -> "HyperOS 3 超级岛已就绪；若未展示，请确认已在小米开放平台开通焦点通知权限。"
        version in 1..2 -> "仅支持旧版焦点通知模板（OS$version），无法使用超级岛。"
        else -> "当前系统未提供焦点通知能力。"
      }
    )
  }

  override fun extend(context: Context, notification: Notification, title: String, detail: String, running: Boolean) {
    val version = protocolVersion(context)
    if (version < 3) return
    val iconKey = "miui.focus.pic_app"
    val pics = Bundle().apply { putParcelable(iconKey, Icon.createWithResource(context, R.drawable.ic_app)) }
    val parameters = JSONObject().put("param_v2", JSONObject()
      .put("protocol", 1).put("business", "app").put("updatable", running)
      .put("ticker", detail.take(32)).put("aodTitle", title.take(32))
      .put("param_island", JSONObject()
        .put("islandProperty", 1)
        .put("smallIslandArea", JSONObject().put("picInfo", JSONObject().put("type", 1).put("pic", iconKey)))
        .put("bigIslandArea", JSONObject()
          .put("imageTextInfoLeft", JSONObject().put("type", 1)
            .put("picInfo", JSONObject().put("type", 1).put("pic", iconKey))
            .put("miui.focus.paramtextInfo", JSONObject().put("frontTitle", "OpenCode")
              .put("title", title.take(24)).put("content", detail.take(32)))))))
    notification.extras.putBundle("miui.focus.pics", pics)
    notification.extras.putString("miui.focus.param", parameters.toString())
  }
}

/**
 * vivo OriginOS 原子岛 / 原子通知。使用标准 `notification.superx.*` extras 在本地创建/更新。
 * 场景与权限需向 vivo 申请（当前为公测），未获批时系统会忽略这些 extras；`showNotify` 保证
 * 退化为普通通知。刷新/展示时长受系统限制（详见 vivo 文档），故摘要通知本身为 ongoing。
 */
internal object VivoIslandAdapter : IslandAdapter {
  override val vendor = "vivo"

  private fun isVivo(): Boolean {
    val brand = brandKey()
    return "vivo" in brand || "iqoo" in brand
  }

  override fun support(context: Context): IslandSupport = IslandSupport(
    vendor = vendor,
    label = "vivo 原子岛（原子通知）",
    supported = isVivo(),
    granted = false,
    note = if (isVivo()) "已写入原子岛参数；需在 vivo 开放平台申请原子通知/原子岛接入权限后方可展示。"
           else "当前设备不是 vivo / iQOO。"
  )

  override fun extend(context: Context, notification: Notification, title: String, detail: String, running: Boolean) {
    if (!isVivo()) return
    val extras = Bundle()
    // 0=创建，1=更新，2=结束。客户端本地接口没有“首次创建”的显式信号，采用 operation=1（更新），
    // 系统在不存在活动时会按创建处理；真正结束由取消通知完成。若真机验证要求严格的 0→1 序列，
    // 需由持有活动状态的调用方传入 operation。
    extras.putInt("notification.superx.operation", 1)
    extras.putBoolean("notification.superx.showNotify", true)
    extras.putInt("notification.superx.template", 1)
    // 官方示例使用 HEALTH_REGISTER 等垂域场景值；TASK 需在 vivo 开放平台申请时确认。
    extras.putString("notification.superx.scene", "TASK")
    val baseInfo = Bundle().apply {
      putParcelable("notification.superx.baseInfos.icon", Icon.createWithResource(context, R.drawable.ic_notification))
      putCharSequence("notification.superx.baseInfos.title", title.take(40))
      putCharSequence("notification.superx.baseInfos.content", detail.take(100))
    }
    extras.putBundle("notification.superx.baseInfos", baseInfo)
    notification.extras.putAll(extras)
  }
}

/**
 * 荣耀 MagicOS 灵动胶囊 / YOYO 建议。属于白名单制的“快捷服务 / 卡片模板”通道，非通行运行时通知
 * extras，需荣耀开发者企业认证与专项对接，因此默认关闭。开启后本适配器标记“已就绪”，
 * 实际下发由荣耀对接层完成（待厂商提供具体协议）。
 */
internal object HonorIslandAdapter : IslandAdapter {
  override val vendor = "honor"

  private fun isHonor(): Boolean = "honor" in brandKey()

  override fun support(context: Context): IslandSupport = IslandSupport(
    vendor = vendor,
    label = "荣耀灵动胶囊（YOYO 建议）",
    supported = isHonor() && IslandVendorConfig.honorCapsuleEnabled,
    granted = false,
    note = when {
      !isHonor() -> "当前设备不是荣耀。"
      !IslandVendorConfig.honorCapsuleEnabled -> "该通道需荣耀开发者企业认证与白名单，当前未开启。"
      else -> "通道已开启；灵动胶囊的呈现由荣耀审核与 YOYO 建议服务控制。"
    }
  )

  override fun extend(context: Context, notification: Notification, title: String, detail: String, running: Boolean) {
    if (!isHonor() || !IslandVendorConfig.honorCapsuleEnabled) return
    // 荣耀通道走独立对接（非通知 extras），此处按约定不写入 notification.extras，避免影响标准提示。
  }
}

/**
 * OPPO ColorOS 15 流体云（意图共享）。端侧通过「意图共享」创建 / 更新 / 结束，`actionStatus = 0/1/2`；
 * 需开放平台分配 `serviceId`。为不依赖厂商环境即可构建测试，实际下发通过
 * [IslandVendorConfig.oppoFluidCloudTransport] 注入；默认空实现。ColorOS 16 无需此通道，走标准 Live Updates。
 */
internal object OppoFluidCloudAdapter : IslandAdapter {
  override val vendor = "oppo"

  private fun isOppo(): Boolean = run {
    val brand = brandKey()
    "oppo" in brand || "oneplus" in brand || "realme" in brand
  }

  private fun enabled(): Boolean = IslandVendorConfig.oppoFluidCloudEnabled && IslandVendorConfig.oppoFluidCloudServiceId.isNotBlank()

  override fun support(context: Context): IslandSupport = IslandSupport(
    vendor = vendor,
    label = "OPPO 流体云（ColorOS 15 意图共享）",
    supported = isOppo() && enabled(),
    granted = enabled(),
    note = when {
      !isOppo() -> "当前设备不是 OPPO / 一加 / realme。"
      !enabled() -> "该通道需 OPPO 开放平台分配 serviceId，当前未开启；ColorOS 16 走标准实时更新即可。"
      else -> "通道已开启；请确认真机系统为 ColorOS 15 且意图共享特性开关已打开。"
    }
  )

  override fun extend(context: Context, notification: Notification, title: String, detail: String, running: Boolean) {
    if (!isOppo() || !enabled()) return
    val payload = JSONObject()
      .put("intentName", "OpenCode.TaskSummary")
      .put("actionStatus", if (running) 1 else 0) // 0=创建，1=更新；结束由取消通知触发
      .put("entityName", "TASK")
      .put("entityId", "opencode-task-summary")
      .put("capsule", JSONObject().put("rightText", detail.take(20)))
      .put("primary", JSONObject().put("title", title.take(40)).put("content", detail.take(100)))
      .toString()
    runCatching { IslandVendorConfig.oppoFluidCloudTransport.publish(context, IslandVendorConfig.oppoFluidCloudServiceId, payload) }
  }
}

/**
 * 原生 Android 16 Live Updates 通道（Pixel 及遵循 AOSP 的 ROM；OPPO ColorOS 16 的流体云已声明
 * 完整兼容该 API）。通知构建处已设置 `setRequestPromotedOngoing` / `setShortCriticalText`，
 * 此处仅用于探测与展示，无额外 extras。
 */
internal object StandardLiveUpdateAdapter : IslandAdapter {
  override val vendor = "android"

  override fun support(context: Context): IslandSupport {
    val api = Build.VERSION.SDK_INT >= 36
    val manager = context.getSystemService(NotificationManager::class.java)
    val granted = api && runCatching { manager?.canPostPromotedNotifications() == true }.getOrDefault(false)
    return IslandSupport(
      vendor = vendor,
      label = "Android 16 实时更新（Live Updates）",
      supported = api,
      granted = granted,
      note = when {
        !api -> "需要 Android 16（API 36）及以上。已随标准通知照常显示。"
        granted -> "系统已允许将任务总览提升为实时更新；同时兼容 OPPO ColorOS 16 流体云等遵循该 API 的系统。"
        else -> "系统尚未允许实时更新。请在设置中授予「实时更新 / 提升为常驻通知」权限后重试。"
      }
    )
  }

  override fun extend(context: Context, notification: Notification, title: String, detail: String, running: Boolean) = Unit
}
