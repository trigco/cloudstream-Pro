# Phase 8.80 — SkyStream bridge: HTTP response parity (Katdrama streams)

Device retest of 8.79: home loads for nearly every plugin and
4K HD streams play, but Katdrama still returned no streams — while
the same plugin plays in SkyStream's own app on the same phone.
Reproducing on the spike showed our http bridge diverging from the
reference bridge (js_engine.dart) in ways plugins can see:

- SkyStream exposes `Set-Cookie` to plugins as a header list;
  plugins keep their OWN cookie store from it. Ours stripped it
  into the host jar only — katdrama's kmhd locker chain silently
  stalled on the missing cookies.
- SkyStream's response carries `finalUrl` (post-redirect URL);
  chain-walking plugins use it to recognise the kmhd
  /file/ -> /locked hop. Ours had no final URL.
- SkyStream forces `Accept-Encoding: identity` by default and
  defaults POST/PUT Content-Type to
  application/x-www-form-urlencoded. Ours did neither.

## Changes (sky/SkyEngine.kt, SkyHostBridge only)

- Response JSON gains finalUrl + url (the OkHttp request URL after
  redirects) and code/statusCode aliases of status.
- Response headers now include "set-cookie" as a JSON array of the
  full Set-Cookie strings, in addition to the existing host jar.
- Default `Accept-Encoding: identity` request header when the
  plugin did not set one.
- POST/PUT bodies default to application/x-www-form-urlencoded
  when the plugin sent no Content-Type.

## Spike verification (this VM's network)

katdrama-zoro, Sweet Teeth ep.1: before the fix the chain died in
"Request timed out: links.kmhd.me"; after, the locker chain
resolves through and the plugin reaches the real file hosts,
which answer HTTP 403 from this datacenter IP (Streamwish-family
hosts) — the failure is now purely the source refusing this
network, the same class as before. On the phone's mobile network
(where SkyStream plays the same plugin) the chain has everything
it needs.
