package com.inatbox

class InatDiniProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_1p78-p683RKRQmhQj_bhfaHO" },
) {
    override var mainUrl = "https://diziboxen.help"
}
