package com.lagradost.cloudstream3.ui.result

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.OvershootInterpolator
import android.view.animation.ScaleAnimation
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.CastItemBinding
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.newSharedPool
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

class ActorAdaptor(
    private var nextFocusUpId: Int? = null,
    private val focusCallback: (View?) -> Unit = {}
) : NoStateAdapter<ActorData>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.actor.name == b.actor.name
})) {
    companion object {
        val sharedPool =
            newSharedPool { setMaxRecycledViews(CONTENT, 10) }
    }

    // Easier to store it here than to store it in the ActorData
    val inverted: HashMap<ActorData, Boolean> = hashMapOf()

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        return ViewHolderState(
            CastItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        when (val binding = holder.view) {
            is CastItemBinding -> {
                clearImage(binding.actorImage)
            }
        }
    }

    override fun onUpdateContent(holder: ViewHolderState<Any>, item: ActorData, position: Int) {
        when (val binding = holder.view) {
            is CastItemBinding -> {
                val anim: Animation = ScaleAnimation(
                    0.8f, 1f,
                    0.8f, 1f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f
                )
                anim.fillAfter = true
                anim.duration = 200
                anim.interpolator = OvershootInterpolator()
                binding.voiceActorImageHolder2.startAnimation(anim)
            }
        }
        super.onUpdateContent(holder, item, position)
    }

    private fun showActorOverview(anchor: View, item: ActorData) {
        val context = anchor.context
        val dialog = BottomSheetDialog(context)
        val density = context.resources.displayMetrics.density
        fun Int.dp(): Int = (this * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 12.dp(), 24.dp(), 28.dp())
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.primaryGrayBackground))
                setCornerRadii(floatArrayOf(28f, 28f, 28f, 28f, 0f, 0f, 0f, 0f))
            }
        }

        // A compact drag handle gives the sheet a clear, modern affordance.
        content.addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.grayTextColor))
                cornerRadius = 2.dp().toFloat()
            }
            alpha = 0.45f
        }, LinearLayout.LayoutParams(36.dp(), 4.dp()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = 20.dp()
        })

        val portrait = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(context, R.color.iconGrayBackground))
                cornerRadius = 22.dp().toFloat()
            }
            clipToOutline = true
            contentDescription = item.actor.name
            loadImage(item.actor.image)
        }
        content.addView(portrait, LinearLayout.LayoutParams(116.dp(), 148.dp()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = 18.dp()
        })

        content.addView(TextView(context).apply {
            text = item.actor.name
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.textColor))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val role = item.roleString ?: item.role?.let {
            context.getString(
                when (it) {
                    ActorRole.Main -> R.string.actor_main
                    ActorRole.Supporting -> R.string.actor_supporting
                    ActorRole.Background -> R.string.actor_background
                }
            )
        }
        if (!role.isNullOrBlank()) {
            content.addView(TextView(context).apply {
                text = role
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 6.dp(), 0, 0)
                setTextColor(ContextCompat.getColor(context, R.color.grayTextColor))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        item.voiceActor?.let { voice ->
            content.addView(TextView(context).apply {
                text = context.getString(R.string.actor_overview_voice_actor, voice.name)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 16.dp(), 0, 0)
                setTextColor(context.getColor(R.color.textColor))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        content.addView(TextView(context).apply {
            text = context.getString(R.string.actor_overview_source_note)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 14.dp(), 0, 0)
            setTextColor(context.getColor(R.color.grayTextColor))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        dialog.setContentView(content)
        dialog.show()
    }

    override fun onBindContent(holder: ViewHolderState<Any>, item: ActorData, position: Int) {
        when (val binding = holder.view) {
            is CastItemBinding -> {
                val itemView = binding.root
                val isInverted = inverted.getOrDefault(item, false)

                val (mainImg, vaImage) = if (!isInverted || item.voiceActor?.image.isNullOrBlank()) {
                    Pair(item.actor.image, item.voiceActor?.image)
                } else {
                    Pair(item.voiceActor?.image, item.actor.image)
                }

                // Fix tv focus escaping the recyclerview
                if (position == 0) {
                    itemView.nextFocusLeftId = R.id.result_cast_items
                } else if ((position - 1) == itemCount) {
                    itemView.nextFocusRightId = R.id.result_cast_items
                }
                nextFocusUpId?.let {
                    itemView.nextFocusUpId = it
                }

                itemView.setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        focusCallback(v)
                    }
                }

                itemView.setOnClickListener {
                    showActorOverview(itemView, item)
                }

                // Long-press still lets users switch between actor and voice-actor portraits.
                itemView.setOnLongClickListener {
                    if (item.voiceActor != null) {
                        inverted[item] = !isInverted
                        this.onUpdateContent(holder, getItem(position), position)
                    }
                    true
                }

                binding.apply {
                    actorImage.loadImage(mainImg)

                    actorName.text = item.actor.name
                    item.role?.let {
                        actorExtra.context?.getString(
                            when (it) {
                                ActorRole.Main -> {
                                    R.string.actor_main
                                }

                                ActorRole.Supporting -> {
                                    R.string.actor_supporting
                                }

                                ActorRole.Background -> {
                                    R.string.actor_background
                                }
                            }
                        )?.let { text ->
                            actorExtra.isVisible = true
                            actorExtra.text = text
                        }
                    } ?: item.roleString?.let {
                        actorExtra.isVisible = true
                        actorExtra.text = it
                    } ?: run {
                        actorExtra.isVisible = false
                    }

                    if (item.voiceActor == null) {
                        voiceActorImageHolder.isVisible = false
                        voiceActorName.isVisible = false
                    } else {
                        voiceActorName.text = item.voiceActor?.name
                        if (!vaImage.isNullOrEmpty())
                            voiceActorImageHolder.isVisible = true
                        voiceActorImage.loadImage(vaImage)
                    }
                }
            }
        }
    }
}
