# Phase 8.45 — crash fix + Categories in Settings Content (PR #7)

From his 8.44 device test:

1. **Crash on "＋ New list" (his crash log).** The overview
   passed `bundleOf("listId" to null)`; the nav runtime stores
   that as a non-String value and dies with "Wrong argument
   type for 'listId' in argument savedState" — and the crash
   put the app in Safe Mode. Fix: navigate with NO arguments
   for a new list (the destination default is null anyway).
2. **Stuck download queue — same cascade, not a new bug.**
   After the crash, the process carries lastError; the
   DownloadQueueService's job early-returns while a crash
   record exists (upstream crash-loop protection), so queued
   items never start and the pill spins on queue membership.
   A full app restart clears it (the error file is consumed
   on read). No downloader code changed; fixing the crash
   removes the trigger.
3. **Categories placement (his correction).** The manager
   entry moves OUT of Settings → Layout (8.44) into the
   Settings home's CONTENT card as its own "Categories" row,
   next to Providers / Extensions — everything (lists,
   builder) happens inside it. The Home provider switcher
   keeps listing the custom names, as he asked; Home itself
   is unchanged.
