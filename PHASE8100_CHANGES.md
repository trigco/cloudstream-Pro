# 8.100 — ABI splits: arm64-v8a + armeabi-v7a + universal

**Decision (Md, 2026-11-01):** move off the single universal APK.
Every build now ships three APKs — an arm64-v8a split (his 64-bit
phone), an armeabi-v7a split (32-bit TV / older phones), and the
universal APK kept as the fallback that runs everywhere.

**Branch:** `abi-splits` (from master `691869fc`, v1.4.0).
**Files changed: 2** — `app/build.gradle.kts`, this doc.
No workflow change, no other build config touched.

## Config added (`app/build.gradle.kts`)

```kotlin
splits {
    abi {
        isEnable = true
        reset()
        include("arm64-v8a", "armeabi-v7a")
        isUniversalApk = true
    }
}
```

`reset()` + the explicit include list drops x86 / x86_64 (emulator
only). `isUniversalApk = true` keeps the universal APK alongside
the two splits.

## versionCode scheme

Per-output override via the AGP `androidComponents.onVariants`
API (`variant.outputs` → `output.versionCode`), in the same block
that already registers the git-hash asset source:

```
output versionCode = base versionCode * 10 + ABI index
    universal    (no ABI filter) -> +0
    armeabi-v7a                  -> +1
    arm64-v8a                    -> +2
```

"Base" is each output's effective versionCode read back from the
variant (stable flavor: the catalog value, currently 68 →
680/681/682; prerelease flavor: its timestamp value, scaled the
same way). The universal APK deliberately keeps the lowest code
of a build, so a device can always update from the universal APK
to its native split of the same build, never the reverse.

## Artifact expectation

No CI workflow change: `pull_request.yml`'s upload-artifact path
is already the glob `app/build/outputs/apk/prerelease/debug/*.apk`,
which matches every split output. Each PR build's
`pull-request-build` artifact should now contain three APKs,
named in AGP's split pattern:

- `app-arm64-v8a-prerelease-debug.apk` (~55 MB expected)
- `app-armeabi-v7a-prerelease-debug.apk` (~55 MB expected)
- `app-universal-prerelease-debug.apk` (~85 MB, same as before)

(Sizes are the pre-split measurement of the v1.4.0 universal APK:
11 native libs × 4 ABIs, 19.8–24.4 MB uncompressed each — a
per-ABI APK drops the other three ABIs' libraries.)

The same three outputs will appear under
`app/build/outputs/apk/prerelease/release/` for the master
pre-release workflow.
