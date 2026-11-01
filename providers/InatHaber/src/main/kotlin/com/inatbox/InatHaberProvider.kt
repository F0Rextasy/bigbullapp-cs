package com.inatbox

class InatHaberProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_MZOJ3eZThBuLqmehbmefruZ4" },
) {
    override var mainUrl = "https://diziboxen.help"
}
