package com.supplices.kemimotowear.model

import androidx.annotation.StringRes
import com.supplices.kemimotowear.R

/**
 * Atomic step within an automated heating profile.
 *
 * @property durationMinutes Phase duration in minutes
 * @property neckLevel Heating intensity for neck (0-3)
 * @property backLevel Heating intensity for back (0-3)
 * @property bellyLevel Heating intensity for belly (0-3)
 * @property stepLabelRes Localized string resource ID describing this phase
 */
data class ProfileStep(
    val durationMinutes: Int,
    val neckLevel: Int,
    val backLevel: Int,
    val bellyLevel: Int,
    @StringRes val stepLabelRes: Int
)

/**
 * Automated heat management routine.
 * Allows intelligent duty cycling to extend garment battery runtime up to 300%
 * while maintaining core body temperature through periodic burst heating.
 *
 * @property id Unique machine identifier used by Gemini Deep Links
 * @property titleRes Localized string resource for profile title
 * @property subtitleRes Localized string resource for duty-cycle description
 * @property steps Sequence of heating phases executed in chronological order
 * @property isCyclic When true, loops back to step 0 upon completing the last step
 */
data class HeatingProfile(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val steps: List<ProfileStep>,
    val isCyclic: Boolean = true
)

/**
 * Predefined, field-tested duty-cycle profiles designed for motorcycle riders and outdoor sub-zero operations.
 * Fully localized in French and English.
 */
object PredefinedProfiles {

    /**
     * 1. Balanced Eco Profile: 5 minutes High / 5 minutes Low
     */
    val ECO_5_5 = HeatingProfile(
        id = "eco_5_5",
        titleRes = R.string.profile_eco_5_5_title,
        subtitleRes = R.string.profile_eco_5_5_subtitle,
        steps = listOf(
            ProfileStep(durationMinutes = 5, neckLevel = 3, backLevel = 3, bellyLevel = 3, stepLabelRes = R.string.profile_eco_5_5_step1),
            ProfileStep(durationMinutes = 5, neckLevel = 1, backLevel = 1, bellyLevel = 1, stepLabelRes = R.string.profile_eco_5_5_step2)
        ),
        isCyclic = true
    )

    /**
     * 2. Deep Cold Conservative: 5 minutes High / 10 minutes Low
     */
    val ECO_5_10 = HeatingProfile(
        id = "eco_5_10",
        titleRes = R.string.profile_eco_5_10_title,
        subtitleRes = R.string.profile_eco_5_10_subtitle,
        steps = listOf(
            ProfileStep(durationMinutes = 5, neckLevel = 3, backLevel = 3, bellyLevel = 3, stepLabelRes = R.string.profile_eco_5_10_step1),
            ProfileStep(durationMinutes = 10, neckLevel = 1, backLevel = 1, bellyLevel = 1, stepLabelRes = R.string.profile_eco_5_10_step2)
        ),
        isCyclic = true
    )

    /**
     * 3. Survival Intermittent: 10 minutes Medium / 10 minutes Complete Off
     */
    val EXTREME_10_10 = HeatingProfile(
        id = "extreme_10_10",
        titleRes = R.string.profile_extreme_10_10_title,
        subtitleRes = R.string.profile_extreme_10_10_subtitle,
        steps = listOf(
            ProfileStep(durationMinutes = 10, neckLevel = 2, backLevel = 2, bellyLevel = 2, stepLabelRes = R.string.profile_extreme_10_10_step1),
            ProfileStep(durationMinutes = 10, neckLevel = 0, backLevel = 0, bellyLevel = 0, stepLabelRes = R.string.profile_extreme_10_10_step2)
        ),
        isCyclic = true
    )

    /**
     * 4. Rapid Warmup: 3 minutes Maximum Boost, then settles at Medium (2)
     */
    val BOOST_PREHEAT = HeatingProfile(
        id = "boost_preheat",
        titleRes = R.string.profile_boost_title,
        subtitleRes = R.string.profile_boost_subtitle,
        steps = listOf(
            ProfileStep(durationMinutes = 3, neckLevel = 3, backLevel = 3, bellyLevel = 3, stepLabelRes = R.string.profile_boost_step1),
            ProfileStep(durationMinutes = 120, neckLevel = 2, backLevel = 2, bellyLevel = 2, stepLabelRes = R.string.profile_boost_step2)
        ),
        isCyclic = false
    )

    /**
     * 5. Motorcycle Wind-Chill Shield: Maximum collar & back heat against frontal wind deflection.
     */
    val MOTO_WIND = HeatingProfile(
        id = "moto_wind",
        titleRes = R.string.profile_moto_title,
        subtitleRes = R.string.profile_moto_subtitle,
        steps = listOf(
            ProfileStep(durationMinutes = 60, neckLevel = 3, backLevel = 3, bellyLevel = 1, stepLabelRes = R.string.profile_moto_step1)
        ),
        isCyclic = true
    )

    /** Complete catalog of all registered profiles */
    val ALL_PROFILES = listOf(
        ECO_5_5,
        ECO_5_10,
        EXTREME_10_10,
        BOOST_PREHEAT,
        MOTO_WIND
    )

    /** Resolves a profile by its unique ID case-insensitively */
    fun findById(id: String): HeatingProfile? {
        return ALL_PROFILES.find { it.id.equals(id, ignoreCase = true) }
    }
}
