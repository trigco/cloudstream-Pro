# Phase 8.79 — SkyStream bridge: host-surface parity fixes

Device verdict on 8.78: install/repos worked and some plugins ran
(CineStream home, HindMoviez), but others failed and streams were
unreliable. The failures were reproduced on the JVM spike
(~/workspace/cloudstream/jvm-test/src/SkyRun.kt) against the user's
actual installed plugins, and each one traced to a piece of
SkyStream's host surface the 8.78 polyfill lacked — not to plugin or
network problems:

- katdrama-zoro died in getHome: `'URL' is not defined` (37 uses of
  `new URL(...)` in that plugin).
- vegamovies died in load(): `'btoa' is not defined`.
- cinestream/movies4u use URL, atob, TextEncoder/TextDecoder and
  AbortController on their stream paths.
- castletv needs the native-assist crypto channel
  (`crypto.decryptAES` / `sendMessage("crypto_decrypt_aes", ...)`)
  for its encrypted payloads.

## Changes

- `sky/SkyPolyfill.kt` — CODE replaced with the spike-verified
  polyfill (kept byte-identical to SkySpike.kt's SKY_POLYFILL):
  adds URL + URLSearchParams (relative resolution, live
  searchParams), atob/btoa (URL-safe tolerant decode), UTF-8
  TextEncoder/TextDecoder, AbortController/AbortSignal,
  sendMessage() + nativeMd5/nativeSha256 (skyNativeSync binding),
  crypto.decryptAES / crypto.pbkdf2 (skyNativeAsync binding), and
  a pure-JS js_unpack.
- `sky/SkyEngine.kt` — registers the skyNativeSync /
  skyNativeAsync bindings in every plugin session and adds the
  `SkyNative` implementation with the reference's semantics
  (aes_decrypt.dart): whitespace-tolerant re-padded base64, AES
  CBC/PKCS7 (GCM 128-bit tag and ECB honoured), lowercase-hex
  MD5/SHA-256, hand-rolled PBKDF2-HMAC-SHA256 (the platform
  factory is API 26+, we target 23).

## Spike regression after the fix (JVM, this VM's network)

- 4K HD: home/search/load + 4 streams (was 0 streams).
- CastleTV: full chain incl. AES-decrypted stream resolution
  (was HOME_ERROR).
- katdrama (ZORO): home + load (was 'URL' is not defined);
  katdrama (TJ): home + load as before.
- VegaMovies/RogMovies: home/search/load (was LOAD_ERROR 'btoa');
  streams still 0 from this VM.
- HindMoviez: full chain + 6 streams (unchanged).
- CineStream: home/search/load (streams 0 from this VM).
- Movies4U: past the URL failure; its home scrapes a Google
  interstitial from datacenter IPs (source-side).

The remaining stream empties complete with success envelopes and
no missing-global errors — they are the sources not answering
this VM's datacenter network; the device (mobile IP) retest is
the deciding check, same as the Nuvio bridge's history.
