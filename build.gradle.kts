plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.ktlint) apply false
}

subprojects {
    // Release builds pass -PappVersion=<tag> (Dockerfile ARG APP_VERSION); local builds report "dev".
    version = providers.gradleProperty("appVersion").getOrElse("dev")

    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        filter {
            exclude("**/school/server/store/db/**")
            exclude { element ->
                element.file.invariantSeparatorsPath.contains("generated")
            }
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
