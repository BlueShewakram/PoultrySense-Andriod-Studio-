package com.example.poultrysense.data

import kotlin.math.abs

/**
 * Model representing a physical color tag (leg band / wing band) placed on a chicken.
 */
data class ChickenTagInfo(
    val tagNumber: Int,
    val colorName: String,
    val colorHex: String,
    val textColorHex: String,
    val chipBgHex: String,
    val chipBorderHex: String,
    val chipTextHex: String,
    val emoji: String,
    val shortLabel: String,
    val displayLabel: String,
    val physicalDescription: String
)

/**
 * Helper providing consistent, deterministic, bright physical tag color assignments
 * for all chickens in the flock.
 *
 * Farmers use simple, bright, distinguishable physical leg bands so chickens can be
 * instantly identified across the barn.
 */
object ChickenTagHelper {

    private data class TagColorDef(
        val name: String,
        val colorHex: String,
        val textColorHex: String,
        val chipBgHex: String,
        val chipBorderHex: String,
        val chipTextHex: String,
        val emoji: String
    )

    // 8 Bright, High-Contrast, Industry-Standard Poultry Tag Colors
    private val PALETTE = listOf(
        TagColorDef(
            name = "Red",
            colorHex = "#FF1744",
            textColorHex = "#FFFFFF",
            chipBgHex = "#FFF1F2",
            chipBorderHex = "#FECDD3",
            chipTextHex = "#BE123C",
            emoji = "🔴"
        ),
        TagColorDef(
            name = "Blue",
            colorHex = "#0091EA",
            textColorHex = "#FFFFFF",
            chipBgHex = "#F0F9FF",
            chipBorderHex = "#BAE6FD",
            chipTextHex = "#0369A1",
            emoji = "🔵"
        ),
        TagColorDef(
            name = "Yellow",
            colorHex = "#FFD600",
            textColorHex = "#000000",
            chipBgHex = "#FEFCE8",
            chipBorderHex = "#FEF08A",
            chipTextHex = "#854D0E",
            emoji = "🟡"
        ),
        TagColorDef(
            name = "Green",
            colorHex = "#00E676",
            textColorHex = "#000000",
            chipBgHex = "#F0FDF4",
            chipBorderHex = "#BBF7D0",
            chipTextHex = "#15803D",
            emoji = "🟢"
        ),
        TagColorDef(
            name = "Orange",
            colorHex = "#FF6D00",
            textColorHex = "#FFFFFF",
            chipBgHex = "#FFF7ED",
            chipBorderHex = "#FED7AA",
            chipTextHex = "#C2410C",
            emoji = "🟠"
        ),
        TagColorDef(
            name = "Purple",
            colorHex = "#AA00FF",
            textColorHex = "#FFFFFF",
            chipBgHex = "#FAF5FF",
            chipBorderHex = "#E9D5FF",
            chipTextHex = "#7E22CE",
            emoji = "🟣"
        ),
        TagColorDef(
            name = "Pink",
            colorHex = "#FF4081",
            textColorHex = "#FFFFFF",
            chipBgHex = "#FDF2F8",
            chipBorderHex = "#FBCFE8",
            chipTextHex = "#BE185D",
            emoji = "🌸"
        ),
        TagColorDef(
            name = "Cyan",
            colorHex = "#00E5FF",
            textColorHex = "#000000",
            chipBgHex = "#ECFEFF",
            chipBorderHex = "#A5F3FC",
            chipTextHex = "#0E7490",
            emoji = "🔷"
        )
    )

    /**
     * Deterministically maps a chicken ID number to its bright tag color.
     * Guaranteed consistent: Chicken #1 is always Red #1, Chicken #2 is always Blue #2, etc.
     */
    fun getTagInfo(chickenNumber: Int): ChickenTagInfo {
        val safeNumber = if (chickenNumber <= 0) 1 else chickenNumber
        val index = (abs(safeNumber - 1)) % PALETTE.size
        val colorDef = PALETTE[index]

        return ChickenTagInfo(
            tagNumber = safeNumber,
            colorName = colorDef.name,
            colorHex = colorDef.colorHex,
            textColorHex = colorDef.textColorHex,
            chipBgHex = colorDef.chipBgHex,
            chipBorderHex = colorDef.chipBorderHex,
            chipTextHex = colorDef.chipTextHex,
            emoji = colorDef.emoji,
            shortLabel = "${colorDef.name.uppercase()} #$safeNumber",
            displayLabel = "${colorDef.emoji} ${colorDef.name} Tag #$safeNumber",
            physicalDescription = "Bright ${colorDef.name} Leg Band #$safeNumber"
        )
    }

    /**
     * Resolves tag info from a string ID or null if unparseable.
     */
    fun getTagInfo(chickenIdStr: String?): ChickenTagInfo? {
        if (chickenIdStr.isNullOrBlank()) return null
        val digits = chickenIdStr.filter { it.isDigit() }
        val num = digits.toIntOrNull() ?: (abs(chickenIdStr.hashCode()) % 8 + 1)
        return getTagInfo(num)
    }

    /**
     * Resolves chicken tag information from alert fields, extracting from chickenId,
     * or parsing from the title / description if not directly provided.
     */
    fun resolveTag(chickenId: String?, title: String? = null, description: String? = null): ChickenTagInfo? {
        if (!chickenId.isNullOrBlank()) {
            return getTagInfo(chickenId)
        }

        // Try extracting from title or description (e.g. "Chicken #3", "Tag #2", "#5")
        val combined = "${title.orEmpty()} ${description.orEmpty()}"
        val match = Regex("""(?:Chicken|Tag|Band|Bird)\s*#?(\d+)""", RegexOption.IGNORE_CASE).find(combined)
            ?: Regex("""#(\d+)""").find(combined)

        if (match != null) {
            val num = match.groupValues[1].toIntOrNull()
            if (num != null) {
                return getTagInfo(num)
            }
        }

        return null
    }

    /**
     * Returns standard tags for 1..8 for legends, settings, and physical band inventory guides.
     */
    fun getAllStandardTags(): List<ChickenTagInfo> {
        return (1..PALETTE.size).map { getTagInfo(it) }
    }
}
