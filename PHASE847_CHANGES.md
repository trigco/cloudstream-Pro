# Phase 8.47 — his 8.46 verdict round

Downloads (he confirmed downloads now run in the background
and finish with a Done notification; the gaps were live
feedback and library visibility):
1. Completed downloads never appeared on the Downloads page.
   Root cause: DownloadMetaData.close() only re-saves the
   file info for HLS / unknown totals, so a DASH download's
   saved info stayed totalBytes = 0 and the Downloads page
   filtered it out. downloadDash now persists the final
   size into the file-info template at success.
2. Downloads page refreshes live when a download completes
   (DownloadViewModel listens to downloadStatusEvent IsDone).
3. The queue foreground notification is now LIVE: active
   download's name as the title, percent + MB (or MB so far
   with an indeterminate bar for DASH), updated ~1/s from
   downloadProgressEvent; the counts version clears it.

Custom categories:
4. Netflix / Prime Video / Hotstar still showed no usable
   categories: those plugins declare a single BLANK mainPage
   entry and build real categories inside getMainPage (the
   way Home itself fetches them). Providers whose declared
   names are all blank are now classified dynamic — the
   builder fetches their homepage categories on tap, like
   Home does — and blank declared names are filtered out.
5. Status-bar overlap on the Categories pages fixed:
   both custom-list fragments now apply the status-bar
   inset (they are plain Fragments without fixLayout).
6. Custom lists now live inside the ORIGINAL provider
   picker (his new instruction, superseding the 8.46
   revert for the chooser dialog only): appended at the
   end with a ★ prefix, the active list is checked, and
   tapping one activates it — one place manages providers
   and custom lists. Editing still happens in
   Settings → Content → Categories.

Home:
7. Custom-list hero loads progressively: rows post
   immediately and the hero preview re-posts after EACH
   successful page load instead of blocking on up to 3
   sequential loads.
