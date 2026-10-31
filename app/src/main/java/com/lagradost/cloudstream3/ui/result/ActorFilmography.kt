package com.lagradost.cloudstream3.ui.result

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.databinding.FilmoItemBinding
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder.cleanPosterTitle
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.FamilyMode
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 8.93: an actor's filmography without TMDB and without
 * sending the user to a Wikipedia search (his complaint about the
 * old cast behaviour). Two keyless sources, merged:
 *
 *  - **TVmaze** (api.tvmaze.com, no key): people search → cast
 *    credits. Credit payloads carry the show + character names in
 *    their `_links`; posters/years come from one `/shows/{id}`
 *    call per credit (capped, parallel).
 *  - **Wikidata** (no key): `wbsearchentities` for the person,
 *    then a SPARQL query for works listing them as cast member
 *    (P161), with year (P577/P580) and IMDb id (P345). This is
 *    where the movies come from — TVmaze is TV-only.
 *
 * Wrong-person guard: we know the title the user came from, so a
 * candidate only counts as verified when one of its works matches
 * that title (cleaned comparison). Unverified fallbacks are used
 * only under the rules in [fetch]. Results are cached per actor
 * in SharedPreferences (`actor_filmo_v1`) so reopening the actor
 * page is instant.
 */
object ActorFilmography {

    private const val PREFS = "actor_filmo_v1"
    private const val USER_AGENT = "CloudStreamPro/1.0 (myself@nitrogen.bond)"
    private const val MAX_TVMAZE_CANDIDATES = 5
    private const val MAX_SHOW_DETAIL_FETCHES = 24
    private const val MAX_WIKIDATA_CANDIDATES = 5
    private const val MAX_ENTRIES = 48

    @Serializable
    data class FilmoEntry(
        val title: String,
        val year: Int? = null,
        val posterUrl: String? = null,
        val character: String? = null,
        val imdbId: String? = null,
    )

    @Serializable
    data class ActorFilmo(
        val personImage: String? = null,
        val entries: List<FilmoEntry> = emptyList(),
    )

    /** Title comparison key: poster-title cleaning, then a plain
     *  lowercase alphanumeric fold (drops "[English]"-style tags,
     *  punctuation and spacing differences between sources). */
    fun cleanTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return cleanPosterTitle(raw)
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Resolves [name]'s filmography. [sourceTitle] is the title
     * the user tapped the actor from (may be null). Returns null
     * when neither source yields anything usable; the caller then
     * falls back to an in-app name search.
     */
    suspend fun fetch(
        context: Context,
        name: String,
        sourceTitle: String?,
    ): ActorFilmo? {
        val cacheKey = cleanTitle(name)
        if (cacheKey.isNotBlank()) {
            cached(context, cacheKey)?.let { return it }
        }
        val result = try {
            resolve(name, sourceTitle)
        } catch (e: Throwable) {
            logError(e)
            null
        } ?: return null
        if (result.entries.isNotEmpty() && cacheKey.isNotBlank()) {
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(cacheKey, result.toJson())
                    .apply()
            } catch (e: Throwable) {
                logError(e)
            }
        }
        return result
    }

    private fun cached(context: Context, cacheKey: String): ActorFilmo? {
        val raw = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(cacheKey, null)
        } catch (_: Throwable) {
            null
        } ?: return null
        return tryParseJson<ActorFilmo>(raw)?.takeIf { it.entries.isNotEmpty() }
    }

    private suspend fun resolve(name: String, sourceTitle: String?): ActorFilmo? {
        val sourceClean = cleanTitle(sourceTitle)
        val tv = resolveTvMaze(name, sourceClean)
        val wd = resolveWikidata(
            name = name,
            sourceClean = sourceClean,
            tvMazeVerified = tv?.verified == true,
        )

        val merged = LinkedHashMap<String, FilmoEntry>()
        fun keyOf(e: FilmoEntry): String =
            cleanTitle(e.title) + "|" + (e.year?.toString() ?: "")

        // TVmaze first: it owns posters + character names.
        tv?.filmo?.entries.orEmpty().forEach { e -> merged[keyOf(e)] = e }
        wd.orEmpty().forEach { e ->
            val key = keyOf(e)
            val existing = merged[key]
            if (existing == null) {
                // A year-less TVmaze entry and a dated Wikidata entry
                // for the same work should merge, not duplicate.
                val yearlessKey = cleanTitle(e.title) + "|"
                val yearless = merged[yearlessKey]
                if (yearless != null && e.year != null) {
                    merged.remove(yearlessKey)
                    merged[key] = yearless.copy(year = e.year, imdbId = e.imdbId)
                } else {
                    merged[key] = e
                }
            } else if (existing.imdbId == null && e.imdbId != null) {
                merged[key] = existing.copy(imdbId = e.imdbId)
            }
        }

        var entries = merged.values.toList()
        if (FamilyMode.isEnabled()) {
            entries = entries.filter { !FamilyMode.isAdultTitle(it.title) }
        }
        entries = entries.sortedWith(
            compareByDescending<FilmoEntry> { it.year ?: Int.MIN_VALUE }
                .thenBy { it.title.lowercase() }
        ).take(MAX_ENTRIES)
        if (entries.isEmpty()) return null
        return ActorFilmo(
            personImage = tv?.filmo?.personImage,
            entries = entries,
        )
    }

    // ---------- TVmaze ----------

    private data class TvPersonResult(
        val filmo: ActorFilmo,
        val verified: Boolean,
    )

    /**
     * Returns the chosen person's TV credits (with posters/years
     * filled in) and whether the source-title check verified them.
     * Candidates are tried in search order; the first one whose
     * credits contain the source title wins immediately, otherwise
     * the first candidate with any credits is the fallback.
     */
    private suspend fun resolveTvMaze(
        name: String,
        sourceClean: String,
    ): TvPersonResult? {
        val hits = try {
            app.get(
                "https://api.tvmaze.com/search/people?q=${Uri.encode(name)}"
            ).parsedSafe<List<TvMazePersonHit>>()
        } catch (e: Throwable) {
            logError(e)
            null
        } ?: return null

        var fallback: Pair<TvMazePerson, List<FilmoEntry>>? = null
        for (hit in hits.take(MAX_TVMAZE_CANDIDATES)) {
            val person = hit.person ?: continue
            val credits = fetchTvCredits(person.id, person.name ?: name) ?: continue
            if (credits.isEmpty()) continue
            val verified = sourceClean.isNotBlank() &&
                credits.any { cleanTitle(it.title) == sourceClean }
            if (verified) {
                return TvPersonResult(
                    ActorFilmo(person.image?.original ?: person.image?.medium, credits),
                    verified = true,
                )
            }
            if (fallback == null) fallback = person to credits
        }
        val (person, credits) = fallback ?: return null
        return TvPersonResult(
            ActorFilmo(person.image?.original ?: person.image?.medium, credits),
            verified = false,
        )
    }

    /** Raw cast credits for one person: title + character from the
     *  `_links` block (the `_embed=show` form is ignored by the API
     *  today), then posters/years via `/shows/{id}` for the first
     *  [MAX_SHOW_DETAIL_FETCHES] shows. Self appearances billed as
     *  the actor's own name are dropped. */
    private suspend fun fetchTvCredits(
        personId: Int,
        personName: String,
    ): List<FilmoEntry>? {
        val credits = try {
            app.get(
                "https://api.tvmaze.com/people/$personId/castcredits"
            ).parsedSafe<List<TvMazeCredit>>()
        } catch (e: Throwable) {
            logError(e)
            null
        } ?: return null

        data class RawCredit(
            val showId: Int,
            val title: String,
            val character: String?,
        )

        val personClean = cleanTitle(personName)
        val raw = credits.mapNotNull { credit ->
            val show = credit.links?.show ?: return@mapNotNull null
            val title = show.name?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val showId = show.href?.substringAfterLast('/')?.toIntOrNull()
                ?: return@mapNotNull null
            val character = credit.links.character?.name
                ?.takeIf { it.isNotBlank() }
            // Talk-show style self appearances (billed under the
            // actor's own name) carry no filmography value.
            if (credit.self == true && character != null &&
                cleanTitle(character) == personClean
            ) {
                return@mapNotNull null
            }
            RawCredit(showId, title, character)
        }.distinctBy { it.showId }

        val detailed = raw.take(MAX_SHOW_DETAIL_FETCHES).amap { credit ->
            val show = try {
                app.get("https://api.tvmaze.com/shows/${credit.showId}")
                    .parsedSafe<TvMazeShow>()
            } catch (e: Throwable) {
                logError(e)
                null
            }
            FilmoEntry(
                title = credit.title,
                year = show?.premiered?.take(4)?.toIntOrNull(),
                posterUrl = show?.image?.medium ?: show?.image?.original,
                character = credit.character,
            )
        }
        val rest = raw.drop(MAX_SHOW_DETAIL_FETCHES).map {
            FilmoEntry(title = it.title, character = it.character)
        }
        return detailed + rest
    }

    // ---------- Wikidata ----------

    /**
     * Movie + series works from Wikidata. Candidate order comes
     * from `wbsearchentities`; a candidate is accepted when its
     * works verify against the source title, or — when TVmaze
     * already verified the person (or there is no source title) —
     * when its label exactly matches the actor's name.
     */
    private suspend fun resolveWikidata(
        name: String,
        sourceClean: String,
        tvMazeVerified: Boolean,
    ): List<FilmoEntry>? {
        val candidates = try {
            val text = app.get(
                "https://www.wikidata.org/w/api.php?action=wbsearchentities" +
                    "&search=${Uri.encode(name)}&language=en&type=item" +
                    "&format=json&limit=$MAX_WIKIDATA_CANDIDATES",
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
            val root = mapper.readTree(text)
            root.path("search").mapNotNull { node ->
                val id = node.path("id").asText(null) ?: return@mapNotNull null
                val label = node.path("label").asText(null).orEmpty()
                id to label
            }
        } catch (e: Throwable) {
            logError(e)
            null
        } ?: return null

        val nameClean = cleanTitle(name)
        var fallback: List<FilmoEntry>? = null
        for ((qid, label) in candidates) {
            val works = fetchWikidataWorks(qid) ?: continue
            if (works.isEmpty()) continue
            val verified = sourceClean.isNotBlank() &&
                works.any { cleanTitle(it.title) == sourceClean }
            if (verified) return works
            if (fallback == null &&
                (tvMazeVerified || cleanTitle(label) == nameClean)
            ) {
                fallback = works
            }
        }
        return fallback
    }

    private suspend fun fetchWikidataWorks(qid: String): List<FilmoEntry>? {
        val sparql = """
            SELECT ?work ?workLabel ?date ?imdb WHERE {
              ?work wdt:P161 wd:$qid .
              OPTIONAL { ?work wdt:P577 ?d1 }
              OPTIONAL { ?work wdt:P580 ?d2 }
              BIND(COALESCE(?d1, ?d2) AS ?date)
              OPTIONAL { ?work wdt:P345 ?imdb }
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
            } LIMIT 150
        """.trimIndent()
        val text = try {
            app.get(
                "https://query.wikidata.org/sparql?query=${Uri.encode(sparql)}&format=json",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/sparql-results+json",
                )
            ).text
        } catch (e: Throwable) {
            logError(e)
            return null
        }
        return try {
            val rows = mapper.readTree(text).path("results").path("bindings")
            val seen = HashSet<String>()
            val out = ArrayList<FilmoEntry>()
            for (row in rows) {
                val title = row.path("workLabel").path("value").asText(null)
                    ?.takeIf { it.isNotBlank() } ?: continue
                // Unlabelled items come back as their QID — useless as a title.
                if (title.matches(Regex("Q\\d+"))) continue
                val year = row.path("date").path("value").asText(null)
                    ?.take(4)?.toIntOrNull()
                val imdb = row.path("imdb").path("value").asText(null)
                    ?.takeIf { it.isNotBlank() }
                val key = cleanTitle(title) + "|" + (year?.toString() ?: "")
                if (!seen.add(key)) continue
                out.add(FilmoEntry(title = title, year = year, imdbId = imdb))
            }
            out
        } catch (e: Throwable) {
            logError(e)
            null
        }
    }

    // ---------- TVmaze payload models (dual-annotated like the
    // Wikipedia models in ActorFragment: NiceHttp's parser accepts
    // either annotation style) ----------

    @Serializable
    private data class TvMazeImage(
        @JsonProperty("medium") @SerialName("medium") val medium: String? = null,
        @JsonProperty("original") @SerialName("original") val original: String? = null,
    )

    @Serializable
    private data class TvMazePerson(
        @JsonProperty("id") @SerialName("id") val id: Int = 0,
        @JsonProperty("name") @SerialName("name") val name: String? = null,
        @JsonProperty("image") @SerialName("image") val image: TvMazeImage? = null,
    )

    @Serializable
    private data class TvMazePersonHit(
        @JsonProperty("score") @SerialName("score") val score: Double? = null,
        @JsonProperty("person") @SerialName("person") val person: TvMazePerson? = null,
    )

    @Serializable
    private data class TvMazeLink(
        @JsonProperty("href") @SerialName("href") val href: String? = null,
        @JsonProperty("name") @SerialName("name") val name: String? = null,
    )

    @Serializable
    private data class TvMazeCreditLinks(
        @JsonProperty("show") @SerialName("show") val show: TvMazeLink? = null,
        @JsonProperty("character") @SerialName("character") val character: TvMazeLink? = null,
    )

    @Serializable
    private data class TvMazeCredit(
        @JsonProperty("self") @SerialName("self") val self: Boolean? = null,
        @JsonProperty("voice") @SerialName("voice") val voice: Boolean? = null,
        @JsonProperty("_links") @SerialName("_links") val links: TvMazeCreditLinks? = null,
    )

    @Serializable
    private data class TvMazeShow(
        @JsonProperty("id") @SerialName("id") val id: Int = 0,
        @JsonProperty("name") @SerialName("name") val name: String? = null,
        @JsonProperty("premiered") @SerialName("premiered") val premiered: String? = null,
        @JsonProperty("image") @SerialName("image") val image: TvMazeImage? = null,
    )
}

/** Phase 8.93: filmography grid adapter (Actor page). */
class FilmoAdapter(
    private val onClick: (ActorFilmography.FilmoEntry) -> Unit,
) : RecyclerView.Adapter<FilmoAdapter.FilmoHolder>() {

    private var items: List<ActorFilmography.FilmoEntry> = emptyList()

    fun submit(newItems: List<ActorFilmography.FilmoEntry>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    inner class FilmoHolder(val binding: FilmoItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilmoHolder {
        return FilmoHolder(
            FilmoItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
    }

    override fun onBindViewHolder(holder: FilmoHolder, position: Int) {
        val entry = items[position]
        val binding = holder.binding
        binding.filmoTitle.text = entry.title
        binding.filmoSub.text = listOfNotNull(
            entry.year?.toString(),
            entry.character?.takeIf { it.isNotBlank() },
        ).joinToString(" • ")
        binding.filmoInitials.text = initialsForActor(entry.title)
        if (entry.posterUrl.isNullOrBlank()) {
            binding.filmoPoster.setImageDrawable(null)
        } else {
            binding.filmoPoster.loadImage(entry.posterUrl)
        }
        binding.root.setOnClickListener { onClick(entry) }
    }
}
