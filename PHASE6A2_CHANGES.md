# Phase 6A-2 — Pull-to-refresh on Home

User ask: pulling down on Home should refresh with a SMOOTH animation ("like a soft hand
stroking"). The app previously had no pull-to-refresh anywhere (no SwipeRefreshLayout in
the project). Implemented locally only — not pushed.

## Files changed (5)

1. `gradle/libs.versions.toml` — new version + library entries (see Dependency).
2. `app/build.gradle.kts` — `implementation(libs.swiperefreshlayout)`.
3. `app/src/main/res/layout/fragment_home.xml` — RecyclerView wrapped in SwipeRefreshLayout.
4. `app/src/main/res/layout/fragment_home_tv.xml` — structural mirror of the wrapper (see
   "Why the TV layout changed" — no TV behavior change).
5. `app/src/main/java/com/lagradost/cloudstream3/ui/home/HomeFragment.kt` — wiring (+34
   lines, incl. one new import: `androidx.core.content.ContextCompat`).

No `res/drawable` files touched (another agent owns those). `HomeViewModel` untouched —
only its existing public API is called.

## Dependency added

`androidx.swiperefreshlayout:swiperefreshlayout:1.1.0` via the version catalog
(`swiperefreshlayout = "1.1.0"` + matching `[libraries]` entry).

- It did not exist anywhere in the project before (grep-verified: zero hits for
  `SwipeRefreshLayout`/`swiperefreshlayout` in code, layouts, and build files), and no
  other androidx artifact brings it transitively in a usable form.
- 1.1.0 is the current stable release of this artifact and fits the project's androidx
  era (appcompat 1.7.1, core-ktx 1.18.0, material 1.14.0).

## Layout change

`fragment_home.xml`: `home_master_recycler` (the home feed RecyclerView, a direct child of
the `home_root` FrameLayout) is now wrapped in

```xml
<androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    android:id="@+id/home_swipe_refresh"
    android:layout_width="match_parent"
    android:layout_height="wrap_content">
    <!-- RecyclerView here, byte-identical attributes -->
</androidx.swiperefreshlayout.widget.SwipeRefreshLayout>
```

- The SwipeRefreshLayout takes the RecyclerView's exact place and layout params
  (match_parent × wrap_content) in the root FrameLayout, so sibling overlays
  (`home_loading`, `home_loading_error`, FABs) keep their z-order and behavior.
- The RecyclerView's own id and every attribute are unchanged (it is still bound by id
  through the generated binding; the wrapper only adds a parent).

### Why the TV layout changed

`HomeFragment.pickLayout()` returns `fragment_home` on phone and `fragment_home_tv`
otherwise, but both are bound with the SAME `FragmentHomeBinding`
(`BindingCreator.Bind(FragmentHomeBinding::bind)`). The generated `bind()` requires every
phone-layout id to exist in the TV layout — which is why, today, every single
`fragment_home.xml` id is mirrored in `fragment_home_tv.xml` (verified: the TV file
contains all phone ids, zero missing). Adding `@+id/home_swipe_refresh` to the phone
layout only would make `bind()` throw "missing required view" on TV. The TV layout
therefore received the identical wrapper around its `home_master_recycler` as a pure
structural mirror. No TV behavior changes: all wiring is gated behind
`isLayout(PHONE)`, and a SwipeRefreshLayout never triggers without a touch pull (TV is
remote/focus driven). This mirrors the file's existing pattern of placeholder/mirrored
views for the other form factor.

## Reload path wired

The refresh listener calls exactly what the existing reload-provider button
(`home_preview_reload_provider`) calls:

```kotlin
homeViewModel.loadAndCancel(
    homeViewModel.apiName.value ?: noneApi.name,
    forceReload = true,
    fromUI = true
)
```

`loadAndCancel` with `forceReload = true` bypasses its "already have a valid homepage /
already loading" early-return, so a pull always performs a real reload of the current
provider's homepage (same as the reload button and the FAB long-press).

## Loading signal (spinner stop)

The existing `observe(homeViewModel.page)` in `onBindingCreated` is the single source of
truth — it already drives the full-screen loading shimmer. `homeSwipeRefresh.isRefreshing
= false` was added in exactly the two terminal branches, next to the lines that hide
that shimmer:

- `Resource.Success` → after `homeLoadingShimmer.stopShimmer()`
- `Resource.Failure` → alongside `homeLoading.isVisible = false`

The `Resource.Loading` branch deliberately does not touch the spinner (it must keep
spinning). Every `loadAndCancel(forceReload = true)` path in `HomeViewModel` terminates
in a `_page.postValue` of Success/Failure (via the internal `load(api)` job), so the
spinner cannot be left running by a completed reload.

Belt-and-braces: `onDestroyView()` also sets
`binding?.homeSwipeRefresh?.isRefreshing = false` before `super.onDestroyView()`
(binding is the nullable `T?` from BaseFragment), so a destroyed view never carries a
stuck spinner.

Note: during a pull-refresh the app's standard full-screen shimmer also appears and the
list hides — that is the pre-existing behavior of the `page` Loading state for ANY
reload (the reload-provider button behaves identically today). This change does not
alter that state handling; it only adds the pull gesture + spinner around it.

## Smooth-feel tuning (all inside the `isLayout(PHONE)` setup block)

- `setColorSchemeColors`: `R.color.white` (#FFF) then `R.color.grayTextColor`
  (#B3B3B3) — existing colors only, resolved with `ContextCompat.getColor` (the idiom
  used in the player UI code).
- `setProgressBackgroundColorSchemeColor`: `R.color.primaryGrayBackground` (#121212,
  the Phase 5A near-black surface).
- `setProgressViewOffset(false, (-40).toPx, 72.toPx)`: the spinner starts 40dp above
  the top edge (hidden) and settles at 72dp — a long, gentle travel instead of the
  default short drop.
- `setDistanceToTriggerSync(120.toPx)`: the pull must travel 120dp before release
  triggers the refresh — a deep, unhurried pull ("soft hand" feel) instead of the
  default ~64dp hair trigger.
- dp→px uses the project's existing `UIHelper.Int.toPx` (already imported and used in
  HomeFragment). No new helpers, no other new APIs.

## Risks

1. **CI is the first compiler** (no local Android SDK): the Kotlin changes are small and
   mirror in-file patterns, but the new binding field (`homeSwipeRefresh`) only exists
   after ViewBinding regenerates from the edited layouts — expected to be automatic.
2. **SwipeRefreshLayout height = wrap_content** (inherited from the RecyclerView's
   original params, per "wrapper takes the child's place"). In the match_parent root
   FrameLayout this measures identically to before (content is always taller than the
   screen). If a future layout change made the feed shorter than the screen, the
   refresh area would only cover the content height.
3. **TV runtime**: the mirrored wrapper is required for binding (see above). It is
   never wired on TV, but SwipeRefreshLayout does sit in the TV view hierarchy as the
   RecyclerView's parent; it does not intercept key/focus events, so D-pad navigation
   should be unaffected — worth a glance if TV focus ever misbehaves near the top of
   the feed.
4. **Nested scrolling**: the hero preview ViewPager inside the feed scrolls
   horizontally; SwipeRefreshLayout only engages on vertical pulls when the
   RecyclerView cannot scroll up (its standard `canChildScrollUp` check), so the
   hero auto-slide/drag should be unaffected — same arrangement as any standard
   SRL + RecyclerView setup.
5. **Dependency**: swiperefreshlayout 1.1.0 is a single, well-known androidx artifact
   (depends only on androidx.core/annotation/interpolator), so resolution risk is low.
