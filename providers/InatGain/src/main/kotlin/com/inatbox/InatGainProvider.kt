package com.inatbox

class InatGainProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_jz59NDtYLsawgRseigIswAEf" },
) {
    override var mainUrl = "https://diziboxen.help"
}
