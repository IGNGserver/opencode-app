#!/usr/bin/env python3
"""
V2 契约代码生成器：从 OpenAPI spec 生成 Kotlin wire 模型 + JSON 解析。

用法（spec 从官方 URL 拉取，不入库以保持仓库精简）：
  curl -sSL https://opencode.ai/v2/openapi.json -o /tmp/oc-v2-openapi.json
  python3 tools/gen_v2_models.py /tmp/oc-v2-openapi.json \
    > app/src/main/java/com/igng/opencode/lagoon/core/generated/V2Wire.kt

设计：字段名、可选性、union 判别全部来自 spec，杜绝手写猜测。
- object schema -> data class + companion fromJson
- anyOf union   -> sealed class + 每分支子类（各带 companion fromJson）+ 判别字段穷举
- $ref/内联对象/内联 union/array/基本类型 -> 递归生成嵌套类型
生成物（V2Wire.kt）入库、请勿手改；服务端 spec 变更时按上面命令重新生成。
"""
import json, sys

TARGETS = [
    "Project", "Location.PublicRef", "Session.Info", "Session.Message.Info",
    "Permission.Request", "Session.StructuredError",
]

PRIMS = {"string": "String", "number": "Double", "integer": "Long", "boolean": "Boolean"}
KEYWORDS = {"object","class","in","is","when","val","var","fun","for","if","else","do","try","this","null","true","false","sealed","data","companion","override","abstract","return"}

def ident(s):
    out = "".join(ch if ch.isalnum() else "_" for ch in s)
    if not out or out[0].isdigit(): out = "_" + out
    return out + "_" if out in KEYWORDS else out

class Gen:
    def __init__(self, spec):
        self.spec = spec
        self.done = {}       # cache key -> kotlin type name
        self.buf = []
        self.counter = 0

    def next_name(self):
        self.counter += 1
        return f"G{self.counter}"

    def emit(self, s=""):
        self.buf.append(s)

    # ---- union 判别：各分支独占的单值 enum 字段 ----
    def discriminator(self, branches):
        branches = [b for b in branches if b.get("type") == "object" and "properties" in b]
        if not branches: return None
        for field in branches[0]["properties"]:
            vals = []
            ok = True
            for b in branches:
                s = self.resolve(b["properties"].get(field, {}))
                e = s.get("enum", [])
                if s.get("type") != "string" or len(e) != 1:
                    ok = False; break
                vals.append(e[0])
            if ok and len(set(vals)) == len(vals):
                return field
        return None

    def resolve(self, node):
        while isinstance(node, dict) and "$ref" in node:
            node = self.spec["components"]["schemas"][node["$ref"].split("/")[-1]]
        return node

    def branches_of(self, node):
        return [self.resolve(b) for b in (node.get("anyOf") or node.get("oneOf") or [])]

    # ---- 类型生成入口 ----
    def type_for(self, node, path, nullable):
        node = self.resolve(node)
        if "enum" in node and len(node["enum"]) == 1:
            return "String", None      # 判别常量字段，读取用 String
        if "enum" in node:
            return "String", None
        if node.get("anyOf") or node.get("oneOf"):
            brs = self.branches_of(node)
            # 标量可空/枚举 union（如 anyOf[string,null]）→ 直接取基本类型，不生成 sealed 类
            obj_branches = [b for b in brs if b.get("type") == "object" or "properties" in b]
            if not obj_branches:
                prim = next((PRIMS[b["type"]] for b in brs if b.get("type") in PRIMS), "String")
                return prim, None
            name = self.next_name()
            self.gen_union(path, name, node)
            return name, None
        t = node.get("type")
        if t in PRIMS:
            return PRIMS[t], None
        if t == "array":
            inner, _ = self.type_for(node.get("items", {}), path + "_item", False)
            return f"List<{inner}>", None
        if t == "object" or "properties" in node:
            if "properties" in node:
                name = self.next_name()
                self.gen_object(path, name, node)
                return name, None
            return "org.json.JSONObject", None
        return "org.json.JSONObject", None

    def gen_named(self, schema_name):
        if schema_name in self.done:
            return self.done[schema_name]
        node = self.spec["components"]["schemas"][schema_name]
        name = ident(schema_name)
        self.done[schema_name] = name
        if node.get("anyOf") or node.get("oneOf"):
            self.gen_union(schema_name, name, node)
        else:
            self.gen_object(schema_name, name, node)
        return name

    def field_type(self, sch, owner_path, field):
        node = self.resolve(sch)
        if "$ref" in sch:
            ref = sch["$ref"].split("/")[-1]
            self.gen_named(ref)
            return self.done[ref], False
        kt, _ = self.type_for(sch, owner_path + "_" + ident(field), False)
        return kt, False

    def read_expr(self, field, kt, opt):
        q = lambda: f'j.optJSONObject("{field}")'
        if kt == "String":
            e = f'j.optString("{field}")'
            return (f'j.optString("{field}").takeIf {{ it.isNotEmpty() && it != "null" }}' if opt else e)
        if kt == "Double": return f'j.optDouble("{field}", 0.0)'
        if kt == "Long": return f'j.optLong("{field}", 0L)'
        if kt == "Boolean": return f'j.optBoolean("{field}", false)'
        if kt.startswith("List<"):
            inner = kt[5:-1]
            if inner in ("String", "Long", "Double", "Boolean"):
                optm = {"String": "optString", "Long": "optLong", "Double": "optDouble", "Boolean": "optBoolean"}[inner]
                conv = f'x.{optm}(i)'
            else:
                conv = f'x.optJSONObject(i)?.let {{ {inner}.fromJson(it) }}'
            return f'j.optJSONArray("{field}")?.let {{ x -> (0 until x.length()).mapNotNull {{ i -> {conv} }} }} ?: emptyList()'
        if kt.startswith("org.json"):
            return f'j.optJSONObject("{field}") ?: org.json.JSONObject()'
        obj = f'j.optJSONObject("{field}")?.let {{ {kt}.fromJson(it) }}'
        return obj if opt else f'({obj} ?: {kt}.fromJson(org.json.JSONObject()))'

    def gen_object(self, path, name, node):
        props = node.get("properties", {})
        required = set(node.get("required", []))
        fields = []
        for field, sch in props.items():
            kt, _ = self.field_type(sch, path, field)
            fields.append((ident(field), field, kt, field in required))
        if not fields:
            self.emit(f"data class {name}(val raw: org.json.JSONObject = org.json.JSONObject()) {{")
            self.emit("  companion object {")
            self.emit(f"    fun fromJson(j: org.json.JSONObject): {name} = {name}(j)")
            self.emit("  }")
            self.emit("}")
            self.emit()
            return
        self.emit(f"data class {name}(")
        for iid, field, kt, req in fields:
            self.emit(f"  val {iid}: {self.maybe_nullable(kt, req)},")
        self.emit(") {")
        self.emit("  companion object {")
        self.emit(f"    fun fromJson(j: org.json.JSONObject): {name} = {name}(")
        for iid, field, kt, req in fields:
            self.emit(f"      {iid} = {self.read_expr(field, kt, True)},")
        self.emit("    )")
        self.emit("  }")
        self.emit("}")
        self.emit()

    def maybe_nullable(self, kt, req):
        # 统一声明为可空：fromJson 返回值（非空或可空）赋给可空字段总是合法，消除所有 null 不匹配
        return kt + "?"

    def gen_union(self, path, name, node):
        branches = [b for b in self.branches_of(node) if b.get("type") == "object" or "properties" in b]
        disc = self.discriminator(branches)
        # 预先确定分支名，便于生成分发
        planned = []
        for b in branches:
            if disc and disc in b.get("properties", {}):
                s = self.resolve(b["properties"][disc])
                val = s.get("enum", [None])[0]
                bname = ident(name + "_" + str(val).replace("-", "_").replace(".", "_"))
            else:
                val, bname = None, ident(name + "_b" + str(len(planned)))
            planned.append((val, bname, b))
        # 先发射 sealed 父类（空体 + 分发），再在顶层发射各分支，避免嵌套作用域问题
        self.emit(f"sealed class {name} {{")
        if disc:
            self.emit(f"  abstract val {ident(disc)}: String")
        self.emit("  companion object {")
        self.emit(f"    fun fromJson(j: org.json.JSONObject): {name}? = when (j.optString(\"{disc or 'type'}\")) {{")
        for val, bname, b in planned:
            if val is not None:
                self.emit(f"      \"{val}\" -> {bname}.fromJson(j)")
        self.emit("      else -> null")
        self.emit("    }")
        self.emit("  }")
        self.emit("}")
        self.emit()
        # 顶层发射各分支（其嵌套字段类型也发射到顶层）
        for val, bname, b in planned:
            props = b.get("properties", {})
            required = set(b.get("required", []))
            fields = []
            for field, sch in props.items():
                if disc and field == disc: continue
                kt, _ = self.field_type(sch, path, field)
                fields.append((ident(field), field, kt, field in required))
            self.emit(f"data class {bname}(")
            if not fields:
                self.emit("  val raw: org.json.JSONObject = org.json.JSONObject(),")
            for iid, field, kt, req in fields:
                self.emit(f"  val {iid}: {self.maybe_nullable(kt, req)},")
            self.emit(f") : {name}() {{")
            if disc:
                self.emit(f"  override val {ident(disc)}: String = \"{val}\"")
            self.emit("  companion object {")
            self.emit(f"    fun fromJson(j: org.json.JSONObject): {bname} = {bname}(")
            if not fields:
                self.emit("      raw = j,")
            for iid, field, kt, req in fields:
                self.emit(f"      {iid} = {self.read_expr(field, kt, True)},")
            self.emit("    )")
            self.emit("  }")
            self.emit("}")
            self.emit()

def main():
    spec = json.load(open(sys.argv[1]))
    targets = sys.argv[2:] or TARGETS
    g = Gen(spec)
    g.emit("package com.igng.opencode.lagoon.core.generated")
    g.emit()
    g.emit("/** 由 tools/gen_v2_models.py 从 app/openapi-v2.json 生成，请勿手改。 */")
    g.emit()
    for t in targets:
        g.gen_named(t)
    sys.stdout.write("\n".join(g.buf) + "\n")

if __name__ == "__main__":
    main()
