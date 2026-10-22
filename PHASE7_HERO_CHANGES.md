# Phase 7 — Home Hero as an Inset Rounded Card

Scope: HOME HERO only (phone). Files changed:

- `app/src/main/res/layout/home_scroll_view.xml` (restructured)
- `app/src/main/res/drawable/bg_quality_badge.xml` (new)
- `app/src/main/java/com/lagradost/cloudstream3/ui/home/HomeScrollAdapter.kt` (phone branch only)

`fragment_home_head.xml` was **not** touched (pager + action row unchanged), and the TV hero (`home_scroll_view_tv.xml` + the adapter's TV branch) is untouched.

## Structure change (home_scroll_view.xml)

- The page root stays a `match_parent` FrameLayout, so the ViewPager2 page size and paging/auto-slide math are completely unchanged — the inset lives *inside* the page.
- All previous page content (backdrop `home_scroll_preview`, `title_shadow_top`, `title_shadow`, text block) moved inside a new `androidx.cardview.widget.CardView` (`@+id/home_scroll_card`) wrapping an inner FrameLayout. CardView is the project's own precedent (already used in `fragment_home_head.xml` for the profile card) and gives real clipping: the backdrop image and shadows are clipped to the rounded corners, no square corners bleed out.
  - Margins: start/end **16dp**, top **14dp**, bottom **108dp** — the bottom margin deliberately clears the 100dp action-row holder (`home_preview_title_holder` in `fragment_home_head.xml`, bottom-gravity, untouched), so the Play/Info row now sits just below the card instead of over the art.
  - `app:cardCornerRadius="24dp"`, `app:cardElevation="0dp"` (no shadow halo), `app:cardBackgroundColor="@color/primaryGrayBackground"` (existing color, shows only while the backdrop loads).
- The page root background changed from `?attr/primaryGrayBackground` to `?attr/primaryBlackBackground` — the same attr `fragment_home_head.xml` uses for the header background — so the margins exposed around the card blend seamlessly with the header in every theme variant (previously that attr only painted the full-bleed page, where it was never visible).
- Text block: now inside the card, bottom-gravity with `layout_marginBottom="16dp"` (was 100dp against the page bottom) and 16dp side padding.
  - **Title** (`home_scroll_preview_title`): visible again, start-aligned (was centered), bold white `?attr/white`, 17sp, `maxLines=2` + ellipsize so long names can't push the meta line off the card.
  - **Meta line** (`@+id/home_scroll_preview_meta`, new): directly under the title, 13sp, `@color/grayTextColor` (#B3B3B3), default `gone`; the adapter shows it when it built at least one part.
  - **Quality badge** (`@+id/home_scroll_quality_badge`, new): top-end of the card, 12dp margin, background `@drawable/bg_quality_badge` (rectangle, 8dp corners, solid `@color/black_overlay` #66000000 — same existing color as `bg_circle_overlay`), white bold 11sp, 8dp×3dp padding, default `gone`.
  - Logo (`home_preview_logo`) and tags (`home_scroll_preview_tags`) remain `gone`, exactly as Phase 6 left them.

## Model fields used (HomeScrollAdapter, phone branch)

The pager item model is **`LoadResponse`** (confirmed in `HomeScrollAdapter` / `MainAPI.kt`), *not* SearchResponse:

| Meta/badge part | Source | Notes |
|---|---|---|
| Title | `item.name` (via existing `.html()`) | unchanged |
| Year | `item.year: Int?` | part skipped when null |
| Rating | `item.score: Score?` → `score.toStringNull(0.1, 10, 1, false, '.')` | 1 decimal; returns null for unset/~0 scores so no "★ 0.0". Exact call mirrors `ResultViewModel2` (result page rating). Rendered as `★ 8.6` |
| Type | `item.type: TvType` → `R.string.*_singular` | Full 18-value mapping copied from the result page's `typeText` in `ResultViewModel2` (Movie/AnimeMovie→"Movie", TvSeries→"Series", Anime→"Anime", …). Resolved via `binding.root.context.getString(...)` (adapter precedent: `AccountAdapter`) |
| Quality | **does not exist** | see below |

Meta line is joined with `" • "` (e.g. `2026 • ★ 8.6 • Movie`) and the view is hidden only if the parts list were empty — in practice the type part always exists, so the line always shows at least the type.

Title visibility is set explicitly on every bind (`isGone = false`), and meta/badge visibility likewise, so ViewPager2 view recycling can't leak state between pages.

## ⚠️ Quality badge has NO data source (important fallback)

`LoadResponse` and all its implementations (`MovieLoadResponse`, `TvSeriesLoadResponse`, `AnimeLoadResponse`, `LiveStreamLoadResponse`) have **no quality field**. `quality: SearchQuality?` exists only on `SearchResponse`, which is not what the hero pager binds — hero items are full `LoadResponse`s fetched per item, and by load time the search-result quality is not carried over.

Therefore, per the spec's own fallback rule ("GONE when quality is null/blank"), the badge view + drawable are in place but the adapter keeps the badge **gone for every item**. If a future change gives the hero a quality source (e.g. threading the originating SearchResponse's quality through, or a provider-supplied field), showing it is a two-line change in the phone branch: set `homeScrollQualityBadge.text` and toggle `isGone`. The meta line likewise omits the quality segment for the same reason — it renders `year • ★ rating • type`.

## Auto-slide / behaviour

Unchanged: the adapter still binds the same items, `bindLogo()` is still intentionally not called (it force-toggles title/logo visibility), tags stay bound-but-hidden as before, and the pager, its timer and the action row in `fragment_home_head.xml` were not modified.

## Risks

1. **Card height shrinks** by 14+108dp inside the fixed 500dp pager frame in `fragment_home_head.xml` (that file was out of scope). The hero art is now ~378dp tall — intended (floating card look), but if the parent prefers a taller card, the lever is the pager's 500dp height in `fragment_home_head.xml`, not this file.
2. **Double scrims**: the head layout's own 220dp bottom scrim (in `fragment_home_head.xml`, untouched) now partially falls on the black strip below the card instead of on art; over `primaryBlackBackground` it is invisible, and the in-card `title_shadow` still backs the title/meta. No visual conflict expected.
3. **Title on art**: backdrop art often carries its own stylized title (the reason Phase 6 hid the text title) — this design intentionally brings the text title back per the Phase 7 spec; it sits bottom-left over the in-card bottom shadow.
4. **CardView + ViewPager2**: CardView is a FrameLayout, so page layout/recycling is unaffected; elevation is 0 so there is no shadow to clip during page transitions.
5. XML validated with `xml.dom.minidom` (both files). Kotlin uses only project-precedented APIs (`Score.toStringNull`, `context.getString`, `isGone`); CI remains the first compiler, as usual for this worktree.
