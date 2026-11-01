package com.inatbox

class InatYerliFilmlerProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_Q9tlKwxINorm6gHWwjkJigM9" },
) {
    override var mainUrl = "https://diziboxen.help"
}
