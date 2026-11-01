package com.inatbox

class InatMubiProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_pfMNTPEDYNErCM1RafOgADx-" },
) {
    override var mainUrl = "https://diziboxen.help"
}
