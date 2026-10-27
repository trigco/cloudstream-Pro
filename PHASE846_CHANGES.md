# Phase 8.46 — his 8.45-round corrections

1. **Builder UI redone** (HomeCustomListEditFragment): ONE
   scrolling sectioned list — selected entries (drag-reorder)
   on top, then all categories grouped under clear provider
   section bars ("ApiName • count"); explicit white text on
   category rows; Add as a proper pill button. The old 50/50
   two-list split is gone.
2. **All providers' categories**: providers that declare no
   mainPage (Netflix-type multi-provider plugins) showed
   nothing before. They now appear as a "tap to load its
   categories" row; tapping fetches the homepage once and
   lists the real categories. Such entries are saved with a
   dynamic flag and Home loads them by fetching the homepage
   and picking the category by name (expand included).
3. **Provider switcher reverted** (his correction): the
   home provider icon opens the ORIGINAL provider picker —
   no custom lists inside it. Picking a provider exits
   custom mode. Custom lists are managed only in
   Settings → Content → Categories and show on Home while
   active. Also fixed: deactivating a list in Settings no
   longer leaves stale custom rows on Home (state cleared +
   forced reload).
4. **Downloads stuck on "Queued" — real cause**:
   DownloadQueueService's queue job died instantly whenever
   the in-memory crash record (lastError) was set, so after
   ANY crash every download stayed Queued forever in that
   session — and the alternative download never started
   either. The gate is now the safe-mode FILE only
   (PluginManager.checkSafeModeFile): real safe mode
   (extensions off) still stops the queue, a stale crash
   record no longer freezes it.
