package com.inatbox

class InatDisneyProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_nKFKpIsaKwC_hjUOBLcrYJrI" },
) {
    override var mainUrl = "https://diziboxen.help"
}
