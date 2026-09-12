package com.nikolay.assistvoice

/**
 * Yandex OAuth client for the "Умный дом Яндекса" integration (see
 * SmartHomeAuthActivity / YandexOAuthDeviceFlow).
 *
 * The real id/secret are never in source — this repo is public, so anything
 * committed here stays readable in git history forever, even after
 * rotation. They come from BuildConfig instead, which build.gradle.kts
 * populates from the YANDEX_CLIENT_ID/YANDEX_CLIENT_SECRET env vars — empty
 * locally unless you set them yourself, populated in CI from the
 * repository's GitHub Actions secrets of the same names (Settings → Secrets
 * and variables → Actions on github.com/feed4me/Assist-Voice). Same pattern
 * this project already uses for the release-signing keystore.
 */
object SmartHomeConfig {
    val YANDEX_CLIENT_ID: String = BuildConfig.YANDEX_CLIENT_ID
    val YANDEX_CLIENT_SECRET: String = BuildConfig.YANDEX_CLIENT_SECRET
}
