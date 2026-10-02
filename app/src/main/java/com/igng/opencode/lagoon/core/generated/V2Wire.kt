package com.igng.opencode.lagoon.core.generated

/** 由 tools/gen_v2_models.py 从 app/openapi-v2.json 生成，请勿手改。 */

data class Project_Vcs(val raw: org.json.JSONObject = org.json.JSONObject()) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Project_Vcs = Project_Vcs(j)
  }
}

data class Project_Icon(
  val url: String?,
  val override_: String?,
  val color: String?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Project_Icon = Project_Icon(
      url = j.optString("url").takeIf { it.isNotEmpty() && it != "null" },
      override_ = j.optString("override").takeIf { it.isNotEmpty() && it != "null" },
      color = j.optString("color").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class Project_Commands(
  val start: String?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Project_Commands = Project_Commands(
      start = j.optString("start").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class Project_Time(
  val created: Long?,
  val updated: Long?,
  val active: Long?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Project_Time = Project_Time(
      created = j.optLong("created", 0L),
      updated = j.optLong("updated", 0L),
      active = j.optLong("active", 0L),
    )
  }
}

data class Project(
  val id: String?,
  val canonical: String?,
  val vcs: Project_Vcs?,
  val name: String?,
  val icon: Project_Icon?,
  val commands: Project_Commands?,
  val time: Project_Time?,
  val sandboxes: List<String>?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Project = Project(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      canonical = j.optString("canonical").takeIf { it.isNotEmpty() && it != "null" },
      vcs = j.optJSONObject("vcs")?.let { Project_Vcs.fromJson(it) },
      name = j.optString("name").takeIf { it.isNotEmpty() && it != "null" },
      icon = j.optJSONObject("icon")?.let { Project_Icon.fromJson(it) },
      commands = j.optJSONObject("commands")?.let { Project_Commands.fromJson(it) },
      time = j.optJSONObject("time")?.let { Project_Time.fromJson(it) },
      sandboxes = j.optJSONArray("sandboxes")?.let { x -> (0 until x.length()).mapNotNull { i -> x.optString(i) } } ?: emptyList(),
    )
  }
}

data class Location_PublicRef(
  val directory: String?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Location_PublicRef = Location_PublicRef(
      directory = j.optString("directory").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

sealed class Session_ForkBoundary {
  abstract val type: String
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_ForkBoundary? = when (j.optString("type")) {
      "before" -> Session_ForkBoundary_before.fromJson(j)
      "through" -> Session_ForkBoundary_through.fromJson(j)
      else -> null
    }
  }
}

data class Session_ForkBoundary_before(
  val messageID: String?,
) : Session_ForkBoundary() {
  override val type: String = "before"
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_ForkBoundary_before = Session_ForkBoundary_before(
      messageID = j.optString("messageID").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class Session_ForkBoundary_through(
  val messageID: String?,
) : Session_ForkBoundary() {
  override val type: String = "through"
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_ForkBoundary_through = Session_ForkBoundary_through(
      messageID = j.optString("messageID").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class G1(
  val sessionID: String?,
  val boundary: Session_ForkBoundary?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G1 = G1(
      sessionID = j.optString("sessionID").takeIf { it.isNotEmpty() && it != "null" },
      boundary = j.optJSONObject("boundary")?.let { Session_ForkBoundary.fromJson(it) },
    )
  }
}

data class Model_Ref(
  val id: String?,
  val providerID: String?,
  val variant: String?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Model_Ref = Model_Ref(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      providerID = j.optString("providerID").takeIf { it.isNotEmpty() && it != "null" },
      variant = j.optString("variant").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class Money_USD(val raw: org.json.JSONObject = org.json.JSONObject()) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Money_USD = Money_USD(j)
  }
}

data class G2(
  val read: Double?,
  val write: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G2 = G2(
      read = j.optDouble("read", 0.0),
      write = j.optDouble("write", 0.0),
    )
  }
}

data class TokenUsage_Info(
  val input: Double?,
  val output: Double?,
  val reasoning: Double?,
  val cache: G2?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): TokenUsage_Info = TokenUsage_Info(
      input = j.optDouble("input", 0.0),
      output = j.optDouble("output", 0.0),
      reasoning = j.optDouble("reasoning", 0.0),
      cache = j.optJSONObject("cache")?.let { G2.fromJson(it) },
    )
  }
}

data class G3(
  val created: Double?,
  val updated: Double?,
  val idle: Double?,
  val viewed: Double?,
  val archived: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G3 = G3(
      created = j.optDouble("created", 0.0),
      updated = j.optDouble("updated", 0.0),
      idle = j.optDouble("idle", 0.0),
      viewed = j.optDouble("viewed", 0.0),
      archived = j.optDouble("archived", 0.0),
    )
  }
}

data class Session_Metadata(val raw: org.json.JSONObject = org.json.JSONObject()) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Metadata = Session_Metadata(j)
  }
}

data class Permission_Ruleset(val raw: org.json.JSONObject = org.json.JSONObject()) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Permission_Ruleset = Permission_Ruleset(j)
  }
}

data class G4(
  val file: String?,
  val patch: String?,
  val additions: Long?,
  val deletions: Long?,
  val status: String?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G4 = G4(
      file = j.optString("file").takeIf { it.isNotEmpty() && it != "null" },
      patch = j.optString("patch").takeIf { it.isNotEmpty() && it != "null" },
      additions = j.optLong("additions", 0L),
      deletions = j.optLong("deletions", 0L),
      status = j.optString("status").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class Session_Revert(
  val messageID: String?,
  val partID: String?,
  val snapshot: String?,
  val files: List<G4>?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Revert = Session_Revert(
      messageID = j.optString("messageID").takeIf { it.isNotEmpty() && it != "null" },
      partID = j.optString("partID").takeIf { it.isNotEmpty() && it != "null" },
      snapshot = j.optString("snapshot").takeIf { it.isNotEmpty() && it != "null" },
      files = j.optJSONArray("files")?.let { x -> (0 until x.length()).mapNotNull { i -> x.optJSONObject(i)?.let { G4.fromJson(it) } } } ?: emptyList(),
    )
  }
}

data class Session_Info(
  val id: String?,
  val parentID: String?,
  val fork: G1?,
  val projectID: String?,
  val agent: String?,
  val model: Model_Ref?,
  val cost: Money_USD?,
  val tokens: TokenUsage_Info?,
  val outcome: String?,
  val time: G3?,
  val title: String?,
  val subpath: String?,
  val metadata: Session_Metadata?,
  val permissions: Permission_Ruleset?,
  val revert: Session_Revert?,
  val location: Location_PublicRef?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Info = Session_Info(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      parentID = j.optString("parentID").takeIf { it.isNotEmpty() && it != "null" },
      fork = j.optJSONObject("fork")?.let { G1.fromJson(it) },
      projectID = j.optString("projectID").takeIf { it.isNotEmpty() && it != "null" },
      agent = j.optString("agent").takeIf { it.isNotEmpty() && it != "null" },
      model = j.optJSONObject("model")?.let { Model_Ref.fromJson(it) },
      cost = j.optJSONObject("cost")?.let { Money_USD.fromJson(it) },
      tokens = j.optJSONObject("tokens")?.let { TokenUsage_Info.fromJson(it) },
      outcome = j.optString("outcome").takeIf { it.isNotEmpty() && it != "null" },
      time = j.optJSONObject("time")?.let { G3.fromJson(it) },
      title = j.optString("title").takeIf { it.isNotEmpty() && it != "null" },
      subpath = j.optString("subpath").takeIf { it.isNotEmpty() && it != "null" },
      metadata = j.optJSONObject("metadata")?.let { Session_Metadata.fromJson(it) },
      permissions = j.optJSONObject("permissions")?.let { Permission_Ruleset.fromJson(it) },
      revert = j.optJSONObject("revert")?.let { Session_Revert.fromJson(it) },
      location = j.optJSONObject("location")?.let { Location_PublicRef.fromJson(it) },
    )
  }
}

sealed class Session_Message_Info {
  abstract val type: String
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_Info? = when (j.optString("type")) {
      "agent-switched" -> Session_Message_Info_agent_switched.fromJson(j)
      "model-switched" -> Session_Message_Info_model_switched.fromJson(j)
      "location-switched" -> Session_Message_Info_location_switched.fromJson(j)
      "user" -> Session_Message_Info_user.fromJson(j)
      "synthetic" -> Session_Message_Info_synthetic.fromJson(j)
      "system" -> Session_Message_Info_system.fromJson(j)
      "skill" -> Session_Message_Info_skill.fromJson(j)
      "shell" -> Session_Message_Info_shell.fromJson(j)
      "assistant" -> Session_Message_Info_assistant.fromJson(j)
      "idle" -> Session_Message_Info_idle.fromJson(j)
      else -> null
    }
  }
}

data class G5(
  val created: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G5 = G5(
      created = j.optDouble("created", 0.0),
    )
  }
}

data class Session_Message_Info_agent_switched(
  val id: String?,
  val metadata: org.json.JSONObject?,
  val time: G5?,
  val agent: String?,
  val previous: String?,
) : Session_Message_Info() {
  override val type: String = "agent-switched"
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_Info_agent_switched = Session_Message_Info_agent_switched(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
      time = j.optJSONObject("time")?.let { G5.fromJson(it) },
      agent = j.optString("agent").takeIf { it.isNotEmpty() && it != "null" },
      previous = j.optString("previous").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class G6(
  val created: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G6 = G6(
      created = j.optDouble("created", 0.0),
    )
  }
}

data class Session_Message_Info_model_switched(
  val id: String?,
  val metadata: org.json.JSONObject?,
  val time: G6?,
  val model: Model_Ref?,
  val previous: Model_Ref?,
) : Session_Message_Info() {
  override val type: String = "model-switched"
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_Info_model_switched = Session_Message_Info_model_switched(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
      time = j.optJSONObject("time")?.let { G6.fromJson(it) },
      model = j.optJSONObject("model")?.let { Model_Ref.fromJson(it) },
      previous = j.optJSONObject("previous")?.let { Model_Ref.fromJson(it) },
    )
  }
}

data class G7(
  val created: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G7 = G7(
      created = j.optDouble("created", 0.0),
    )
  }
}

sealed class G8 {
  companion object {
    fun fromJson(j: org.json.JSONObject): G8? = when (j.optString("type")) {
      else -> null
    }
  }
}

data class G8_b0(
  val location: Location_PublicRef?,
  val projectID: String?,
  val subpath: String?,
) : G8() {
  companion object {
    fun fromJson(j: org.json.JSONObject): G8_b0 = G8_b0(
      location = j.optJSONObject("location")?.let { Location_PublicRef.fromJson(it) },
      projectID = j.optString("projectID").takeIf { it.isNotEmpty() && it != "null" },
      subpath = j.optString("subpath").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class Session_Message_Info_location_switched(
  val id: String?,
  val metadata: org.json.JSONObject?,
  val time: G7?,
  val projectID: String?,
  val subpath: String?,
  val location: Location_PublicRef?,
  val previous: G8?,
) : Session_Message_Info() {
  override val type: String = "location-switched"
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_Info_location_switched = Session_Message_Info_location_switched(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
      time = j.optJSONObject("time")?.let { G7.fromJson(it) },
      projectID = j.optString("projectID").takeIf { it.isNotEmpty() && it != "null" },
      subpath = j.optString("subpath").takeIf { it.isNotEmpty() && it != "null" },
      location = j.optJSONObject("location")?.let { Location_PublicRef.fromJson(it) },
      previous = j.optJSONObject("previous")?.let { G8.fromJson(it) },
    )
  }
}

data class G9(
  val created: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G9 = G9(
      created = j.optDouble("created", 0.0),
    )
  }
}

data class Prompt_Base64(val raw: org.json.JSONObject = org.json.JSONObject()) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Prompt_Base64 = Prompt_Base64(j)
  }
}

sealed class Prompt_FileSource {
  abstract val type: String
  companion object {
    fun fromJson(j: org.json.JSONObject): Prompt_FileSource? = when (j.optString("type")) {
      "inline" -> Prompt_FileSource_inline.fromJson(j)
      "uri" -> Prompt_FileSource_uri.fromJson(j)
      else -> null
    }
  }
}

data class Prompt_FileSource_inline(
  val raw: org.json.JSONObject = org.json.JSONObject(),
) : Prompt_FileSource() {
  override val type: String = "inline"
  companion object {
    fun fromJson(j: org.json.JSONObject): Prompt_FileSource_inline = Prompt_FileSource_inline(
      raw = j,
    )
  }
}

data class Prompt_FileSource_uri(
  val uri: String?,
) : Prompt_FileSource() {
  override val type: String = "uri"
  companion object {
    fun fromJson(j: org.json.JSONObject): Prompt_FileSource_uri = Prompt_FileSource_uri(
      uri = j.optString("uri").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class Prompt_Mention(
  val start: Double?,
  val end: Double?,
  val text: String?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Prompt_Mention = Prompt_Mention(
      start = j.optDouble("start", 0.0),
      end = j.optDouble("end", 0.0),
      text = j.optString("text").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class G10(
  val data_: Prompt_Base64?,
  val mime: String?,
  val source: Prompt_FileSource?,
  val name: String?,
  val description: String?,
  val mention: Prompt_Mention?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G10 = G10(
      data_ = j.optJSONObject("data")?.let { Prompt_Base64.fromJson(it) },
      mime = j.optString("mime").takeIf { it.isNotEmpty() && it != "null" },
      source = j.optJSONObject("source")?.let { Prompt_FileSource.fromJson(it) },
      name = j.optString("name").takeIf { it.isNotEmpty() && it != "null" },
      description = j.optString("description").takeIf { it.isNotEmpty() && it != "null" },
      mention = j.optJSONObject("mention")?.let { Prompt_Mention.fromJson(it) },
    )
  }
}

data class G11(
  val name: String?,
  val mention: Prompt_Mention?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G11 = G11(
      name = j.optString("name").takeIf { it.isNotEmpty() && it != "null" },
      mention = j.optJSONObject("mention")?.let { Prompt_Mention.fromJson(it) },
    )
  }
}

data class G12(
  val id: String?,
  val name: String?,
  val text: String?,
  val mention: Prompt_Mention?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G12 = G12(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      name = j.optString("name").takeIf { it.isNotEmpty() && it != "null" },
      text = j.optString("text").takeIf { it.isNotEmpty() && it != "null" },
      mention = j.optJSONObject("mention")?.let { Prompt_Mention.fromJson(it) },
    )
  }
}

data class Session_Message_Info_user(
  val id: String?,
  val metadata: org.json.JSONObject?,
  val time: G9?,
  val text: String?,
  val files: List<G10>?,
  val agents: List<G11>?,
  val skills: List<G12>?,
) : Session_Message_Info() {
  override val type: String = "user"
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_Info_user = Session_Message_Info_user(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
      time = j.optJSONObject("time")?.let { G9.fromJson(it) },
      text = j.optString("text").takeIf { it.isNotEmpty() && it != "null" },
      files = j.optJSONArray("files")?.let { x -> (0 until x.length()).mapNotNull { i -> x.optJSONObject(i)?.let { G10.fromJson(it) } } } ?: emptyList(),
      agents = j.optJSONArray("agents")?.let { x -> (0 until x.length()).mapNotNull { i -> x.optJSONObject(i)?.let { G11.fromJson(it) } } } ?: emptyList(),
      skills = j.optJSONArray("skills")?.let { x -> (0 until x.length()).mapNotNull { i -> x.optJSONObject(i)?.let { G12.fromJson(it) } } } ?: emptyList(),
    )
  }
}

data class G13(
  val created: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G13 = G13(
      created = j.optDouble("created", 0.0),
    )
  }
}

data class Session_Message_Info_synthetic(
  val id: String?,
  val metadata: org.json.JSONObject?,
  val time: G13?,
  val text: String?,
  val description: String?,
) : Session_Message_Info() {
  override val type: String = "synthetic"
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_Info_synthetic = Session_Message_Info_synthetic(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
      time = j.optJSONObject("time")?.let { G13.fromJson(it) },
      text = j.optString("text").takeIf { it.isNotEmpty() && it != "null" },
      description = j.optString("description").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class G14(
  val created: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G14 = G14(
      created = j.optDouble("created", 0.0),
    )
  }
}

data class Session_Message_Info_system(
  val id: String?,
  val metadata: org.json.JSONObject?,
  val time: G14?,
  val text: String?,
  val description: String?,
) : Session_Message_Info() {
  override val type: String = "system"
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_Info_system = Session_Message_Info_system(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
      time = j.optJSONObject("time")?.let { G14.fromJson(it) },
      text = j.optString("text").takeIf { it.isNotEmpty() && it != "null" },
      description = j.optString("description").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class G15(
  val created: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G15 = G15(
      created = j.optDouble("created", 0.0),
    )
  }
}

data class Session_Message_Info_skill(
  val id: String?,
  val metadata: org.json.JSONObject?,
  val time: G15?,
  val skill: String?,
  val name: String?,
  val text: String?,
) : Session_Message_Info() {
  override val type: String = "skill"
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_Info_skill = Session_Message_Info_skill(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
      time = j.optJSONObject("time")?.let { G15.fromJson(it) },
      skill = j.optString("skill").takeIf { it.isNotEmpty() && it != "null" },
      name = j.optString("name").takeIf { it.isNotEmpty() && it != "null" },
      text = j.optString("text").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class G16(
  val created: Double?,
  val completed: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G16 = G16(
      created = j.optDouble("created", 0.0),
      completed = j.optDouble("completed", 0.0),
    )
  }
}

data class G17(
  val output: String?,
  val cursor: Long?,
  val size: Long?,
  val truncated: Boolean?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G17 = G17(
      output = j.optString("output").takeIf { it.isNotEmpty() && it != "null" },
      cursor = j.optLong("cursor", 0L),
      size = j.optLong("size", 0L),
      truncated = j.optBoolean("truncated", false),
    )
  }
}

data class Session_Message_Info_shell(
  val id: String?,
  val metadata: org.json.JSONObject?,
  val time: G16?,
  val shellID: String?,
  val command: String?,
  val status: String?,
  val exit: Double?,
  val output: G17?,
) : Session_Message_Info() {
  override val type: String = "shell"
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_Info_shell = Session_Message_Info_shell(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
      time = j.optJSONObject("time")?.let { G16.fromJson(it) },
      shellID = j.optString("shellID").takeIf { it.isNotEmpty() && it != "null" },
      command = j.optString("command").takeIf { it.isNotEmpty() && it != "null" },
      status = j.optString("status").takeIf { it.isNotEmpty() && it != "null" },
      exit = j.optDouble("exit", 0.0),
      output = j.optJSONObject("output")?.let { G17.fromJson(it) },
    )
  }
}

data class G18(
  val created: Double?,
  val streamed: Double?,
  val completed: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G18 = G18(
      created = j.optDouble("created", 0.0),
      streamed = j.optDouble("streamed", 0.0),
      completed = j.optDouble("completed", 0.0),
    )
  }
}

sealed class G19 {
  abstract val type: String
  companion object {
    fun fromJson(j: org.json.JSONObject): G19? = when (j.optString("type")) {
      "text" -> G19_text.fromJson(j)
      "reasoning" -> G19_reasoning.fromJson(j)
      "tool" -> G19_tool.fromJson(j)
      else -> null
    }
  }
}

data class Session_Message_ProviderState(val raw: org.json.JSONObject = org.json.JSONObject()) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_ProviderState = Session_Message_ProviderState(j)
  }
}

data class G19_text(
  val text: String?,
  val state: Session_Message_ProviderState?,
) : G19() {
  override val type: String = "text"
  companion object {
    fun fromJson(j: org.json.JSONObject): G19_text = G19_text(
      text = j.optString("text").takeIf { it.isNotEmpty() && it != "null" },
      state = j.optJSONObject("state")?.let { Session_Message_ProviderState.fromJson(it) },
    )
  }
}

data class Session_Message_ProviderState_1(val raw: org.json.JSONObject = org.json.JSONObject()) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_ProviderState_1 = Session_Message_ProviderState_1(j)
  }
}

data class G20(
  val created: Double?,
  val completed: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G20 = G20(
      created = j.optDouble("created", 0.0),
      completed = j.optDouble("completed", 0.0),
    )
  }
}

data class G19_reasoning(
  val text: String?,
  val state: Session_Message_ProviderState_1?,
  val time: G20?,
) : G19() {
  override val type: String = "reasoning"
  companion object {
    fun fromJson(j: org.json.JSONObject): G19_reasoning = G19_reasoning(
      text = j.optString("text").takeIf { it.isNotEmpty() && it != "null" },
      state = j.optJSONObject("state")?.let { Session_Message_ProviderState_1.fromJson(it) },
      time = j.optJSONObject("time")?.let { G20.fromJson(it) },
    )
  }
}

data class Session_Message_ProviderState_2(val raw: org.json.JSONObject = org.json.JSONObject()) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_ProviderState_2 = Session_Message_ProviderState_2(j)
  }
}

data class Session_Message_ProviderState_3(val raw: org.json.JSONObject = org.json.JSONObject()) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_ProviderState_3 = Session_Message_ProviderState_3(j)
  }
}

sealed class G21 {
  abstract val status: String
  companion object {
    fun fromJson(j: org.json.JSONObject): G21? = when (j.optString("status")) {
      "streaming" -> G21_streaming.fromJson(j)
      "running" -> G21_running.fromJson(j)
      "completed" -> G21_completed.fromJson(j)
      "error" -> G21_error.fromJson(j)
      else -> null
    }
  }
}

data class G21_streaming(
  val input: String?,
) : G21() {
  override val status: String = "streaming"
  companion object {
    fun fromJson(j: org.json.JSONObject): G21_streaming = G21_streaming(
      input = j.optString("input").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class G21_running(
  val input: org.json.JSONObject?,
  val metadata: org.json.JSONObject?,
) : G21() {
  override val status: String = "running"
  companion object {
    fun fromJson(j: org.json.JSONObject): G21_running = G21_running(
      input = j.optJSONObject("input") ?: org.json.JSONObject(),
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
    )
  }
}

sealed class G22 {
  abstract val type: String
  companion object {
    fun fromJson(j: org.json.JSONObject): G22? = when (j.optString("type")) {
      "text" -> G22_text.fromJson(j)
      "file" -> G22_file.fromJson(j)
      else -> null
    }
  }
}

data class G22_text(
  val text: String?,
) : G22() {
  override val type: String = "text"
  companion object {
    fun fromJson(j: org.json.JSONObject): G22_text = G22_text(
      text = j.optString("text").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class G22_file(
  val uri: String?,
  val mime: String?,
  val name: String?,
) : G22() {
  override val type: String = "file"
  companion object {
    fun fromJson(j: org.json.JSONObject): G22_file = G22_file(
      uri = j.optString("uri").takeIf { it.isNotEmpty() && it != "null" },
      mime = j.optString("mime").takeIf { it.isNotEmpty() && it != "null" },
      name = j.optString("name").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class G21_completed(
  val input: org.json.JSONObject?,
  val content: List<G22>?,
  val metadata: org.json.JSONObject?,
) : G21() {
  override val status: String = "completed"
  companion object {
    fun fromJson(j: org.json.JSONObject): G21_completed = G21_completed(
      input = j.optJSONObject("input") ?: org.json.JSONObject(),
      content = j.optJSONArray("content")?.let { x -> (0 until x.length()).mapNotNull { i -> x.optJSONObject(i)?.let { G22.fromJson(it) } } } ?: emptyList(),
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
    )
  }
}

data class Session_StructuredError(
  val type: String?,
  val message: String?,
  val status: Long?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_StructuredError = Session_StructuredError(
      type = j.optString("type").takeIf { it.isNotEmpty() && it != "null" },
      message = j.optString("message").takeIf { it.isNotEmpty() && it != "null" },
      status = j.optLong("status", 0L),
    )
  }
}

sealed class G23 {
  abstract val type: String
  companion object {
    fun fromJson(j: org.json.JSONObject): G23? = when (j.optString("type")) {
      "text" -> G23_text.fromJson(j)
      "file" -> G23_file.fromJson(j)
      else -> null
    }
  }
}

data class G23_text(
  val text: String?,
) : G23() {
  override val type: String = "text"
  companion object {
    fun fromJson(j: org.json.JSONObject): G23_text = G23_text(
      text = j.optString("text").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class G23_file(
  val uri: String?,
  val mime: String?,
  val name: String?,
) : G23() {
  override val type: String = "file"
  companion object {
    fun fromJson(j: org.json.JSONObject): G23_file = G23_file(
      uri = j.optString("uri").takeIf { it.isNotEmpty() && it != "null" },
      mime = j.optString("mime").takeIf { it.isNotEmpty() && it != "null" },
      name = j.optString("name").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class G21_error(
  val input: org.json.JSONObject?,
  val error: Session_StructuredError?,
  val content: List<G23>?,
  val metadata: org.json.JSONObject?,
) : G21() {
  override val status: String = "error"
  companion object {
    fun fromJson(j: org.json.JSONObject): G21_error = G21_error(
      input = j.optJSONObject("input") ?: org.json.JSONObject(),
      error = j.optJSONObject("error")?.let { Session_StructuredError.fromJson(it) },
      content = j.optJSONArray("content")?.let { x -> (0 until x.length()).mapNotNull { i -> x.optJSONObject(i)?.let { G23.fromJson(it) } } } ?: emptyList(),
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
    )
  }
}

data class G24(
  val created: Double?,
  val ran: Double?,
  val completed: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G24 = G24(
      created = j.optDouble("created", 0.0),
      ran = j.optDouble("ran", 0.0),
      completed = j.optDouble("completed", 0.0),
    )
  }
}

data class G19_tool(
  val id: String?,
  val name: String?,
  val executed: Boolean?,
  val providerState: Session_Message_ProviderState_2?,
  val providerResultState: Session_Message_ProviderState_3?,
  val state: G21?,
  val time: G24?,
) : G19() {
  override val type: String = "tool"
  companion object {
    fun fromJson(j: org.json.JSONObject): G19_tool = G19_tool(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      name = j.optString("name").takeIf { it.isNotEmpty() && it != "null" },
      executed = j.optBoolean("executed", false),
      providerState = j.optJSONObject("providerState")?.let { Session_Message_ProviderState_2.fromJson(it) },
      providerResultState = j.optJSONObject("providerResultState")?.let { Session_Message_ProviderState_3.fromJson(it) },
      state = j.optJSONObject("state")?.let { G21.fromJson(it) },
      time = j.optJSONObject("time")?.let { G24.fromJson(it) },
    )
  }
}

data class G25(
  val start: String?,
  val end: String?,
  val files: List<String>?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G25 = G25(
      start = j.optString("start").takeIf { it.isNotEmpty() && it != "null" },
      end = j.optString("end").takeIf { it.isNotEmpty() && it != "null" },
      files = j.optJSONArray("files")?.let { x -> (0 until x.length()).mapNotNull { i -> x.optString(i) } } ?: emptyList(),
    )
  }
}

data class Session_Message_ProviderState_4(val raw: org.json.JSONObject = org.json.JSONObject()) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_ProviderState_4 = Session_Message_ProviderState_4(j)
  }
}

data class Session_Message_Assistant_Retry(
  val attempt: Long?,
  val at: Double?,
  val error: Session_StructuredError?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_Assistant_Retry = Session_Message_Assistant_Retry(
      attempt = j.optLong("attempt", 0L),
      at = j.optDouble("at", 0.0),
      error = j.optJSONObject("error")?.let { Session_StructuredError.fromJson(it) },
    )
  }
}

data class Session_Message_Info_assistant(
  val id: String?,
  val metadata: org.json.JSONObject?,
  val time: G18?,
  val agent: String?,
  val model: Model_Ref?,
  val content: List<G19>?,
  val snapshot: G25?,
  val finish: String?,
  val rawFinish: String?,
  val providerState: Session_Message_ProviderState_4?,
  val cost: Money_USD?,
  val tokens: TokenUsage_Info?,
  val error: Session_StructuredError?,
  val retry: Session_Message_Assistant_Retry?,
) : Session_Message_Info() {
  override val type: String = "assistant"
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_Info_assistant = Session_Message_Info_assistant(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
      time = j.optJSONObject("time")?.let { G18.fromJson(it) },
      agent = j.optString("agent").takeIf { it.isNotEmpty() && it != "null" },
      model = j.optJSONObject("model")?.let { Model_Ref.fromJson(it) },
      content = j.optJSONArray("content")?.let { x -> (0 until x.length()).mapNotNull { i -> x.optJSONObject(i)?.let { G19.fromJson(it) } } } ?: emptyList(),
      snapshot = j.optJSONObject("snapshot")?.let { G25.fromJson(it) },
      finish = j.optString("finish").takeIf { it.isNotEmpty() && it != "null" },
      rawFinish = j.optString("rawFinish").takeIf { it.isNotEmpty() && it != "null" },
      providerState = j.optJSONObject("providerState")?.let { Session_Message_ProviderState_4.fromJson(it) },
      cost = j.optJSONObject("cost")?.let { Money_USD.fromJson(it) },
      tokens = j.optJSONObject("tokens")?.let { TokenUsage_Info.fromJson(it) },
      error = j.optJSONObject("error")?.let { Session_StructuredError.fromJson(it) },
      retry = j.optJSONObject("retry")?.let { Session_Message_Assistant_Retry.fromJson(it) },
    )
  }
}

data class G26(
  val created: Double?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): G26 = G26(
      created = j.optDouble("created", 0.0),
    )
  }
}

data class Session_Message_Info_idle(
  val id: String?,
  val metadata: org.json.JSONObject?,
  val time: G26?,
  val outcome: String?,
) : Session_Message_Info() {
  override val type: String = "idle"
  companion object {
    fun fromJson(j: org.json.JSONObject): Session_Message_Info_idle = Session_Message_Info_idle(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
      time = j.optJSONObject("time")?.let { G26.fromJson(it) },
      outcome = j.optString("outcome").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

sealed class Permission_Source {
  abstract val type: String
  companion object {
    fun fromJson(j: org.json.JSONObject): Permission_Source? = when (j.optString("type")) {
      "tool" -> Permission_Source_tool.fromJson(j)
      else -> null
    }
  }
}

data class Permission_Source_tool(
  val messageID: String?,
  val id: String?,
) : Permission_Source() {
  override val type: String = "tool"
  companion object {
    fun fromJson(j: org.json.JSONObject): Permission_Source_tool = Permission_Source_tool(
      messageID = j.optString("messageID").takeIf { it.isNotEmpty() && it != "null" },
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

data class Permission_Request(
  val id: String?,
  val sessionID: String?,
  val action: String?,
  val resources: List<String>?,
  val save: List<String>?,
  val metadata: org.json.JSONObject?,
  val source: Permission_Source?,
  val message: String?,
) {
  companion object {
    fun fromJson(j: org.json.JSONObject): Permission_Request = Permission_Request(
      id = j.optString("id").takeIf { it.isNotEmpty() && it != "null" },
      sessionID = j.optString("sessionID").takeIf { it.isNotEmpty() && it != "null" },
      action = j.optString("action").takeIf { it.isNotEmpty() && it != "null" },
      resources = j.optJSONArray("resources")?.let { x -> (0 until x.length()).mapNotNull { i -> x.optString(i) } } ?: emptyList(),
      save = j.optJSONArray("save")?.let { x -> (0 until x.length()).mapNotNull { i -> x.optString(i) } } ?: emptyList(),
      metadata = j.optJSONObject("metadata") ?: org.json.JSONObject(),
      source = j.optJSONObject("source")?.let { Permission_Source.fromJson(it) },
      message = j.optString("message").takeIf { it.isNotEmpty() && it != "null" },
    )
  }
}

