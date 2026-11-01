package com.inatbox

class InatCocukProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_B03_bguqxzvRzyvPeiExd_xO" },
) {
    override var mainUrl = "https://diziboxen.help"
}
