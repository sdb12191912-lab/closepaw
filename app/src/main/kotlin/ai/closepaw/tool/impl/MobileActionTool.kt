package ai.closepaw.tool.impl

import ai.closepaw.tool.ToolInvocation
import ai.closepaw.tool.ToolSpec
import ai.closepaw.tool.ValidationResult
import ai.closepaw.tool.action.ClickExecutor
import ai.closepaw.tool.action.LongPressExecutor
import ai.closepaw.tool.action.ScrollExecutor
import ai.closepaw.tool.action.SwipeExecutor
import ai.closepaw.tool.action.Target
import ai.closepaw.tool.action.TypeExecutor
import org.json.JSONArray
import org.json.JSONObject

/**
 * MobileActionTool — consolidated tool for all touch interactions.
 *
 * Implements ToolSpec directly. No base class, no ActionHandler indirection.
 * Validation is inline; execution is delegated to per-action executors.
 *
 * Targeting is canonicalized by priority: valid element_index, then text, then x/y.
 * Extra target fields are treated as hints. x/y may accompany a semantic
 * target as a fallback coordinate hint. Bare x/y is allowed for click/
 * long_press/type, but scroll rejects it because scroll is area-based.
 *
 * A negative element_index emitted by an LLM is treated as "no element target"
 * when a valid text or coordinate target is also present. This makes coordinate
 * fallback robust instead of failing validation on sentinel values such as -1.
 */
class MobileActionTool : ToolSpec {

    override val name: String = "mobile_action"

    override val description: String = """
Perform touch interactions on the device screen.

Navigation reliability rules:
- Treat a user-provided route such as "App / Settings / Wallet" or "App → Settings → Wallet" as ordered waypoints. Complete them in that order; do not replace the route with search unless the requested waypoint is unavailable.
- After every click, long press, type, scroll, or swipe, use the returned fresh screen observation before choosing the next target. Element indices and coordinates from an older screen may be stale.
- Prefer current visible text or a valid current element_index over raw coordinates. If a gesture fallback is marked [unverified], do not assume the requested UI action succeeded; inspect the fresh observation and choose the target again.
- Do not repeat the same click on an unchanged screen. Re-resolve the element from the latest observation or try a different visible target.
- For text entry, prefer a current editable element_index/text. If the field is not exposed as editable, tap it to focus, then call type without a target so the focused field can be used.
- Never send element_index=-1 as the target. If no valid element index exists and coordinates are known, omit element_index and use x/y only.

Targeting (click, long_press, type): prefer a valid element_index (>=0), then text, then x/y coordinates. If multiple target fields are supplied, a valid element_index is primary, text is a label hint, and x/y is only a fallback coordinate hint. A negative element_index is ignored when another valid target is present.

Actions:
- click: Tap element
- long_press: Long press element
- type: Type text into element (or focused field if no target)
- scroll: Scroll content direction (direction="down" reveals content below). Optional element_index/text for specific scrollable; bare x/y is not accepted.
- swipe: Precision coordinate gesture (sliders, drag-and-drop). Requires start/end [x,y].
""".trimIndent()

    override val parameterSchema: JSONObject by lazy { buildSchema() }

    override fun validate(params: JSONObject): ValidationResult {
        val action = params.optString("action", "")
        if (action.isEmpty()) return ValidationResult.Invalid("Missing required parameter: action")

        if (params.has("x1") || params.has("y1") || params.has("x2") || params.has("y2")) {
            return ValidationResult.Invalid(
                "Bounds selector (x1/y1/x2/y2) is no longer supported. " +
                "Use element_index, text, or x/y instead."
            )
        }

        return when (action) {
            "click", "long_press" -> validateTargetedAction(params, action, required = true)
            "type" -> validateTypeAction(params)
            "scroll" -> validateScrollAction(params)
            "swipe" -> validateSwipeAction(params)
            else -> ValidationResult.Invalid("Unknown action: '$action'. Valid: click, long_press, scroll, swipe, type")
        }
    }

    override fun createInvocation(params: JSONObject): ToolInvocation {
        val action = params.getString("action")
        val target = parseOptionalTarget(params)
        val description = buildDescription(action, target, params)

        return MobileActionInvocation(params, description) { platform, snapshot, isCancelled, appClassifier ->
            when (action) {
                "click" -> {
                    val requiredTarget = requireTarget(action, target)
                    ClickExecutor().execute(requiredTarget, snapshot, platform, isCancelled, appClassifier)
                }
                "long_press" -> {
                    val requiredTarget = requireTarget(action, target)
                    LongPressExecutor().execute(
                        requiredTarget,
                        params.optLong("duration_ms", 1000),
                        snapshot,
                        platform,
                        isCancelled,
                        appClassifier
                    )
                }
                "type" -> TypeExecutor().execute(
                    target, params.getString("input_text"),
                    params.optBoolean("clear", false), snapshot, platform, isCancelled, appClassifier
                )
                "scroll" -> ScrollExecutor().execute(
                    target, params.getString("direction"),
                    snapshot, platform, isCancelled, appClassifier
                )
                "swipe" -> SwipeExecutor().execute(params, snapshot, platform, isCancelled, appClassifier)
                else -> error("Unreachable: validated above")
            }
        }
    }

    private fun hasValidElementIndex(params: JSONObject): Boolean =
        params.has("element_index") && params.optInt("element_index", -1) >= 0

    private fun hasInvalidElementIndex(params: JSONObject): Boolean =
        params.has("element_index") && params.optInt("element_index", -1) < 0

    private fun hasTextTarget(params: JSONObject): Boolean =
        params.optString("text", "").trim().isNotEmpty()

    private fun hasCompleteCoordinates(params: JSONObject): Boolean =
        params.has("x") && params.has("y") && params.optInt("x", -1) >= 0 && params.optInt("y", -1) >= 0

    private fun validateTargetedAction(
        params: JSONObject, action: String, required: Boolean
    ): ValidationResult {
        val hasElement = hasValidElementIndex(params)
        val hasText = hasTextTarget(params)
        val hasAnyCoord = params.has("x") || params.has("y")
        val hasCoordinates = hasCompleteCoordinates(params)

        if (!hasElement && !hasText && !hasCoordinates && required) {
            return if (hasInvalidElementIndex(params)) {
                ValidationResult.Invalid(
                    "$action has element_index < 0 and no valid fallback target. " +
                        "Omit element_index and provide text or valid x/y coordinates."
                )
            } else {
                ValidationResult.Invalid(
                    "$action requires one of: element_index >= 0, text, or x/y coordinates"
                )
            }
        }

        // Negative element_index is a common LLM sentinel for "no semantic target".
        // Ignore it when another valid target exists; only reject it when it is the sole target.
        if (hasInvalidElementIndex(params) && !hasText && !hasCoordinates) {
            return ValidationResult.Invalid("element_index must be >= 0 when used as the only target")
        }

        validateCoordinates(params, action)?.let { return it }
        if (params.has("text_index") && !hasText && !hasElement && !hasAnyCoord) {
            return ValidationResult.Invalid("text_index requires text")
        }

        if (action == "long_press") {
            val duration = params.optLong("duration_ms", 1000)
            if (duration < 0) return ValidationResult.Invalid("duration_ms must be non-negative")
            if (duration > 30000) return ValidationResult.Invalid("duration_ms must be <= 30000")
        }

        return ValidationResult.Valid
    }

    private fun validateTypeAction(params: JSONObject): ValidationResult {
        if (!params.has("input_text")) {
            return ValidationResult.Invalid("type action requires input_text")
        }
        return validateTargetedAction(params, "type", required = false)
    }

    private fun validateScrollAction(params: JSONObject): ValidationResult {
        val direction = params.optString("direction", "").trim().lowercase()
        if (direction.isEmpty()) {
            return ValidationResult.Invalid("scroll requires direction (up/down/left/right)")
        }
        if (direction !in setOf("up", "down", "left", "right")) {
            return ValidationResult.Invalid("direction must be one of: up, down, left, right")
        }

        val hasElement = hasValidElementIndex(params)
        val hasText = hasTextTarget(params)
        val hasAnyCoord = params.has("x") || params.has("y")

        if (hasAnyCoord && !hasElement && !hasText) {
            return ValidationResult.Invalid(
                "scroll does not accept bare x/y. Provide element_index >= 0 or text; x/y is only a coordinate hint for a semantic target."
            )
        }

        if (hasInvalidElementIndex(params) && !hasText) {
            return ValidationResult.Invalid(
                "scroll ignores negative element_index only when a valid text target is present"
            )
        }

        validateCoordinates(params, "scroll")?.let { return it }
        if (params.has("text_index") && !hasText && !hasElement) {
            return ValidationResult.Invalid("text_index requires text")
        }
        return ValidationResult.Valid
    }

    private fun validateCoordinates(params: JSONObject, action: String): ValidationResult.Invalid? {
        if (!params.has("x") && !params.has("y")) return null
        if (!params.has("x") || !params.has("y")) {
            return ValidationResult.Invalid("$action requires both x and y when using coordinates")
        }
        if (params.optInt("x", -1) < 0 || params.optInt("y", -1) < 0) {
            return ValidationResult.Invalid("x and y must be >= 0")
        }
        return null
    }

    private fun validateSwipeAction(params: JSONObject): ValidationResult {
        if (!params.has("start")) return ValidationResult.Invalid("swipe requires start coordinate [x, y]")
        val start = params.optJSONArray("start")
        if (start == null || start.length() != 2) {
            return ValidationResult.Invalid("start must be an array of [x, y]")
        }
        if (!params.has("end")) return ValidationResult.Invalid("swipe requires end coordinate [x, y]")
        val end = params.optJSONArray("end")
        if (end == null || end.length() != 2) {
            return ValidationResult.Invalid("end must be an array of [x, y]")
        }
        try {
            val sx = start.getInt(0); val sy = start.getInt(1)
            val ex = end.getInt(0); val ey = end.getInt(1)
            if (sx < 0 || sy < 0 || ex < 0 || ey < 0) {
                return ValidationResult.Invalid("Coordinates must be non-negative")
            }
        } catch (e: Exception) {
            return ValidationResult.Invalid("Coordinates must be integers")
        }
        return ValidationResult.Valid
    }

    private fun parseOptionalTarget(params: JSONObject): Target? {
        val hint = if (hasCompleteCoordinates(params)) {
            Target.Coordinate(params.getInt("x"), params.getInt("y"))
        } else null

        return when {
            hasValidElementIndex(params) ->
                Target.ElementIndex(params.getInt("element_index"), hint)
            hasTextTarget(params) ->
                Target.Text(params.getString("text"), params.optInt("text_index", 0), hint)
            hint != null -> hint
            else -> null
        }
    }

    private fun buildDescription(action: String, target: Target?, params: JSONObject): String {
        val targetDesc = when (target) {
            is Target.ElementIndex -> "element ${target.index}"
            is Target.Text -> "text \"${target.text}\" (index ${target.textIndex})"
            is Target.Coordinate -> "(${target.x},${target.y})"
            null -> if (action == "type") "focused field" else ""
        }
        return when (action) {
            "click" -> "Click $targetDesc"
            "long_press" -> "Long press $targetDesc for ${params.optLong("duration_ms", 1000)}ms"
            "type" -> {
                val input = params.optString("input_text", "").take(30)
                val clear = if (params.optBoolean("clear", false)) " (clear first)" else ""
                "Type \"$input\" into $targetDesc$clear"
            }
            "scroll" -> {
                val dir = params.optString("direction", "")
                if (targetDesc.isNotEmpty()) "Scroll $dir on $targetDesc"
                else "Scroll $dir"
            }
            "swipe" -> {
                val start = params.optJSONArray("start")
                val end = params.optJSONArray("end")
                if (start != null && end != null) {
                    "Swipe (${start.optInt(0)},${start.optInt(1)})→(${end.optInt(0)},${end.optInt(1)})"
                } else {
                    "Swipe"
                }
            }
            else -> "$action $targetDesc"
        }
    }

    private fun requireTarget(action: String, target: Target?): Target {
        return requireNotNull(target) {
            "$action requires a target. Validate parameters before creating invocation."
        }
    }

    private fun buildSchema(): JSONObject {
        val properties = JSONObject().apply {
            put("agent_thought", JSONObject().apply {
                put("type", "string")
                put("description", "Brief reason for why this action is being performed")
            })
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("click", "long_press", "scroll", "swipe", "type")))
                put("description", "The action to perform")
            })
            put("element_index", JSONObject().apply {
                put("type", "integer")
                put("minimum", 0)
                put("description", "Optional index from the CURRENT screen observation only. Omit this field if no valid element index exists; never send -1. Never reuse an index after the screen changes.")
            })
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "Target element by visible text on the CURRENT screen. Prefer this over raw coordinates when possible.")
            })
            put("text_index", JSONObject().apply {
                put("type", "integer")
                put("minimum", 0)
                put("description", "Zero-based index when multiple elements match text (default 0)")
            })
            put("x", JSONObject().apply {
                put("type", "integer")
                put("minimum", 0)
                put("description", "Target X coordinate in pixels. Use only from the current screen; coordinates become stale after navigation. If using coordinate-only targeting, omit element_index entirely.")
            })
            put("y", JSONObject().apply {
                put("type", "integer")
                put("minimum", 0)
                put("description", "Target Y coordinate in pixels. Use only from the current screen; coordinates become stale after navigation. If using coordinate-only targeting, omit element_index entirely.")
            })
            put("input_text", JSONObject().apply {
                put("type", "string")
                put("description", "Text to type (type action only)")
            })
            put("clear", JSONObject().apply {
                put("type", "boolean")
                put("description", "Clear field before typing (type action, default false)")
            })
            put("direction", JSONObject().apply {
                put("type", "string")
                put("description", "Scroll content direction: 'down' reveals content below, 'up' reveals content above (scroll action only)")
                put("enum", JSONArray(listOf("up", "down", "left", "right")))
            })
            put("start", JSONObject().apply {
                put("type", "array")
                put("description", "Swipe start coordinate [x, y] in pixels (swipe action only)")
                put("items", JSONObject().put("type", "integer").put("minimum", 0))
            })
            put("end", JSONObject().apply {
                put("type", "array")
                put("description", "Swipe end coordinate [x, y] in pixels (swipe action only)")
                put("items", JSONObject().put("type", "integer").put("minimum", 0))
            })
            put("duration_ms", JSONObject().apply {
                put("type", "integer")
                put("minimum", 0)
                put("maximum", 30000)
                put("description", "Duration in ms: hold time for long_press (default 1000), gesture time for swipe (default 400)")
            })
        }

        return JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            put("required", JSONArray(listOf("action")))
            put("additionalProperties", false)
        }
    }
}
