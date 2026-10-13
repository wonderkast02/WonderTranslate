version = "0.1.14"
description = "WonderTranslate: automatic chat translation with native Aliucord update support"

aliucord {
    // Official Aliucord updater metadata; release artifact verified before updater.json is advanced.
    updateUrl.set("https://raw.githubusercontent.com/wonderkast02/WonderTranslate/refs/heads/main/updater.json")
    buildUrl.set("https://github.com/wonderkast02/WonderTranslate/releases/download/v0.1.14/WonderTranslate.zip")
    github("https://github.com/wonderkast02/WonderTranslate")
    changelog.set("R31: 0.1.14 version-only update to validate Aliucord native update; translation and UI unchanged")
    deploy.set(false)
}
