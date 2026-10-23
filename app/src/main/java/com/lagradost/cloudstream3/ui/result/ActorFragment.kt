package com.lagradost.cloudstream3.ui.result

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.FragmentActorBinding
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.search.SearchFragment
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 5B: in-app actor overview page.
 *
 * Opened from a cast tap on the result screens (see [ActorAdaptor] clickCallback
 * and [openActor]). Shows the actor photo/name/role, a best-effort biography
 * extract fetched from Wikipedia (silently hidden on any failure), and a
 * button that opens the regular search screen pre-filled with the actor name.
 */
class ActorFragment : BaseFragment<FragmentActorBinding>(
    BindingCreator.Inflate(FragmentActorBinding::inflate)
) {
    companion object {
        const val ARG_NAME = "actor_name"
        const val ARG_IMAGE = "actor_image"
        const val ARG_ROLE = "actor_role"
        const val ARG_ROLE_STRING = "actor_role_string"
        const val ARG_TITLE = "actor_title"

        /** Opens the actor page, mirroring how QuickSearchFragment.pushSearch navigates. */
        fun openActor(activity: Activity?, item: ActorData, sourceTitle: String?) {
            activity.navigate(R.id.global_to_navigation_actor, Bundle().apply {
                putString(ARG_NAME, item.actor.name)
                putString(ARG_IMAGE, item.actor.image)
                putString(ARG_ROLE, item.role?.name)
                putString(ARG_ROLE_STRING, item.roleString)
                putString(ARG_TITLE, sourceTitle)
            })
        }
    }

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)
    }

    override fun onBindingCreated(binding: FragmentActorBinding) {
        val name = arguments?.getString(ARG_NAME).orEmpty()
        val image = arguments?.getString(ARG_IMAGE)
        val roleString = arguments?.getString(ARG_ROLE_STRING)
        val sourceTitle = arguments?.getString(ARG_TITLE)

        binding.apply {
            actorToolbar.apply {
                title = name
                if (isLayout(PHONE or EMULATOR)) {
                    setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
                    setNavigationOnClickListener {
                        dispatchBackPressed()
                    }
                }
            }

            // Same image-loading helper the cast list itself uses.
            actorPhoto.loadImage(image)
            // Phase 8.10: initials avatar under the photo — shown when the
            // actor has no photo at all (the Wikipedia upgrade in
            // fetchWikipediaOverview still fills the photo when it can;
            // this is the last resort, never an empty card).
            actorInitials.text = initialsForActor(name)
            actorName.text = name

            // Role line: character name, role category and source title,
            // e.g. "Mikasa Ackerman • Main • in Attack on Titan".
            val roleLabel = arguments?.getString(ARG_ROLE)?.let { roleName ->
                try {
                    ActorRole.valueOf(roleName)
                } catch (_: Throwable) {
                    null
                }
            }?.let { role ->
                getString(
                    when (role) {
                        ActorRole.Main -> R.string.actor_main
                        ActorRole.Supporting -> R.string.actor_supporting
                        ActorRole.Background -> R.string.actor_background
                    }
                )
            }
            val roleParts = listOfNotNull(
                roleString?.takeIf { it.isNotBlank() },
                roleLabel,
                sourceTitle?.takeIf { it.isNotBlank() }
                    ?.let { getString(R.string.actor_in_format, it) }
            )
            actorRole.isGone = roleParts.isEmpty()
            actorRole.text = roleParts.joinToString(" • ")

            actorSearchButton.text = getString(R.string.actor_search_titles, name)
            actorSearchButton.setOnClickListener {
                // SearchFragment auto-submits the SEARCH_QUERY argument.
                activity.navigate(R.id.navigation_search, Bundle().apply {
                    putString(SearchFragment.SEARCH_QUERY, name)
                })
            }
        }

        if (name.isNotBlank()) {
            fetchWikipediaOverview(name, image)
        }
    }

    /**
     * Best-effort Wikipedia summary fetch (same app.get + parsedSafe pattern
     * as VotingApi). The Overview section only becomes visible when a
     * non-empty extract comes back; every failure path leaves it hidden.
     */
    private fun fetchWikipediaOverview(name: String, image: String?) {
        ioSafe {
            val summary = app.get(
                "https://en.wikipedia.org/api/rest_v1/page/summary/${
                    Uri.encode(name.replace(' ', '_'))
                }",
                // Wikimedia's API policy requires an identifying User-Agent;
                // without one the request may be blocked and the overview
                // would silently never appear.
                headers = mapOf(
                    "User-Agent" to "CloudStreamPro/1.0 (myself@nitrogen.bond)"
                )
            ).parsedSafe<WikipediaSummary>()

            val extract = summary?.extract?.takeIf { it.isNotBlank() }
            val wikiImage = summary?.originalimage?.source
                ?: summary?.thumbnail?.source
            if (extract == null && wikiImage.isNullOrBlank()) return@ioSafe

            main {
                binding?.apply {
                    if (extract != null) {
                        actorOverviewHeader.isVisible = true
                        actorOverview.isVisible = true
                        actorOverview.text = extract
                    }
                    // Only upgrade the photo from Wikipedia when the
                    // provider did not supply one.
                    if (image.isNullOrBlank() && !wikiImage.isNullOrBlank()) {
                        actorPhoto.loadImage(wikiImage)
                    }
                }
            }
        }
    }

    @Serializable
    private data class WikipediaImage(
        @JsonProperty("source") @SerialName("source") val source: String? = null,
    )

    @Serializable
    private data class WikipediaSummary(
        @JsonProperty("extract") @SerialName("extract") val extract: String? = null,
        @JsonProperty("thumbnail") @SerialName("thumbnail") val thumbnail: WikipediaImage? = null,
        @JsonProperty("originalimage") @SerialName("originalimage") val originalimage: WikipediaImage? = null,
    )
}
