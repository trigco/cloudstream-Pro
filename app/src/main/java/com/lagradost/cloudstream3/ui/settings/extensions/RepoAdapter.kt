package com.lagradost.cloudstream3.ui.settings.extensions

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import coil3.transform.CircleCropTransformation
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.RepositoryItemBinding
import com.lagradost.cloudstream3.databinding.RepositoryItemTvBinding
import com.lagradost.cloudstream3.plugins.RepositoryManager.PREBUILT_REPOSITORIES
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.clipboardHelper
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import com.lagradost.cloudstream3.utils.txt

class RepoAdapter(
    val isSetup: Boolean,
    val clickCallback: RepoAdapter.(RepositoryData) -> Unit,
    val imageClickCallback: RepoAdapter.(RepositoryData) -> Unit,
    /** In setup mode the trash icons will be replaced with download icons */
) :
    NoStateAdapter<RepositoryData>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
        a.url == b.url
    })) {

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        val layout = if (isLayout(TV)) RepositoryItemTvBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        ) else RepositoryItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolderState(layout)
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        when (val binding = holder.view) {
            is RepositoryItemBinding -> clearImage(binding.entryIcon)
            is RepositoryItemTvBinding -> clearImage(binding.entryIcon)
        }
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: RepositoryData, position: Int) {
        val isPrebuilt = PREBUILT_REPOSITORIES.contains(item)
        val drawable =
            if (isSetup) R.drawable.netflix_download else R.drawable.ic_baseline_delete_outline_24
        when (val binding = holder.view) {
            is RepositoryItemTvBinding -> {
                binding.apply {
                    // Only shows icon if on setup or if it isn't a prebuilt repo.
                    // No delete buttons on prebuilt repos.
                    if (!isPrebuilt || isSetup) {
                        actionButton.setImageResource(drawable)
                    }

                    actionButton.setOnClickListener {
                        imageClickCallback(item)
                    }

                    repositoryItemRoot.setOnClickListener {
                        clickCallback(item)
                    }
                    mainText.text = item.name
                    // Phase 8.9: show only the source host, not the raw URL
                    subText.text = hostLabel(item.url)
                    if (!item.iconUrl.isNullOrEmpty()) {
                        entryIcon.loadImage(item.iconUrl) {
                            // Phase 8.9: circle-mask real repo icons/avatars
                            transformations(CircleCropTransformation())
                            error(
                                getImageFromDrawable(
                                    binding.root.context,
                                    R.drawable.ic_github_logo
                                )
                            )
                        }
                    } else {
                        entryIcon.loadImage(R.drawable.ic_github_logo)
                    }
                }
            }

            is RepositoryItemBinding -> {
                binding.apply {
                    // Only shows icon if on setup or if it isn't a prebuilt repo.
                    // No delete buttons on prebuilt repos.
                    if (!isPrebuilt || isSetup) {
                        actionButton.setImageResource(drawable)
                    }

                    actionButton.setOnClickListener {
                        imageClickCallback(item)
                    }

                    repositoryItemRoot.setOnClickListener {
                        clickCallback(item)
                    }

                    repositoryItemRoot.setOnLongClickListener {
                        val shareableRepoData =
                            "${item.name}$SHAREABLE_REPO_SEPARATOR\n ${item.url}"
                        clipboardHelper(txt(R.string.repo_copy_label), shareableRepoData)
                        true
                    }

                    mainText.text = item.name
                    // Phase 8.9: show only the source host, not the raw URL
                    subText.text = hostLabel(item.url)
                    if (!item.iconUrl.isNullOrEmpty()) {
                        entryIcon.loadImage(item.iconUrl) {
                            // Phase 8.9: circle-mask real repo icons/avatars
                            transformations(CircleCropTransformation())
                            error(
                                getImageFromDrawable(
                                    binding.root.context,
                                    R.drawable.ic_github_logo
                                )
                            )
                        }
                    } else {
                        entryIcon.loadImage(R.drawable.ic_github_logo)
                    }
                }
            }
        }
    }

    companion object {
        const val SHAREABLE_REPO_SEPARATOR = " : "

        /** Phase 8.9: the muted second line shows just the repository's host
         * (e.g. raw.githubusercontent.com) instead of the full raw URL, which
         * was long, noisy and user-unfriendly. The full URL is still used for
         * clicks and the long-press copy action; this is display-only. */
        fun hostLabel(url: String): String =
            runCatching { Uri.parse(url).host }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: url
    }
}