package com.inatbox

class InatNetflixProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_b6c0fgN5DT1TlQLs3X4DFEUC" },
) {
    override var mainUrl = "https://diziboxen.help"
}
