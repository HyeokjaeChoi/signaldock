// No Kotlin plugin override: the consumer builds with AGP 9's default built-in Kotlin, as a
// customer would (spec Q132, ADR-0009). scripts/verify-consumer.sh guards this.
plugins {
    alias(libs.plugins.android.application) apply false
}
