plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// Eine Versionsnummer für Handy und Uhr, gepflegt in gradle.properties.
// versionCode = MMmmpp mal 10 plus Geräteziffer (0 = Handy, 1 = Uhr): Google Play verlangt
// für Handy- und Uhr-App mit derselben applicationId unterschiedliche versionCodes.
val bernosVersion: String = providers.gradleProperty("bernos.version").get()
require(Regex("""\d+\.\d+\.\d+""").matches(bernosVersion)) {
    "bernos.version muss <major>.<minor>.<patch> sein, ist aber '$bernosVersion'"
}
val (versionMajor, versionMinor, versionPatch) = bernosVersion.split(".").map(String::toInt)
require(versionMinor < 100 && versionPatch < 100) { "minor und patch müssen kleiner als 100 sein" }

extra["bernosVersionName"] = bernosVersion
extra["bernosVersionCodeBase"] = (versionMajor * 10_000 + versionMinor * 100 + versionPatch) * 10
